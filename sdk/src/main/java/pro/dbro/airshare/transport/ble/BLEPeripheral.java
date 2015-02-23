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

import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualHashBidiMap;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import pro.dbro.airshare.transport.ConnectionGovernor;
import pro.dbro.airshare.transport.ConnectionListener;
import pro.dbro.airshare.transport.Transport;
import timber.log.Timber;

/**
 * A basic BLE Peripheral device discovered by centrals
 *
 * Created by davidbrodsky on 10/11/14.
 */
public class BLEPeripheral {

    private Set<BluetoothGattCharacteristic> characterisitics = new HashSet<>();
    /** Map of connected device addresses to devices */
    private BidiMap<String, BluetoothDevice> connectedDevices = new DualHashBidiMap<>();

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
    private Transport.TransportCallback transportCallback;

    private boolean isAdvertising = false;

    /** Advertise Callback */
    private AdvertiseCallback mAdvCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            if (settingsInEffect != null) {
                Timber.d("Advertise success TxPowerLv="
                        + settingsInEffect.getTxPowerLevel()
                        + " mode=" + settingsInEffect.getMode());
            } else {
                Timber.d("Advertise success" );
            }
        }

        @Override
        public void onStartFailure(int errorCode) {
            Timber.d("Advertising failed with code " + errorCode);
        }
    };

    // <editor-fold desc="Public API">

    public BLEPeripheral(@NonNull Context context, @NonNull UUID serviceUUID) {
        this.context = context;
        this.serviceUUID = serviceUUID;
        init();
    }

    public void setTransportCallback(Transport.TransportCallback callback) {
        transportCallback = callback;
    }

    public void setConnectionListener(ConnectionListener listener) {
        connectionListener = listener;
    }

    public void setGattCallback(BluetoothGattServerCallback callback) {
        gattCallback = callback;
    }

    /**
     * Start the BLE Peripheral advertisement.
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

    public void addCharacteristic(BluetoothGattCharacteristic characteristic) {
        characterisitics.add(characteristic);
    }


    public boolean indicate(BluetoothGattCharacteristic characteristic,
                          String deviceAddress) {

        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) !=
                                              BluetoothGattCharacteristic.PROPERTY_INDICATE)
            throw new IllegalArgumentException(String.format("Requested indicate on Characteristic %s without Notify Property",
                                                             characteristic.getUuid()));

        BluetoothDevice recipient = connectedDevices.get(deviceAddress);

        if (recipient != null && gattServer != null)
            return gattServer.notifyCharacteristicChanged(recipient,
                                                          characteristic,
                                                          true);

        Timber.w("Unable to indicate " + deviceAddress);
        return false;
    }

    public boolean isConnectedTo(String deviceAddress) {
        return connectedDevices.containsKey(deviceAddress);
    }

    public BidiMap<String, BluetoothDevice> getConnectedDeviceAddresses() {
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
            Timber.d("Bluetooth not supported.");
            return;
        }
        BluetoothManager manager = BLEUtil.getManager(context);
        if (manager != null) {
            btAdapter = manager.getAdapter();
        }
        if (btAdapter == null) {
            Timber.d("Bluetooth unavailble.");
            return;
        }

    }

    private void startAdvertising() {
        if ((btAdapter != null) && (!isAdvertising)) {
            if (advertiser == null) {
                advertiser = btAdapter.getBluetoothLeAdvertiser();
            }
            if (advertiser != null) {
                Timber.d("Starting GATT server");
                startGattServer();
                advertiser.startAdvertising(createAdvSettings(), createAdvData(), mAdvCallback);
            } else {
                Timber.d("Unable to access Bluetooth LE Advertiser. Device not supported");
            }
        } else {
            if (isAdvertising)
                Timber.d("Start Advertising called while advertising already in progress");
            else
                Timber.d("Start Advertising WTF error");
        }
    }

    private void startGattServer() {
        BluetoothManager manager = BLEUtil.getManager(context);
        if (gattCallback == null)
            gattCallback = new BluetoothGattServerCallback() {
            @Override
            public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (connectedDevices.containsKey(device.getAddress())) {
                        // We're already connected (should never happen). Cancel connection
                        Timber.d("Denied connection. Already connected to " + device.getAddress());
                        gattServer.cancelConnection(device);
                        return;
                    }

                    if (connectionGovernor != null && !connectionGovernor.shouldConnectToAddress(device.getAddress())) {
                        // The ConnectionGovernor denied the connection. Cancel connection
                        Timber.d("Denied connection. ConnectionGovernor denied " + device.getAddress());
                        gattServer.cancelConnection(device);
                        return;
                    } else {
                        // Allow connection to proceed. Mark device connected
                        Timber.d("Accepted connection to " + device.getAddress());
                        connectedDevices.put(device.getAddress(), device);

                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // We've disconnected
                    Timber.d("Disconnected from " + device.getAddress());
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
            public void onCharacteristicWriteRequest(BluetoothDevice remoteCentral, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                Timber.d(String.format("onCharacteristicWriteRequest for request %d on characteristic %s with offset %d", requestId, characteristic.getUuid().toString().substring(0,3), offset));

                BluetoothGattCharacteristic localCharacteristic = gattServer.getService(serviceUUID).getCharacteristic(characteristic.getUuid());
                if (localCharacteristic != null) {

                    if (transportCallback != null)
                        transportCallback.dataReceivedFromIdentifier(null, value, remoteCentral.getAddress());

                    if (responseNeeded) {
                        boolean success = gattServer.sendResponse(remoteCentral, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                        Timber.d("Ack'd write with success " + success);
                    }

                } else {
                    Timber.d("CharacteristicWriteRequest. Unrecognized characteristic " + characteristic.getUuid().toString());
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
                if (descriptor.getValue() == BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) {
                    // TODO Mark this characterisitic as our notify
                }
                super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value);
            }

            @Override
            public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
                Timber.d("onExecuteWrite " + device.toString() + " requestId " + requestId);
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
        assert(gattServer != null);

        BluetoothGattService chatService = new BluetoothGattService(serviceUUID,
                                                                    BluetoothGattService.SERVICE_TYPE_PRIMARY);

        for (BluetoothGattCharacteristic characteristic : characterisitics) {
            chatService.addCharacteristic(characteristic);
        }

        gattServer.addService(chatService);
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

    // </editor-fold>
}
