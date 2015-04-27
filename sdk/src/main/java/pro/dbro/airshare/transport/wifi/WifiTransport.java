package pro.dbro.airshare.transport.wifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.CountDownTimer;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import pro.dbro.airshare.transport.Transport;
import timber.log.Timber;

/**
 * Wifi Direct Transport. Requires Android 4.0.
 *
 * Proof-of-concept. Not yet ready for use. Supports a single WiFi Direct connection at time
 *
 * Created by davidbrodsky on 2/21/15.
 */
public class WifiTransport extends Transport implements WifiP2pManager.ConnectionInfoListener, WifiP2pManager.ChannelListener {

    /** Values to id transport useful in bit fields */
    public static final int TRANSPORT_CODE = 2;

    public static final int DEFAULT_MTU_BYTES = 1024;

    private static final int PORT = 8787;
    private static final int SOCKET_TIMEOUT_MS = 3000;

    private Context context;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private IntentFilter intentFilter;

    private Thread socketThread;

    private boolean connectionDesired = true;
    private boolean discoveringPeers = false;
    private boolean localPrefersToHost = false;
    private boolean retryChannel = true;

    private BiMap<String, String> macToIpAddress = HashBiMap.create();

    private HashSet<String> connectingPeers = new HashSet<>();
    private HashSet<String> connectedPeers = new HashSet<>();

    private static int PEER_DISCOVERY_TIMEOUT_MS = 30 * 1000;
    private CountDownTimer peerDiscoveryTimer;

    /** Identifier -> Queue of outgoing buffers */
    private final HashMap<String, ArrayDeque<byte[]>> outBuffers = new HashMap<>();

    public class DeviceActionListener implements WifiP2pManager.ActionListener {

        private WifiP2pDevice device;

        public DeviceActionListener(WifiP2pDevice device) {
            this.device = device;
        }

        @Override
        public void onSuccess() {
            connectingPeers.add(device.deviceAddress);
            Timber.d("Connection request initiated");
        }

        @Override
        public void onFailure(int reason) {
            Timber.d("Failed to connect with reason: %s", getDescriptionForActionListenerError(reason));
        }
    };

