package pro.dbro.airshare.transport.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;
import android.support.annotation.NonNull;
import android.util.Pair;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;

import pro.dbro.airshare.DataUtil;
import pro.dbro.airshare.transport.ConnectionGovernor;
import pro.dbro.airshare.transport.ConnectionListener;
import timber.log.Timber;

/**
 * A basic BLE Peripheral device discovered by centrals
 *
 * Created by davidbrodsky on 10/11/14.
 */
public class BLEPeripheral {

    /** Map of Responses to perform keyed by request characteristic & type pair */
    private HashMap<Pair<UUID, BLEPeripheralResponse.RequestType>, BLEPeripheralResponse> responses = new HashMap<>();
    /** Set of connected device addresses */
    private HashSet<String> connectedDevices = new HashSet<>();
    /** Map of cached response payloads keyed by request characteristic & type pair.
     * Used to respond to repeat requests provided by the framework when packetization is necessary
     * TODO We need to create a Key object that is specific to remote central, e.g: What happens when we're doing this with multiple centrals at once?
     */
    private HashMap<Pair<UUID, BLEPeripheralResponse.RequestType>, byte[]> cachedResponsePayloads = new HashMap<>();

    /** Set this value on each call to onCharacteristicWrite. If onExecuteWrite() is called before
     * all data is collected via calls to onCharacteristicWriteRequest,
     * use this key to resume the write request at the appropriate offset.
     */
    private Pair<UUID, BLEPeripheralResponse.RequestType> lastRequestKey;

    public interface BLEPeripheralConnectionGovernor {
        public boolean shouldConnectToCentral(BluetoothDevice potentialPeer);
    }

    private Context context;
    private UUID serviceUUID;
    private BluetoothAdapter btAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothGattServerCallback gattCallback;
    private ConnectionGovernor connectionGovernor;
    private ConnectionListener connectionListener;

    private boolean isAdvertising = false;

