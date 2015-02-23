package pro.dbro.airshare.transport.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
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
import android.os.ParcelUuid;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualHashBidiMap;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
import pro.dbro.airshare.transport.ConnectionListener;
import pro.dbro.airshare.transport.Transport;
import timber.log.Timber;

/**
 * A basic BLE Central device that discovers peripherals
 * <p/>
 * Created by davidbrodsky on 10/2/14.
 */
public class BLECentral {
    public static final String TAG = "BLECentral";

    /**
     * Set of connected device addresses
     */
    private BidiMap<String, BluetoothGatt> connectedDevices = new DualHashBidiMap<>();

    private HashMap<String, HashSet<BluetoothGattCharacteristic>> discoveredCharacteristics = new HashMap<>();
    /**
     * Set of 'connecting' device addresses. Intended to prevent multiple simultaneous connection requests
     */
    private Set<String> connectingDevices = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private Context context;
    private UUID serviceUUID;
    private BluetoothAdapter btAdapter;
    private ScanCallback scanCallback;
    private BluetoothLeScanner scanner;
    private ConnectionGovernor connectionGovernor;
    private ConnectionListener connectionListener;
    private Transport.TransportCallback transportCallback;

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

    public void setConnectionListener(ConnectionListener listener) {
        connectionListener = listener;
    }

    public void setTransportCallback(Transport.TransportCallback callback) {
        this.transportCallback = callback;
    }

    public void start() {
        startScanning();
    }

    public void stop() {
        stopScanning();
    }

    public boolean isScanning() {
        return isScanning;
    }

    public boolean isConnectedTo(String deviceAddress) {
        return connectedDevices.containsKey(deviceAddress);
    }

    public boolean write(BluetoothGattCharacteristic characteristic,
                      String deviceAddress) {
        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) !=
                                              BluetoothGattCharacteristic.PROPERTY_WRITE)
            throw new IllegalArgumentException(String.format("Requested write on Characteristic %s without Notify Property",
                    characteristic.getUuid()));

        BluetoothGatt recipient = connectedDevices.get(deviceAddress);
        if (recipient != null) {
            return recipient.writeCharacteristic(characteristic);
        }
        Timber.w("Unable to write " + deviceAddress);
        return false;
    }

    public BidiMap<String, BluetoothGatt> getConnectedDeviceAddresses() {
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
                    //logEvent("Denied connection. Already connected to  " + scanResult.getDevice().getAddress());
                    return;
                }

                if (connectingDevices.contains(scanResult.getDevice().getAddress())) {
                    // If we're already connected, forget it
                    //logEvent("Denied connection. Already connecting to  " + scanResult.getDevice().getAddress());
                    return;
                }

                if (connectionGovernor != null && !connectionGovernor.shouldConnectToAddress(scanResult.getDevice().getAddress())) {
                    // If the BLEConnectionGovernor says we should not bother connecting to this peer, don't
                    //logEvent("Denied connection. ConnectionGovernor denied  " + scanResult.getDevice().getAddress());
                    return;
                }
                connectingDevices.add(scanResult.getDevice().getAddress());
                logEvent("Initiating connection to " + scanResult.getDevice().getAddress());
                scanResult.getDevice().connectGatt(context, false, new BluetoothGattCallback() {
                    @Override
                    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            logEvent("status indicates GATT Connection Success!");
                        }

                        switch (newState) {
                            case BluetoothProfile.STATE_DISCONNECTED:
                                logEvent("Disconnected from " + gatt.getDevice().getAddress());
                                connectedDevices.remove(gatt.getDevice().getAddress());
                                connectingDevices.remove(gatt.getDevice().getAddress());
                                if (connectionListener != null)
                                    connectionListener.disconnectedFrom(gatt.getDevice().getAddress());
                                gatt.close();
                                break;
                            case BluetoothProfile.STATE_CONNECTED:
                                logEvent("Connected to " + gatt.getDevice().getAddress());
                                connectedDevices.put(gatt.getDevice().getAddress(), gatt);
                                connectingDevices.remove(gatt.getDevice().getAddress());
                                if (connectionListener != null)
                                    connectionListener.connectedTo(gatt.getDevice().getAddress());
                                // TODO: Stop discovering services once we can
                                // TOOD: reliably craft characteristics
                                boolean discovering = gatt.discoverServices();
                                logEvent("Discovering services : " + discovering);
                                //beginRequestFlowWithPeripheral(gatt);
                                break;
                        }
                        super.onConnectionStateChange(gatt, status, newState);
                    }

                    @Override
                    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                        if (status == BluetoothGatt.GATT_SUCCESS)
                            logEvent("Discovered services");
                        else
                            logEvent("Discovered services appears unsuccessful with code " + status);
                        // TODO: Keep this here to examine characteristics
                        // eventually we should get rid of the discoverServices step
                        boolean foundService = false;
                        try {
                            List<BluetoothGattService> serviceList = gatt.getServices();
                            for (BluetoothGattService service : serviceList) {
                                if (service.getUuid().equals(serviceUUID)) {
                                    logEvent("Discovered Service");
                                    foundService = true;
                                    HashSet<BluetoothGattCharacteristic> characteristicSet = new HashSet<>();
                                    characteristicSet.addAll(service.getCharacteristics());
                                    discoveredCharacteristics.put(gatt.getDevice().getAddress(), characteristicSet);
                                }
                            }
                        } catch (Exception e) {
                            logEvent("Exception analyzing discovered services " + e.getLocalizedMessage());
                            e.printStackTrace();
                        }
                        if (!foundService)
                            logEvent("Could not discover chat service!");
                        super.onServicesDiscovered(gatt, status);
                    }

                    @Override
                    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                        String toLog = null;

                        if (transportCallback != null)
                            transportCallback.dataReceivedFromIdentifier(null,
                                                                         characteristic.getValue(),
                                                                         gatt.getDevice().getAddress());

                        try {
                            toLog = "onCharacteristicChanged value: " + characteristic.getStringValue(0);
                        } catch (Exception e) {
                            // whoops
                            toLog = "onCharacteristicChanged uuid: " + characteristic.getUuid().toString();
                        }
                        logEvent(toLog);
//                        Log.i(TAG, "onCharacteristicChanged");
                        super.onCharacteristicChanged(gatt, characteristic);
                    }

                    @Override
                    public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
                        Log.i(TAG, "onReadRemoteRssi");
                        super.onReadRemoteRssi(gatt, rssi, status);
                    }

                });
            }

            @Override
            public void onScanFailed(int i) {
                String toLog = "Scan failed with code " + i;
                logEvent(toLog);
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
        if (scanner != null) {
            scanner.stopScan(scanCallback);
        }
        isScanning = false;
    }

    private void logEvent(String event) {
        Timber.d(event);
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
        logEvent(builder.toString());
    }

    //</editor-fold>
}