    public WifiTransport(@NonNull Context context,
                         @NonNull String serviceName,
                         @NonNull TransportCallback callback) {

        super(serviceName, callback);
        this.context = context;

        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);

    }

    // <editor-fold desc="Transport">

    @Override
    public boolean sendData(byte[] data, Set<String> identifiers) {
        boolean didSendAll = true;

        for (String identifier : identifiers) {
            boolean didSend = sendData(data, identifier);

            if (!didSend) didSendAll = false;
        }
        return didSendAll;
    }

    @Override
    public boolean sendData(@NonNull byte[] data, String identifier) {

        queueOutgoingData(data, identifier);

//        if (isConnectedTo(identifier))
//            return transmitOutgoingDataForConnectedPeer(identifier);

        return true;
    }

    @Override
    public void advertise() {
        localPrefersToHost = true;
        initializeWiFiDirect();
    }

    @Override
    public void scanForPeers() {
        localPrefersToHost = false;
        initializeWiFiDirect();
    }

    @Override
    public void stop() {
        Timber.d("Stopping WiFi");
        context.unregisterReceiver(wifiDirectReceiver);
        connectionDesired = false;
        if (socketThread != null)
            socketThread.interrupt();

        if (discoveringPeers)
            manager.stopPeerDiscovery(channel, null);

        manager.cancelConnect(channel, null);
        manager.removeGroup(channel, null);

        connectedPeers.clear();
        connectingPeers.clear();

        outBuffers.clear();

        discoveringPeers = false;
    }

    @Override
    public int getTransportCode() {
        return TRANSPORT_CODE;
    }

    @Override
    public int getMtuForIdentifier(String identifier) {
//        Integer mtu = central.getMtuForIdentifier(identifier);
//        return (mtu == null ? DEFAULT_MTU_BYTES : mtu ) - 10;
        return DEFAULT_MTU_BYTES;
    }

    // </editor-fold desc="Transport">

    private void initializeWiFiDirect() {
        if (channel != null) {
            Timber.w("Channel already present");
        }

        channel = manager.initialize(context, Looper.getMainLooper(), this);

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        context.registerReceiver(wifiDirectReceiver, intentFilter);

        // Begin peer discovery, if appropriate, when Wi-Fi Direct ready
    }

    private void discoverPeers() {
        if (discoveringPeers) {
            Timber.w("Already discovering peers. For WiFi Transport there is no meaning to simultaneously 'scanning' and 'advertising");
            return;
        }

        discoveringPeers = true;

        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Timber.d("Peer discovery initiated");
                // Restart P2P stack if discovery fails
                peerDiscoveryTimer = new CountDownTimer(PEER_DISCOVERY_TIMEOUT_MS, PEER_DISCOVERY_TIMEOUT_MS) {

                    public void onTick(long millisUntilFinished) {
                    }

                    public void onFinish() {
                        Timber.d("Peer Discovery timed out, restarting P2P stack");
                        resetP2PStack();
                    }
                }.start();
            }

            @Override
            public void onFailure(int reason) {
                String reasonDescription = getDescriptionForActionListenerError(reason);
                Timber.e("Peer discovery failed with reason " + reasonDescription);
            }
        });
    }

    public void onWifiDirectReady() {
        // It appears that if only one device enters discovery mode,
        // a connection will never be made. Instead, both devices enter discovery mode
        // but only the client will request connection when a peer is discovered
        //if (!localPrefersToHost*/)
        discoverPeers();
    }

    public void resetP2PStack() {
        stop();
        initializeWiFiDirect();
    }

    /**
     * Queue data for transmission to identifier
     */
    private void queueOutgoingData(byte[] data, String identifier) {
        synchronized (outBuffers) {
            if (!outBuffers.containsKey(identifier)) {
                outBuffers.put(identifier, new ArrayDeque<byte[]>());
            }

            int mtu = getMtuForIdentifier(identifier);

            int readIdx = 0;
            while (readIdx < data.length) {

                if (data.length - readIdx > mtu) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(mtu);
                    bos.write(data, readIdx, mtu);
                    outBuffers.get(identifier).add(bos.toByteArray());
                    readIdx += mtu;
                } else {
                    outBuffers.get(identifier).add(data);
                    break;
                }
            }

            Timber.d("Queued %d outgoing bytes for %s", data.length, identifier);
            outBuffers.notify();
        }
    }

    private boolean isConnectedTo(String identifier) {
        return connectedPeers.contains(identifier);
    }

    private BroadcastReceiver wifiDirectReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            final WifiP2pDevice device = (WifiP2pDevice) intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
            final WifiP2pDeviceList deviceList = (WifiP2pDeviceList) intent.getParcelableExtra(WifiP2pManager.EXTRA_P2P_DEVICE_LIST);
            final WifiP2pGroup p2pGroup = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP);
            final WifiP2pInfo p2pInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);

            switch(action) {

                case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:

                    // UI update to indicate wifi p2p status.
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        // Wifi Direct mode is enabled
                        Timber.d("Wifi Direct enabled");
                        onWifiDirectReady();
                    } else {
                        Timber.w("Wifi Direct is not enabled");
                    }

                    break;

                case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION:

                    // request available peers from the wifi p2p manager. This is an
                    // asynchronous call and the calling activity is notified with a
                    // callback on PeerListListener.onPeersAvailable()
                    if (manager != null && discoveringPeers) {
                        // TODO: How to handle when multiple P2P peers are available?
                        int numPeers = deviceList.getDeviceList().size();
                        String firstPeerStatus = numPeers > 0 ? "First peer status + " + getDescriptionForDeviceStatus(deviceList.getDeviceList().iterator().next().status) :
                                                                "";
                        Timber.d("Got %d available peers. %s", numPeers, firstPeerStatus);
                        // Only the client should initiate connection
                        if (!localPrefersToHost && numPeers > 0) {
                            WifiP2pDevice connectableDevice = deviceList.getDeviceList().iterator().next();
                            if (connectableDevice.status == WifiP2pDevice.AVAILABLE) {
                                // If the peer status is available, the prior invitation is void
                                connectingPeers.remove(connectableDevice.deviceAddress);
                                initiateConnectionToPeer(connectableDevice);
                            }
                        } /*else
                            Timber.d("Local is host so allow client to begin connection");*/
                    } else {
                        Timber.w("Peers changed, but %s", manager == null ? "manager is null" : "discoveringPeers is false");
                    }

                    break;

                case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:

                    Timber.d("Local device is %s of %s P2P group '%s' with %d clients",
                             p2pGroup.isGroupOwner() ? "owner" : "client",
                             p2pInfo.groupFormed ? "formed" : "unformed",
                             p2pGroup.getNetworkName(),
                             p2pGroup.getClientList().size());

                    if (manager == null) {
                        Timber.d("Connection changed but manager null.");
                        return;
                    }

                    NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

                    if (networkInfo.isConnected()) {
                        // we are connected with the other device, request connection
                        // info to find group owner IP
                        Timber.d("Connected to %s", device != null ? device.deviceAddress : "");
                        if (discoveringPeers) {
                            Timber.d("Connected to %s. Requesting connection info", device != null ? device.deviceAddress : "");
                            manager.requestConnectionInfo(channel, WifiTransport.this);
                        }
                    } else {
                        Timber.d("Network is %s", networkInfo.getState());
                    }

                    break;

                case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION:

                    Timber.d("Local device status %s", getDescriptionForDeviceStatus(device.status));

                    break;

                case WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION:

                    boolean started = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, 0) ==
                                      WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED;

                    Timber.d("P2P Discovery %s", started ? "started" : "stopped");

                    break;
            }
        }
    };

    private void initiateConnectionToPeer(WifiP2pDevice device) {

        if (!connectedPeers.contains(device.deviceAddress) && !connectingPeers.contains(device.deviceAddress)) {

//            if (connectingPeers.contains(device.deviceAddress)) {
//                Timber.w("Had stale connection opening with peer. Closing and starting anew");
//                connectionDesired = false;
//                socketThread.interrupt();
                socketThread = null;
//            }

            final WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = device.deviceAddress;
            config.groupOwnerIntent = localPrefersToHost ? 15 : 0;
            /*if (connectionInitiated) {
                Timber.d("Canceling existing connection request to peer");
                manager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        connectionInitiated = false;
                        Timber.d("Cancelled stale connection attempt successfully");
                        connectionDesired = true;
                        manager.connect(channel, config, connectionInitiationListener);
                    }

                    @Override
                    public void onFailure(int reason) {
                        Timber.d("Failed to cancel stale connection attempt reason %s.", getDescriptionForActionListenerError(reason));

//                                connectionInitiated = false;
//                                Timber.d("Cancelled stale connection attempt successfully");
//                                connectionDesired = true;
//                                manager.connect(channel, config, connectionInitiationListener);
                    }
                });
            } else */

            Timber.d("Initiating connection as %s to %s type %s with status %s",
                    localPrefersToHost ? "host" : "client",
                    device.deviceAddress,
                    device.primaryDeviceType,
                    getDescriptionForDeviceStatus(device.status));

            manager.connect(channel, config, new DeviceActionListener(device));

        } else {
            Timber.w("Cannot honor request to connect to peer. Already connected");
        }
    }

    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo info) {
        // After a connection we request connection info
        if (info.groupFormed && info.isGroupOwner) {
            Timber.d("This device is the host (group owner)");
            // At this point we want to open a socket and receive data + the client address
            startServerSocket();

        } else if (info.groupFormed) {
            // The other device is the group owner. For now we assume groups of fixed size 2
            Timber.d("Connected to %s (local is client)", info.groupOwnerAddress.getHostAddress());

            if (connectingPeers.size() == 1) {
                // We can associate the MAC address we initially discovered
                // with the IP address now available
                String mac = connectingPeers.iterator().next();
                connectedPeers.add(info.groupOwnerAddress.getHostAddress());
                macToIpAddress.put(mac, info.groupOwnerAddress.getHostAddress());
                Timber.d("associated %s with %s", mac,
                        info.groupOwnerAddress.getHostAddress());
            } else {
                    Iterator<String> iter = connectingPeers.iterator();
                    StringBuilder builder = new StringBuilder();
                    while (iter.hasNext()) {
                        builder.append(iter.next());
                        builder.append(", ");
                    }
                    Timber.w("Connecting to %d peers (%s)... cannot associate IP address of just-connected peer", connectingPeers.size(), builder.toString());
            }

            startClientSocket(info.groupOwnerAddress);
        }
    }

    public void startClientSocket(final InetAddress address) {
        socketThread = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    Socket socket = new Socket();
                    socket.bind(null);
                    Timber.d("Client opening socket to %s", address.getHostAddress());
                    socket.connect((new InetSocketAddress(address, PORT)), SOCKET_TIMEOUT_MS);

                    cancelPeerDiscoveryTimer();

                    Timber.d("Client connected to %s", address.getHostAddress());
                    callback.get().identifierUpdated(WifiTransport.this, address.getHostAddress(), ConnectionStatus.CONNECTED, true, null);

                    maintainSocket(null, socket, address.getHostAddress());

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        socketThread.start();
    }

    public void startServerSocket() {
        socketThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {

                    ServerSocket serverSocket = new ServerSocket(PORT);

                    Timber.d("Created Server socket. Waiting for connection");
                    Socket client = serverSocket.accept();

                    cancelPeerDiscoveryTimer();

                    String clientAddress = client.getInetAddress().getHostAddress();
                    Timber.d("Connected to %s (local is server)", clientAddress);

                    connectedPeers.add(clientAddress);
                    callback.get().identifierUpdated(WifiTransport.this, clientAddress, ConnectionStatus.CONNECTED, false, null);

                    maintainSocket(serverSocket, client, client.getInetAddress().getHostAddress());

                } catch (IOException e) {
                    Timber.e(e, "Failed to read socket inputstream");
                    e.printStackTrace();
                }
            }
        });
        socketThread.start();
    }

    /**
     * Maintains the given socket in a read / write loop until
     * {@link #connectionDesired} is set false.
     */
    private void maintainSocket(@Nullable ServerSocket serverSocket, Socket socket, String remoteAddress) {
        try {
            connectionDesired = true;
            socket.setSoTimeout(500);

            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream();

            byte[] buf = new byte[1024];
            int len;

            while (connectionDesired) {

                // Read incoming data
                try {
                    while ((len = inputStream.read(buf)) > 0) {
                        ByteArrayOutputStream os = new ByteArrayOutputStream(len);
                        os.write(buf, 0, len);
                        Timber.d("Got %d bytes from %s", len, remoteAddress);
                        callback.get().dataReceivedFromIdentifier(WifiTransport.this, os.toByteArray(), remoteAddress);
                    }
                } catch (SocketTimeoutException e) {
                    // No incoming data received
                    //Timber.d("No incoming data found in timeout period");
                } catch (SocketException e2) {
                    // Socket was closed
                    Timber.d("Socket closed");
                    break;
                }

                // Write outgoing data
                if (outBuffers.containsKey(remoteAddress) &&
                        outBuffers.get(remoteAddress).size() > 0) {

                    ArrayDeque<byte[]> outBuffersForPeer = outBuffers.get(remoteAddress);

                    while (outBuffers.get(remoteAddress).size() > 0) {
                        outputStream.write(outBuffersForPeer.peek());
                        Timber.d("Wrote %d bytes to %s", outBuffersForPeer.peek().length, remoteAddress);
                        callback.get().dataSentToIdentifier(WifiTransport.this, outBuffersForPeer.poll(), remoteAddress, null);
                    }

                }
            }

            outputStream.close();
            inputStream.close();
            socket.close();
            if (serverSocket != null) serverSocket.close();
            Timber.d("%s closed socket with %s", connectionDesired ? "remote" : "local", remoteAddress);
            if (connectionDesired) {
                // If we arrive here the connection was closed by the other party.
                stop();
            }

            if (callback.get() != null)
                callback.get().identifierUpdated(this, remoteAddress, ConnectionStatus.DISCONNECTED, !localPrefersToHost, null);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String getDescriptionForDeviceStatus(int status) {

        switch (status) {
            case WifiP2pDevice.CONNECTED:
                return "Connected";

            case WifiP2pDevice.INVITED:
                return "Invited";

            case WifiP2pDevice.FAILED:
                return "Failed";

            case WifiP2pDevice.AVAILABLE:
                return "Available";

            case WifiP2pDevice.UNAVAILABLE:
                return "Unavailable";

            default:
                return "?";
        }
    }

    private static String getDescriptionForActionListenerError(int error) {
        String reasonDescription = null;
        switch (error) {

            case WifiP2pManager.ERROR:
                reasonDescription = "Framework error";
                break;

            case WifiP2pManager.BUSY:
                reasonDescription = "Device busy";
                break;

            case WifiP2pManager.P2P_UNSUPPORTED:
                reasonDescription = "Device does not support WifiP2P";
                break;
        }

        return reasonDescription;
    }

    @Override
    public void onChannelDisconnected() {
        if (manager != null && !retryChannel) {
            Timber.d("Channel lost, retrying...");
            retryChannel = true;
            manager.initialize(context, Looper.getMainLooper(), this);
        } else {
            Timber.e("Severe! Channel is probably lost premanently. Try Disable/Re-Enable P2P.");
        }
    }

    private void cancelPeerDiscoveryTimer() {
        if (peerDiscoveryTimer != null) {
            peerDiscoveryTimer.cancel();
            peerDiscoveryTimer = null;
        }
    }
}