    /** Advertise Callback */
    private AdvertiseCallback mAdvCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            if (settingsInEffect != null) {
                logEvent("Advertise success TxPowerLv="
                        + settingsInEffect.getTxPowerLevel()
                        + " mode=" + settingsInEffect.getMode());
            } else {
                logEvent("Advertise success" );
            }
        }

        @Override
        public void onStartFailure(int errorCode) {
            logEvent("Advertising failed with code " + errorCode);
        }
    };

    // <editor-fold desc="Public API">

    public BLEPeripheral(@NonNull Context context, @NonNull UUID serviceUUID) {
        this.context = context;
        this.serviceUUID = serviceUUID;
        init();
    }

    public void setConnectionListener(ConnectionListener listener) {
        connectionListener = listener;
    }

    public void setGattCallback(BluetoothGattServerCallback callback) {
        gattCallback = callback;
    }

    /**
     * Start the BLE Peripheral advertisement. All responses should be added via
     * {@link #addResponse(BLEPeripheralResponse)} before this call.
     */
    public void start() {
        startAdvertising();
    }

    public void stop() {
        stopAdvertising();
    }

    public boolean isAdvertising() {
        return isAdvertising;
    }

    public BluetoothGattServer getGattServer() {
        return gattServer;
    }

    public void addResponse(BLEPeripheralResponse response) {
        Pair<UUID, BLEPeripheralResponse.RequestType> requestFilter = new Pair<>(response.characteristic.getUuid(), response.requestType);
        responses.put(requestFilter, response);
        logEvent(String.format("Registered %s response for %s", requestFilter.second, requestFilter.first));
    }

    public HashSet<String> getConnectedDeviceAddresses() {
        return connectedDevices;
    }

    public void setConnectionGovernor(ConnectionGovernor governor) {
        connectionGovernor = governor;
    }

    // </editor-fold>

    //<editor-fold desc="Private API">

    private void init() {
        // BLE check
        if (!BLEUtil.isBLESupported(context)) {
            logEvent("Bluetooth not supported.");
            return;
        }
        BluetoothManager manager = BLEUtil.getManager(context);
        if (manager != null) {
            btAdapter = manager.getAdapter();
        }
        if (btAdapter == null) {
            logEvent("Bluetooth unavailble.");
            return;
        }

    }

    private void startAdvertising() {
        if ((btAdapter != null) && (!isAdvertising)) {
            if (advertiser == null) {
                advertiser = btAdapter.getBluetoothLeAdvertiser();
            }
            if (advertiser != null) {
                logEvent("Starting GATT server");
                startGattServer();
                advertiser.startAdvertising(createAdvSettings(), createAdvData(), mAdvCallback);
            } else {
                logEvent("Unable to access Bluetooth LE Advertiser. Device not supported");
            }
        } else {
            if (isAdvertising)
                logEvent("Start Advertising called while advertising already in progress");
            else
                logEvent("Start Advertising WTF error");
        }
    }

    private void startGattServer() {
        BluetoothManager manager = BLEUtil.getManager(context);
        if (gattCallback == null)
            gattCallback = new BluetoothGattServerCallback() {
            @Override
            public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (connectedDevices.contains(device.getAddress())) {
                        // We're already connected (should never happen). Cancel connection
                        logEvent("Denied connection. Already connected to " + device.getAddress());
                        gattServer.cancelConnection(device);
                        return;
                    }

                    if (connectionGovernor != null && !connectionGovernor.shouldConnectToAddress(device.getAddress())) {
                        // The ConnectionGovernor denied the connection. Cancel connection
                        logEvent("Denied connection. ConnectionGovernor denied " + device.getAddress());
                        gattServer.cancelConnection(device);
                        return;
                    } else {
                        // Allow connection to proceed. Mark device connected
                        logEvent("Accepted connection to " + device.getAddress());
                        connectedDevices.add(device.getAddress());
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // We've disconnected
                    logEvent("Disconnected from " + device.getAddress());
                    connectedDevices.remove(device.getAddress());
                    if (connectionListener != null)
                        connectionListener.disconnectedFrom(device.getAddress());
                }
                super.onConnectionStateChange(device, status, newState);
            }

            @Override
            public void onServiceAdded(int status, BluetoothGattService service) {
                Timber.i("onServiceAdded", service.toString());
                super.onServiceAdded(status, service);
            }

            @Override
            public void onCharacteristicReadRequest(BluetoothDevice remoteCentral, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
                logEvent(String.format("onCharacteristicReadRequest for request %d on characteristic %s with offset %d", requestId, characteristic.getUuid().toString().substring(0,3), offset));

                BluetoothGattCharacteristic localCharacteristic = gattServer.getService(serviceUUID).getCharacteristic(characteristic.getUuid());
                Pair<UUID, BLEPeripheralResponse.RequestType> requestKey = new Pair<>(characteristic.getUuid(), BLEPeripheralResponse.RequestType.READ);

                if (localCharacteristic != null || !responses.containsKey(requestKey)) {
                    byte[] cachedResponse = null;
                    if (offset > 0) {
                        // This is a framework-generated follow-up request for another section of data
                        cachedResponse = cachedResponsePayloads.get(requestKey);
                    } else if (responses.containsKey(requestKey)) {
                        // This is a fresh request with a registered response
                        cachedResponse = responses.get(requestKey).respondToRequest(gattServer, remoteCentral, requestId, characteristic, false, true, null);
                        if (cachedResponse != null) {
                            // If a request was necessary, cache the result here
                            cachedResponsePayloads.put(requestKey, cachedResponse);
                        } else {
                            // No data was available for peer.
                            gattServer.sendResponse(remoteCentral, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null);
                            return;
                        }
                    }

                    if (cachedResponse == null) {
                        // A request with nonzero offset came through before the initial zero offset request
                        Timber.w("Invalid request order! Did a nonzero offset request come first?");
                        gattServer.sendResponse(remoteCentral, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null);
                        return;
                    }

                    byte[] toSend = new byte[cachedResponse.length - offset];
                    System.arraycopy(cachedResponse, offset, toSend, 0, toSend.length);
                    logEvent(String.format("Sending extended response chunk for offset %d : %s", offset, DataUtil.bytesToHex(toSend)));
                    try {
                        boolean success = gattServer.sendResponse(remoteCentral, requestId, BluetoothGatt.GATT_SUCCESS, offset, toSend);
                        Timber.w("SendResponse", "oncharacteristicread follow-up success: " + success);
                    } catch (NullPointerException e) {
                        // On Nexus 5 possibly an issue in the Broadcom IBluetoothGatt implementation
                        Timber.w("SendResponse", "NPE on oncharacteristicread follow-up");
                    }

                } else {
                    logEvent("CharacteristicReadRequest. Unrecognized characteristic " + characteristic.getUuid().toString());
                    // Request for unrecognized characteristic. Send GATT_FAILURE
                    try {
                        boolean success = gattServer.sendResponse(remoteCentral, requestId, BluetoothGatt.GATT_FAILURE, 0, new byte[] { 0x00 });
                        Timber.w("SendResponse", "oncharacteristicread failure. success: " + success);
                    } catch (NullPointerException e) {
                        // On Nexus 5 possibly an issue in the Broadcom IBluetoothGatt implementation
                        Timber.w("SendResponse", "NPE oncharacteristicread failure.");
                    }
                }
                super.onCharacteristicReadRequest(remoteCentral, requestId, offset, characteristic);
            }

            @Override
            public void onCharacteristicWriteRequest(BluetoothDevice remoteCentral, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                logEvent(String.format("onCharacteristicWriteRequest for request %d on characteristic %s with offset %d", requestId, characteristic.getUuid().toString().substring(0,3), offset));

                BluetoothGattCharacteristic localCharacteristic = gattServer.getService(serviceUUID).getCharacteristic(characteristic.getUuid());
                if (localCharacteristic != null) {
                    byte[] updatedData;
                    Pair<UUID, BLEPeripheralResponse.RequestType> requestKey = new Pair<>(characteristic.getUuid(), BLEPeripheralResponse.RequestType.WRITE);
                    if (!responses.containsKey(requestKey)) {
                        Timber.e("onCharacteristicWrite for request " + characteristic.getUuid() + " without registered response! Ignoring");
                        return;
                    }
                    if (offset == 0) {
                        // This is a fresh write request so start recording data.
                        updatedData = value;
                        cachedResponsePayloads.put(requestKey, updatedData); // Cache the payload data in case more is coming
                        logEvent(String.format("onCharacteristicWriteRequest had %d bytes, offset : %d", updatedData == null ? 0 : updatedData.length, offset));

                        if (responseNeeded) {
                            // Signal we received the write
                            try {
                                boolean success = gattServer.sendResponse(remoteCentral, requestId, BluetoothGatt.GATT_SUCCESS, offset, updatedData);
                                Timber.w("SendResponse", "oncharwrite success: " + success);
                            } catch (NullPointerException e) {
                                // On Nexus 5 possibly an issue in the Broadcom IBluetoothGatt implementation
                                Timber.w("SendResponse", "NPE oncharwrite");
                            }
                            logEvent("Notifying central we received data");
                        }
                    } else {
                        // this is a subsequent request with more data. Append to what we've received so far
                        byte[] cachedResponse = cachedResponsePayloads.get(requestKey);
                        int cachedResponseLength = (cachedResponse == null ? 0 : cachedResponse.length);
                        //if (cachedResponse.length != offset) logEvent(String.format("Got more data. Original payload len %d. offset %d (should be equal)", cachedResponse.length, offset));
                        updatedData = new byte[cachedResponseLength + value.length];
                        if (cachedResponseLength > 0)
                            System.arraycopy(cachedResponse, 0, updatedData, 0, cachedResponseLength);

                        System.arraycopy(value, 0, updatedData, cachedResponseLength, value.length);
                        logEvent(String.format("Got %d bytes for write request. New bytes: %s", updatedData.length, DataUtil.bytesToHex(value)));
                        logEvent(String.format("Accumulated bytes: %s", DataUtil.bytesToHex(updatedData)));
                    }

                    BLEPeripheralResponse response = responses.get(requestKey);
                    if (response.getExpectedPayloadLength() == updatedData.length) {
                        // We've accumulated all the data we need!
                        logEvent(String.format("Accumulated all data for %s request ", response.characteristic.getUuid()));
                        response.respondToRequest(gattServer, remoteCentral, requestId, characteristic, preparedWrite, responseNeeded, updatedData);
                        cachedResponsePayloads.remove(requestKey);
                    } else if (responseNeeded) {
                        // Signal we received the partial write and are ready for more data
                        try {
                            boolean success = gattServer.sendResponse(remoteCentral, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);

                            Timber.w("SendResponse", "oncharwrite follow-up success: " + success);
                        } catch (NullPointerException e) {
                            // On Nexus 5 possibly an issue in the Broadcom IBluetoothGatt implementation
                            Timber.w("SendResponse", "NPE oncharwrite follow-up");
                        }
                        logEvent("Notifying central we received data");
                        cachedResponsePayloads.put(requestKey, updatedData);
                        lastRequestKey = requestKey;
                    }

                } else {
                    logEvent("CharacteristicWriteRequest. Unrecognized characteristic " + characteristic.getUuid().toString());
                    // Request for unrecognized characteristic. Send GATT_FAILURE
                    try {
                        boolean success = gattServer.sendResponse(remoteCentral, requestId, BluetoothGatt.GATT_FAILURE, 0, null);

                        Timber.w("SendResponse", "write request gatt failure success " + success);
                    } catch (NullPointerException e) {
                        // On Nexus 5 possibly an issue in the Broadcom IBluetoothGatt implementation
                        Timber.w("SendResponse", "NPE on write request gatt failure");
                    }
                }
                super.onCharacteristicWriteRequest(remoteCentral, requestId, characteristic, preparedWrite, responseNeeded, offset, value);
            }

            @Override
            public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
                Timber.i("onDescriptorReadRequest", descriptor.toString());
                super.onDescriptorReadRequest(device, requestId, offset, descriptor);
            }

            @Override
            public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                Timber.i("onDescriptorWriteRequest", descriptor.toString());
                super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value);
            }

            @Override
            public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
                logEvent("onExecuteWrite " + device.toString() + " requestId " + requestId + " Last request key " + lastRequestKey);
                if (cachedResponsePayloads.containsKey(lastRequestKey)) {
                    logEvent("onExecuteWrite called before request finished for " + lastRequestKey.first);
                    // TODO : What is the purpose of this method? I can't call sendRespone without BluetoothGattCharacteristic
                    // and other parameters from onCharacteristicWrite
                }
                super.onExecuteWrite(device, requestId, execute);
            }
        };

        gattServer = manager.openGattServer(context, gattCallback);
        if (gattServer == null) {
            Timber.e("Unable to retrieve BluetoothGattServer");
            return;
        }
        setupGattServer();
    }

    private void setupGattServer() {
        if (gattServer != null) {
            BluetoothGattService chatService = new BluetoothGattService(serviceUUID,
                                                                        BluetoothGattService.SERVICE_TYPE_PRIMARY);

            Collection<BLEPeripheralResponse> responses = this.responses.values();

            for (BLEPeripheralResponse response : responses) {
                chatService.addCharacteristic(response.characteristic);
            }

            gattServer.addService(chatService);
        }
    }

    private AdvertiseData createAdvData() {
        AdvertiseData.Builder builder = new AdvertiseData.Builder();
        builder.addServiceUuid(new ParcelUuid(serviceUUID));
        builder.setIncludeTxPowerLevel(false);
//        builder.setManufacturerData(0x1234578, manufacturerData);
        return builder.build();
    }

    private AdvertiseSettings createAdvSettings() {
        AdvertiseSettings.Builder builder = new AdvertiseSettings.Builder();
        builder.setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH);
        builder.setConnectable(true);
        builder.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED);
        return builder.build();
    }

    private void stopAdvertising() {
        if (advertiser != null) {
            advertiser.stopAdvertising(mAdvCallback);
        }
        isAdvertising = false;
        gattServer.close();
    }

    private void logEvent(String event) {
        Timber.d(event);
    }

    // </editor-fold>
}
