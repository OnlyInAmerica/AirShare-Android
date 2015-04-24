package pro.dbro.airshare.transport.ble;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.widget.Toast;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

import java.net.UnknownServiceException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import pro.dbro.airshare.DataUtil;
import pro.dbro.airshare.R;
import pro.dbro.airshare.transport.ConnectionGovernor;
import pro.dbro.airshare.transport.Transport;
import timber.log.Timber;

/**
 * A basic BLE Central device that discovers peripherals.
 *
 * Upon connection to a Peripheral this device performs a few initialization steps in order:
 * 1. Requests an MTU
 * 2. (On response to the MTU request) discovers services
 * 3. (On response to service discovery) reports connection
 *
 * Created by davidbrodsky on 10/2/14.
 */
// TEMPORARY - Should add 18 APIs for use on older platforms
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class BLECentral {
    public static final String TAG = "BLECentral";

    public static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Set<UUID> notifyUUIDs = new HashSet<>();

    /** Peripheral MAC Address -> Set of characteristics */
    private final HashMap<String, HashSet<BluetoothGattCharacteristic>> discoveredCharacteristics = new HashMap<>();

    /** Peripheral MAC Address -> Peripheral */
    private final BiMap<String, BluetoothGatt> connectedDevices = HashBiMap.create();

    /**
     * Peripheral MAC Address -> Peripheral
     * Intended to prevent multiple simultaneous connection requests
     */
    private final Set<String> connectingDevices = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** Peripheral MAC Address -> Maximum Transmission Unit */
    private HashMap<String, Integer> mtus = new HashMap<>();

    private Context context;
    private UUID serviceUUID;
    private BluetoothAdapter btAdapter;
    private ScanCallback scanCallback;
    private BluetoothLeScanner scanner;
    private ConnectionGovernor connectionGovernor;
    private BLETransportCallback transportCallback;

    private boolean isScanning = false;

    // <editor-fold desc="Public API">

    public BLECentral(@NonNull Context context,
                      @NonNull UUID serviceUUID) {
        this.serviceUUID = serviceUUID;
        this.context = context;
        init();
    }

    public void setConnectionGovernor(ConnectionGovernor governor) {
        connectionGovernor = governor;
    }

    public void setTransportCallback(BLETransportCallback callback) {
        this.transportCallback = callback;
    }

    public void requestNotifyOnCharacteristic(BluetoothGattCharacteristic characteristic) {
        notifyUUIDs.add(characteristic.getUuid());
    }

    public void start() {
        startScanning();
    }

    public void stop() {
        stopScanning();
        synchronized (connectedDevices) {
            for (BluetoothGatt peripheral : connectedDevices.values()) {
                peripheral.disconnect();
            }
        }
    }

    public boolean isScanning() {
        return isScanning;
    }

    public boolean isConnectedTo(String deviceAddress) {
        return connectedDevices.containsKey(deviceAddress);
    }

    public @Nullable Integer getMtuForIdentifier(String identifier) {
        return mtus.get(identifier);
    }

    public boolean write(byte[] data,
                         UUID characteristicUuid,
                         String deviceAddress) {

        BluetoothGattCharacteristic discoveredCharacteristic = null;

        for (BluetoothGattCharacteristic characteristic : discoveredCharacteristics.get(deviceAddress)) {
            if (characteristic.getUuid().equals(characteristicUuid))
                discoveredCharacteristic = characteristic;
        }

        if (discoveredCharacteristic == null) {
            Timber.w("No characteristic with uuid %s discovered for device %s", characteristicUuid, deviceAddress);
            return false;
        }

        discoveredCharacteristic.setValue(data);

        if ((discoveredCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) !=
                                                        BluetoothGattCharacteristic.PROPERTY_WRITE)
            throw new IllegalArgumentException(String.format("Requested write on Characteristic %s without Notify Property",
                    characteristicUuid.toString()));

        BluetoothGatt recipient = connectedDevices.get(deviceAddress);
        if (recipient != null) {
            boolean success = recipient.writeCharacteristic(discoveredCharacteristic);
            // write type should be 2 (Default)
            Timber.d("Wrote %d bytes with type %d to %s with success %b", data.length, discoveredCharacteristic.getWriteType(), deviceAddress, success);
            return success;
        }
        Timber.w("Unable to write " + deviceAddress);
        return false;
    }

    public BiMap<String, BluetoothGatt> getConnectedDeviceAddresses() {
        return connectedDevices;
    }

    // </editor-fold>

    //<editor-fold desc="Private API">

    private void init() {
        // BLE check
        if (!BLEUtil.isBLESupported(context)) {
            Toast.makeText(context, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            return;
        }

        // BT check
        BluetoothManager manager = BLEUtil.getManager(context);
        if (manager != null) {
            btAdapter = manager.getAdapter();
        }
        if (btAdapter == null) {
            Toast.makeText(context, R.string.bt_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }

    }

    public void setScanCallback(ScanCallback callback) {
        if (callback != null) {
            scanCallback = callback;
            return;
        }
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult scanResult) {
                if (connectedDevices.containsKey(scanResult.getDevice().getAddress())) {
                    // If we're already connected, forget it
                    //Timber.d("Denied connection. Already connected to  " + scanResult.getDevice().getAddress());
                    return;
                }

                if (connectingDevices.contains(scanResult.getDevice().getAddress())) {
                    // If we're already connected, forget it
                    //Timber.d("Denied connection. Already connecting to  " + scanResult.getDevice().getAddress());
                    return;
                }

                if (connectionGovernor != null && !connectionGovernor.shouldConnectToAddress(scanResult.getDevice().getAddress())) {
                    // If the BLEConnectionGovernor says we should not bother connecting to this peer, don't
                    //Timber.d("Denied connection. ConnectionGovernor denied  " + scanResult.getDevice().getAddress());
                    return;
                }
                connectingDevices.add(scanResult.getDevice().getAddress());
                Timber.d("Initiating connection to " + scanResult.getDevice().getAddress());
                scanResult.getDevice().connectGatt(context, false, new BluetoothGattCallback() {
                    @Override
                    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

                        synchronized (connectedDevices) {

                            // It appears that certain events (like disconnection) won't have a GATT_SUCCESS status
                            // even when they proceed as expected, at least with the Motorola bluetooth stack
                            if (status != BluetoothGatt.GATT_SUCCESS)
                                Timber.w("onConnectionStateChange with newState %d and non-success status %s", newState, gatt.getDevice().getAddress());

                            Set<BluetoothGattCharacteristic> characteristicSet;

                            switch (newState) {
                                case BluetoothProfile.STATE_DISCONNECTING:
                                    Timber.d("Disconnecting from " + gatt.getDevice().getAddress());

                                    characteristicSet = discoveredCharacteristics.get(gatt.getDevice().getAddress());
                                    for (BluetoothGattCharacteristic characteristic : characteristicSet) {
                                        if (notifyUUIDs.contains(characteristic.getUuid())) {
                                            Timber.d("Attempting to unsubscribe on disconneting");
                                            setIndictaionSubscription(gatt, characteristic, false);
                                        }
                                    }
                                    discoveredCharacteristics.remove(gatt.getDevice().getAddress());

                                    break;

                                case BluetoothProfile.STATE_DISCONNECTED:
                                    Timber.d("Disconnected from " + gatt.getDevice().getAddress());
                                    connectedDevices.remove(gatt.getDevice().getAddress());
                                    connectingDevices.remove(gatt.getDevice().getAddress());
                                    if (transportCallback != null)
                                        transportCallback.identifierUpdated(BLETransportCallback.DeviceType.CENTRAL,
                                                gatt.getDevice().getAddress(),
                                                Transport.ConnectionStatus.DISCONNECTED,
                                                null);

                                    characteristicSet = discoveredCharacteristics.get(gatt.getDevice().getAddress());
                                    if (characteristicSet != null) { // Have we handled unsubscription on DISCONNECTING?
                                        for (BluetoothGattCharacteristic characteristic : characteristicSet) {
                                            if (notifyUUIDs.contains(characteristic.getUuid())) {
                                                Timber.d("Attempting to unsubscribe before disconnet");
                                                setIndictaionSubscription(gatt, characteristic, false);
                                            }
                                        }
                                        // Gatt will be closed on result of descriptor write
                                    } else
                                        gatt.close();

                                    discoveredCharacteristics.remove(gatt.getDevice().getAddress());

                                    break;

                                case BluetoothProfile.STATE_CONNECTED:
                                    // Though we're connected, we shouldn't actually report
                                    // connection until we've discovered all service characteristics

                                    boolean mtuSuccess = gatt.requestMtu(BLETransport.DEFAULT_MTU_BYTES);

                                    Timber.d("Connected to %s. Requested MTU success %b", gatt.getDevice().getAddress(),
                                            mtuSuccess);
                                    break;
                            }

                            super.onConnectionStateChange(gatt, status, newState);
                        }
                    }

                    @Override
                    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                        Timber.d("Got MTU (%d bytes) for device %s. Was changed successfully: %b",
                                 mtu,
                                 gatt.getDevice().getAddress(),
                                 status == BluetoothGatt.GATT_SUCCESS);

                        mtus.put(gatt.getDevice().getAddress(), mtu);

                        // TODO: Can we craft characteristics and avoid discovery step?
                        boolean discovering = gatt.discoverServices();
                        Timber.d("Discovering services : " + discovering);
                    }

                    @Override
                    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                        if (status == BluetoothGatt.GATT_SUCCESS)
                            Timber.d("Discovered services");
                        else
                            Timber.d("Discovered services appears unsuccessful with code " + status);
                        // TODO: Keep this here to examine characteristics
                        // eventually we should get rid of the discoverServices step
                        boolean foundService = false;
                        try {
                            List<BluetoothGattService> serviceList = gatt.getServices();
                            for (BluetoothGattService service : serviceList) {
                                if (service.getUuid().equals(serviceUUID)) {
                                    Timber.d("Discovered Service");
                                    foundService = true;
                                    HashSet<BluetoothGattCharacteristic> characteristicSet = new HashSet<>();
                                    characteristicSet.addAll(service.getCharacteristics());
                                    discoveredCharacteristics.put(gatt.getDevice().getAddress(), characteristicSet);

                                    for (BluetoothGattCharacteristic characteristic : characteristicSet) {
                                        if (notifyUUIDs.contains(characteristic.getUuid())) {
                                            setIndictaionSubscription(gatt, characteristic, true);
                                        }
                                    }
                                }
                            }

                            if (foundService) {
                                synchronized (connectedDevices) {
                                    connectedDevices.put(gatt.getDevice().getAddress(), gatt);
                                }
                                connectingDevices.remove(gatt.getDevice().getAddress());
                            }
                        } catch (Exception e) {
                            Timber.d("Exception analyzing discovered services " + e.getLocalizedMessage());
                            e.printStackTrace();
                        }
                        if (!foundService)
                            Timber.d("Could not discover chat service!");
                        super.onServicesDiscovered(gatt, status);
                    }

                    /**
                     * Subscribe or Unsubscribe to/from indication of a peripheral's characteristic.
                     *
                     * After calling this method you must await the result via
                     * {@link #onDescriptorWrite(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattDescriptor, int)}
                     * before performing any other peripheral actions.
                     */
                    private void setIndictaionSubscription(BluetoothGatt peripheral,
                                                           BluetoothGattCharacteristic characteristic,
                                                           boolean enable) {

                        boolean success = peripheral.setCharacteristicNotification(characteristic, enable);
                        Timber.d("Request notification %s %s with sucess %b", enable ? "set" : "unset", characteristic.getUuid().toString(), success);
                        BluetoothGattDescriptor desc = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
                        desc.setValue(enable ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                        boolean desSuccess = peripheral.writeDescriptor(desc);
                        Timber.d("Wrote descriptor with success %b", desSuccess);
                    }

                    @Override
                    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                                                  int status) {

                        Timber.d("onDescriptorWrite");
                        if (status == BluetoothGatt.GATT_SUCCESS && transportCallback != null) {

                            if (Arrays.equals(descriptor.getValue(), BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                                transportCallback.identifierUpdated(BLETransportCallback.DeviceType.CENTRAL,
                                        gatt.getDevice().getAddress(),
                                        Transport.ConnectionStatus.CONNECTED,
                                        null);

                            } else if (Arrays.equals(descriptor.getValue(), BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                                Timber.d("disabled indications successfully. Closing gatt");
                                gatt.close();
                            }
                        }
                    }

                    @Override
                    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                        Timber.d("onCharacteristicChanged %s with %d bytes", characteristic.getUuid().toString().substring(0,5),
                                                                             characteristic.getValue().length);

                        if (transportCallback != null)
                            transportCallback.dataReceivedFromIdentifier(BLETransportCallback.DeviceType.CENTRAL,
                                                                         characteristic.getValue(),
                                                                         gatt.getDevice().getAddress());

                        super.onCharacteristicChanged(gatt, characteristic);
                    }

                    @Override
                    public void onCharacteristicWrite(BluetoothGatt gatt,
                                                      BluetoothGattCharacteristic characteristic, int status) {

                        Timber.d("onCharacteristicWrite with %d bytes", characteristic.getValue().length);
                        Exception exception = null;
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            String msg = "Write was not successful with code " + status;
                            Timber.w(msg);
                            exception = new UnknownServiceException(msg);
                        }

                        if (transportCallback != null)
                            transportCallback.dataSentToIdentifier(BLETransportCallback.DeviceType.CENTRAL,
                                                                   characteristic.getValue(),
                                                                   gatt.getDevice().getAddress(),
                                                                   exception);
                    }

                    @Override
                    public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
                        Timber.d(String.format("%s rssi: %d", gatt.getDevice().getAddress(), rssi));
                        super.onReadRemoteRssi(gatt, rssi, status);
                    }

                });
            }

            @Override
            public void onScanFailed(int i) {
                Timber.d("Scan failed with code " + i);
            }
        };
    }

    private void startScanning() {
        if ((btAdapter != null) && (!isScanning)) {
            if (scanner == null) {
                scanner = btAdapter.getBluetoothLeScanner();
            }
            if (scanCallback == null) setScanCallback(null);

            scanner.startScan(createScanFilters(), createScanSettings(), scanCallback);
            isScanning = true;
            //Toast.makeText(context, context.getString(R.string.scan_started), Toast.LENGTH_SHORT).show();
        }
    }

    private List<ScanFilter> createScanFilters() {
        ScanFilter.Builder builder = new ScanFilter.Builder();
        builder.setServiceUuid(new ParcelUuid(serviceUUID));
        ArrayList<ScanFilter> scanFilters = new ArrayList<>();
        scanFilters.add(builder.build());
        return scanFilters;
    }

    private ScanSettings createScanSettings() {
        ScanSettings.Builder builder = new ScanSettings.Builder();
        builder.setScanMode(ScanSettings.SCAN_MODE_BALANCED);
        return builder.build();
    }

    private void stopScanning() {
        if (isScanning) {
            scanner.stopScan(scanCallback);
            scanner = null;
            isScanning = false;
        }
    }

    private void logCharacteristic(BluetoothGattCharacteristic characteristic) {
        StringBuilder builder = new StringBuilder();
        builder.append(characteristic.getUuid().toString().substring(0, 3));
        builder.append("... instance: ");
        builder.append(characteristic.getInstanceId());
        builder.append(" properties: ");
        builder.append(characteristic.getProperties());
        builder.append(" permissions: ");
        builder.append(characteristic.getPermissions());
        builder.append(" value: ");
        if (characteristic.getValue() != null)
            builder.append(DataUtil.bytesToHex(characteristic.getValue()));
        else
            builder.append("null");

        if (characteristic.getDescriptors().size() > 0) builder.append("descriptors: [\n");
        for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {
            builder.append("{\n");
            builder.append(descriptor.getUuid().toString());
            builder.append(" permissions: ");
            builder.append(descriptor.getPermissions());
            builder.append("\n value: ");
            if (descriptor.getValue() != null)
                builder.append(DataUtil.bytesToHex(descriptor.getValue()));
            else
                builder.append("null");
            builder.append("\n}");
        }
        if (characteristic.getDescriptors().size() > 0) builder.append("]");
        Timber.d(builder.toString());
    }

    //</editor-fold>
}
