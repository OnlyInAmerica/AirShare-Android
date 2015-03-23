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
import android.os.Looper;
import android.support.annotation.NonNull;

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
 * Created by davidbrodsky on 2/21/15.
 */
public class WifiTransport extends Transport implements WifiP2pManager.PeerListListener,
                                                        WifiP2pManager.ConnectionInfoListener {

    public static final int DEFAULT_MTU_BYTES = 1024;

    private static final int PORT = 8787;
    private static final int SOCKET_TIMEOUT_MS = 3000;

    private static final int BUFFER_SIZE = 500 * 1000;

    private Context context;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private IntentFilter intentFilter;

    private Thread socketThread;

    private boolean connectionDesired = true;

    private BiMap<String, String> macToIpAddress = HashBiMap.create();

    private HashSet<String> connectingPeers = new HashSet<>();
    private HashSet<String> connectedPeers = new HashSet<>();

    /** Identifier -> Queue of outgoing buffers */
    private final HashMap<String, ArrayDeque<byte[]>> outBuffers = new HashMap<>();

    public WifiTransport(@NonNull Context context,
                         @NonNull String serviceName,
                         @NonNull TransportCallback callback) {

        super(serviceName, callback);
        this.context = context;

        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(context, Looper.getMainLooper(), null);

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        context.registerReceiver(wifiDirectReceiver, intentFilter);
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
        Timber.e("Advertise not yet implemented");
    }

    @Override
    public void scanForPeers() {
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Timber.d("Peer discovery initiated");
            }

            @Override
            public void onFailure(int reason) {
                Timber.e("Peer discovery failed with reason " + reason);
            }
        });
    }

    @Override
    public void stop() {
        context.unregisterReceiver(wifiDirectReceiver);
        if (socketThread != null)
            socketThread.interrupt();
    }

    @Override
    public int getMtuForIdentifier(String identifier) {
//        Integer mtu = central.getMtuForIdentifier(identifier);
//        return (mtu == null ? DEFAULT_MTU_BYTES : mtu ) - 10;
        return DEFAULT_MTU_BYTES;
    }

    // </editor-fold desc="Transport">

    public void onWifiDirectReady() {
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
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {

                // UI update to indicate wifi p2p status.
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    // Wifi Direct mode is enabled
                    Timber.d("Wifi Direct enabled");
                    onWifiDirectReady();
                } else {
                    Timber.w("Wifi Direct is not enabled");
                }
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {

                // request available peers from the wifi p2p manager. This is an
                // asynchronous call and the calling activity is notified with a
                // callback on PeerListListener.onPeersAvailable()
                Timber.d("P2P peers changed");
                if (manager != null) {
                    manager.requestPeers(channel, WifiTransport.this);
                }
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {

                if (manager == null) {
                    return;
                }

                WifiP2pGroup p2pGroup = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP);
                WifiP2pInfo p2pInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                final WifiP2pDevice device = (WifiP2pDevice) intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);

                if (networkInfo.isConnected()) {
                    // we are connected with the other device, request connection
                    // info to find group owner IP
                    Timber.d("Connected to %s. Requesting connection info", device != null ? device.deviceAddress : "");
                    manager.requestConnectionInfo(channel, WifiTransport.this);
                } else {
                    Timber.d("Disconnected from %s", device != null ? device.deviceAddress : "");
                    // It's a disconnect
//                    activity.resetData();
                }
            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                WifiP2pDevice device = (WifiP2pDevice) intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                Timber.d("This device (%s) changed", device.deviceAddress);

//                DeviceListFragment fragment = (DeviceListFragment) activity.getFragmentManager()
//                        .findFragmentById(R.id.frag_list);
//                fragment.updateThisDevice((WifiP2pDevice) intent.getParcelableExtra(
//                        WifiP2pManager.EXTRA_WIFI_P2P_DEVICE));

            }
        }
    };

    @Override
    public void onPeersAvailable(WifiP2pDeviceList peers) {
        Timber.d("Got %d available peers", peers.getDeviceList().size());
        for (final WifiP2pDevice device : peers.getDeviceList()) {
            final WifiP2pConfig config = new WifiP2pConfig();

            if (!connectedPeers.contains(device.deviceAddress) &&
                !connectingPeers.contains(device.deviceAddress)) {

                connectingPeers.add(device.deviceAddress);

                config.deviceAddress = device.deviceAddress;
                Timber.d("Requesting connection to %s", config.deviceAddress);
                manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        Timber.d("Connection to %s initiated", config.deviceAddress);
                        connectedPeers.add(device.deviceAddress);
                    }

                    @Override
                    public void onFailure(int reason) {
                        Timber.d("Failed to connect to %s", config.deviceAddress);
                        connectingPeers.remove(device.deviceAddress);
                    }
                });
            }
            break;  // For now just try and connect to the 'first' available peer
        }
    }

    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo info) {
        // After a connection we request connection info

        if (info.groupFormed && info.isGroupOwner) {
            Timber.d("This device is the group owner");
            // At this point we want to open a socket and receive data + the client address
            startServerSocket();

        } else if (info.groupFormed) {
            // The other device is the group owner. For now we assume groups of fixed size 2
            Timber.d("Connected to %s (local is client)", info.groupOwnerAddress.getHostAddress());

            if (connectingPeers.size() == 1) {
                // We can associate the MAC address we initially discovered
                // with the IP address now available
                String mac = connectingPeers.iterator().next();
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
                    Thread.sleep(2000); // Ensure server is started
                    Timber.d("Client opening socket to %s", address.getHostAddress());
                    socket.connect((new InetSocketAddress(address, PORT)), SOCKET_TIMEOUT_MS);
                    Timber.d("Client connected to %s", address.getHostAddress());
                    callback.get().identifierUpdated(WifiTransport.this, address.getHostAddress(), ConnectionStatus.CONNECTED, null);

                    socket.setSoTimeout(500);
                    InputStream is = socket.getInputStream();
                    OutputStream os = socket.getOutputStream();

                    byte[] buf = new byte[1024];
                    int len;

                    while (connectionDesired) {
                        // TODO Allow disconnection :)
//                        synchronized (outBuffers) {
//
//                            // Wait for outgoing data. The client initiates the flow
//
//                            if (!outBuffers.containsKey(address.getHostAddress()) ||
//                                outBuffers.get(address.getHostAddress()).size() == 0) {
//                                Timber.d("Waiting for output data");
//                                outBuffers.wait();
//                            } else
//                                Timber.d("Output ready");
//                        }

                        // Write outgoing data
                        Timber.d("Writing outgoing data");
                        if (outBuffers.containsKey(address.getHostAddress()) &&
                            outBuffers.get(address.getHostAddress()).size() > 0) {

                            ArrayDeque<byte[]> outBuffersForPeer = outBuffers.get(address.getHostAddress());

                            while (outBuffers.get(address.getHostAddress()).size() > 0) {
                                os.write(outBuffersForPeer.peek());
                                Timber.d("Wrote %d bytes to %s", outBuffersForPeer.peek().length, address.getHostAddress());
                                callback.get().dataSentToIdentifier(WifiTransport.this, outBuffersForPeer.poll(), address.getHostAddress(), null);
                            }

                        } else
                            Timber.w("Was notified, but no messages ready");

                        // Read incoming data

                        Timber.d("Reading incoming data");
                        try {
                            while ((len = is.read(buf)) > 0) {
                                ByteArrayOutputStream bos = new ByteArrayOutputStream(len);
                                bos.write(buf, 0, len);
                                Timber.d("Got %d bytes from %s", len, address.getHostAddress());
                                callback.get().dataReceivedFromIdentifier(WifiTransport.this, bos.toByteArray(), address.getHostAddress());
                            }
                        } catch (SocketTimeoutException e) {
                            Timber.d("No incoming data in timeout period");
                        }
                    }

                } catch (IOException | InterruptedException e) {
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
                    String clientAddress = client.getInetAddress().getHostAddress();
                    Timber.d("Connected to %s (local is server)", clientAddress);

                    if (connectingPeers.size() == 1) {
                        // We can associate the MAC address we initially discovered
                        // with the IP address now available
                        String mac = connectingPeers.iterator().next();
                        macToIpAddress.put(mac, clientAddress);
                        Timber.d("associated %s with %s", mac,
                                client.getInetAddress().getHostAddress());
                    } else {
                        Iterator<String> iter = connectingPeers.iterator();
                        StringBuilder builder = new StringBuilder();
                        while (iter.hasNext()) {
                            builder.append(iter.next());
                            builder.append(", ");
                        }
                        Timber.w("Connecting to %d peers (%s)... cannot associate IP address of just-connected peer", connectingPeers.size(), builder.toString());
                    }

                    // Let the Client report initial connection. The Server will respond after identity is received
//                    callback.get().identifierUpdated(WifiTransport.this,
//                                                     client.getInetAddress().getHostAddress(),
//                                                     ConnectionStatus.CONNECTED,
//                                                     null);

                    connectedPeers.add(clientAddress);

                    client.setSoTimeout(500);
                    InputStream inputStream = client.getInputStream();
                    OutputStream outputStream = client.getOutputStream();

                    byte[] buf = new byte[1024];
                    int len;

                    while (connectionDesired) {
                        // TODO : Close the socket when requested

                        // Read incoming data
                        Timber.d("Reading incoming data");
                        try {
                            while ((len = inputStream.read(buf)) > 0) {
                                ByteArrayOutputStream os = new ByteArrayOutputStream(len);
                                os.write(buf, 0, len);
                                Timber.d("Got %d bytes from %s", len, client.getInetAddress().getHostAddress());
                                callback.get().dataReceivedFromIdentifier(WifiTransport.this, os.toByteArray(), client.getInetAddress().getHostAddress());
                            }
                        } catch (SocketTimeoutException e) {
                            Timber.d("No incoming data found in timeout period");
                        }

                        // Never proceeds from reading onward..

                        // Write outgoing data
                        Timber.d("Writing outgoing data");
                        if (outBuffers.containsKey(clientAddress) &&
                            outBuffers.get(clientAddress).size() > 0) {

                            ArrayDeque<byte[]> outBuffersForPeer = outBuffers.get(clientAddress);

                            while (outBuffers.get(clientAddress).size() > 0) {
                                outputStream.write(outBuffersForPeer.peek());
                                Timber.d("Wrote %d bytes to %s", outBuffersForPeer.peek().length, clientAddress);
                                callback.get().dataSentToIdentifier(WifiTransport.this, outBuffersForPeer.poll(), clientAddress, null);
                            }

                        }
                    }
                    outputStream.close();
                    inputStream.close();
                    Timber.d("Finished reading data from %s", client.getInetAddress().getHostAddress());


                } catch (IOException e) {
                    Timber.e(e, "Failed to read socket inputstream");
                    e.printStackTrace();
                }
            }
        });
        socketThread.start();
    }

//    private AsyncTask transferTask = new AsyncTask<Void, Void, String>() {
//
//        @Override
//        protected String doInBackground(Void... params) {
//            try {
//
//                /**
//                 * Create a server socket and wait for client connections. This
//                 * call blocks until a connection is accepted from a client
//                 */
//                ServerSocket serverSocket = new ServerSocket(8888);
//                Socket client = serverSocket.accept();
//
//                client.getOutputStream()
//
//                callback.get().dataReceivedFromIdentifier(this, );
//                return f.getAbsolutePath();
//            } catch (IOException e) {
//                Timber.e(e);
//                return null;
//            }
//        }
//
//        /**
//         * Start activity that can handle the JPEG image
//         */
//        @Override
//        protected void onPostExecute(String result) {
//            if (result != null) {
//                Intent intent = new Intent();
//                intent.setAction(android.content.Intent.ACTION_VIEW);
//                intent.setDataAndType(Uri.parse("file://" + result), "image/*");
//                context.startActivity(intent);
//            }
//        }
//    };
}
