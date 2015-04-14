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

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

import java.net.UnknownServiceException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import pro.dbro.airshare.transport.ConnectionGovernor;
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
    private BiMap<String, BluetoothDevice> connectedDevices = HashBiMap.create();

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
    private BLETransportCallback transportCallback;

    private boolean isAdvertising = false;

    private byte[] lastNotified;

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

    public void setTransportCallback(BLETransportCallback callback) {
        transportCallback = callback;
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

    /**
     * Send data to the central at deviceAddress. If the return value of this function
     * indicates the indicate was successful, another indicate must not be requested until
     * {@link pro.dbro.airshare.transport.ble.BLETransportCallback#dataSentToIdentifier(pro.dbro.airshare.transport.ble.BLETransportCallback.DeviceType, byte[], String, Exception)}
     * is called.
     */
    public boolean indicate(byte[] data,
                            UUID characteristicUuid,
                            String deviceAddress) {

        BluetoothGattCharacteristic targetCharacteristic = null;
        for (BluetoothGattCharacteristic characteristic : characterisitics) {
            if (characteristic.getUuid().equals(characteristicUuid))
                targetCharacteristic = characteristic;
        }

        if (targetCharacteristic == null) {
            Timber.w("No characteristic with uuid %s discovered for device %s", characteristicUuid, deviceAddress);
            return false;
        }

        targetCharacteristic.setValue(data);

        if ((targetCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) !=
                                                    BluetoothGattCharacteristic.PROPERTY_INDICATE)
            throw new IllegalArgumentException(String.format("Requested indicate on Characteristic %s without Notify Property",
                                                             targetCharacteristic.getUuid()));

        BluetoothDevice recipient = connectedDevices.get(deviceAddress);

        if (recipient != null && gattServer != null) {
            boolean success = gattServer.notifyCharacteristicChanged(recipient,
                                                                     targetCharacteristic,
                                                                     true);
            if (success) lastNotified = data;
            Timber.d("Notified %d bytes to %s with success %b", data.length, deviceAddress, success);
            return success;
        }

        Timber.w("Unable to indicate " + deviceAddress);
        return false;
    }

    public boolean isConnectedTo(String deviceAddress) {
        return connectedDevices.containsKey(deviceAddress);
    }

    public BiMap<String, BluetoothDevice> getConnectedDeviceAddresses() {
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
                        if (transportCallback != null)
                            transportCallback.identifierUpdated(BLETransportCallback.DeviceType.PERIPHERAL,
                                                                device.getAddress(),
                                                                Transport.ConnectionStatus.CONNECTED,
                                                                null);

                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // We've disconnected
                    Timber.d("Disconnected from " + device.getAddress());
                    connectedDevices.remove(device.getAddress());
                    if (transportCallback != null)
                        transportCallback.identifierUpdated(BLETransportCallback.DeviceType.PERIPHERAL,
                                                            device.getAddress(),
                                                            Transport.ConnectionStatus.DISCONNECTED,
                                                            null);
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
                Timber.d("onCharacteristicWriteRequest for request %d char %s offset %d length %d responseNeeded %b", requestId, characteristic.getUuid().toString().substring(0,3), offset, value == null ? 0 : value.length, responseNeeded);

                BluetoothGattCharacteristic localCharacteristic = gattServer.getService(serviceUUID).getCharacteristic(characteristic.getUuid());
                if (localCharacteristic != null) {

                    // Must send response before notifying callback (which might trigger data send before remote central received ack)
                    if (responseNeeded) {
                        boolean success = gattServer.sendResponse(remoteCentral, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                        Timber.d("Ack'd write with success " + success);
                    }

                    if (transportCallback != null)
                        transportCallback.dataReceivedFromIdentifier(BLETransportCallback.DeviceType.PERIPHERAL,
                                                                     value,
                                                                     remoteCentral.getAddress());

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
                Timber.d("onDescriptorReadRequest %s", descriptor.toString());
                super.onDescriptorReadRequest(device, requestId, offset, descriptor);
            }

            @Override
            public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                Timber.d("onDescriptorWriteRequest %s", descriptor.toString());
                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) && responseNeeded) {
                    boolean success = gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                    Timber.d("Sent Indication sub response with success %b", success);
                }
                super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value);
            }

            @Override
            public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
                Timber.d("onExecuteWrite " + device.toString() + " requestId " + requestId);
                super.onExecuteWrite(device, requestId, execute);
            }

            @Override
            public void onNotificationSent(BluetoothDevice device, int status) {
                Timber.d("onNotificationSent");
                Exception exception = null;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    String msg = "notify not successful with code " + status;
                    Timber.w(msg);
                    exception = new UnknownServiceException(msg);
                }

                if (transportCallback != null)
                    transportCallback.dataSentToIdentifier(BLETransportCallback.DeviceType.PERIPHERAL,
                                                           lastNotified,
                                                           device.getAddress(),
                                                           exception);
            }
        };

        gattServer = manager.openGattServer(context, gattCallback);
        if (gattServer == null) {
            Timber.e("Unable to retrieve BluetoothGattServer");
            return;
        }
        isAdvertising = true;
        setupGattServer();
    }

    private void setupGattServer() {
        assert(gattServer != null);

        BluetoothGattService service = new BluetoothGattService(serviceUUID,
                                                                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        for (BluetoothGattCharacteristic characteristic : characterisitics) {
            service.addCharacteristic(characteristic);
        }

        gattServer.addService(service);
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
        if (isAdvertising) {
            gattServer.close();
            advertiser.stopAdvertising(mAdvCallback);
            isAdvertising = false;
        }
    }

    // </editor-fold>
}
