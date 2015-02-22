package pro.dbro.airshare.transport.ble;

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
import android.os.ParcelUuid;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import pro.dbro.airshare.DataUtil;
import pro.dbro.airshare.R;
import pro.dbro.airshare.transport.ConnectionGovernor;
import pro.dbro.airshare.transport.ConnectionListener;
import timber.log.Timber;

/**
 * A basic BLE Central device that discovers peripherals
 * <p/>
 * Created by davidbrodsky on 10/2/14.
 */
public class BLECentral {
    public static final String TAG = "BLECentral";

    /**
     * Requests to perform against each discovered peripheral
     */
    private ArrayDeque<BLECentralRequest> defaultRequests = new ArrayDeque<>();
    /**
     * Map of request queues by remote peripheral address
     */
    private HashMap<String, ArrayDeque<BLECentralRequest>> requestsForDevice = new HashMap<>();
    /**
     * Set of connected device addresses
     */
    private Set<String> connectedDevices = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /**
     * Set of 'connecting' device addresses. Intended to prevent multiple simultaneous connection requests
     */
    private Set<String> connectingDevices = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /**
     * Map of Characteristic UUID to BLECentralRequests registered via {@link #addRequest(BLECentralRequest)}
     * This is required because I'm currently unable to execute a GATT operation on the remote peripheral without using the
     * characteristic instance returned to onServicesDiscovered. Therefore I have to swap out the characteristic
     * in each request with the instance received onServicesDiscovered. This map helps this process.
     *
     * TODO Figure out how to execute gatt requests without discovering services
     */

    private HashMap<Pair<UUID, BLECentralRequest.RequestType>, BLECentralRequest> characteristicUUIDToRequest = new HashMap<>();

    private Context context;
    private UUID serviceUUID;
    private BluetoothAdapter btAdapter;
    private ScanCallback scanCallback;
    private BluetoothLeScanner scanner;
    private ConnectionGovernor connectionGovernor;
    private ConnectionListener connectionListener;

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

    public void start() {
        startScanning();
    }

    public void stop() {
        stopScanning();
    }

    public boolean isIsScanning() {
        return isScanning;
    }

    public Set<String> getConnectedDeviceAddresses() {
        return connectedDevices;
    }

    /**
     * Add a {@link pro.dbro.airshare.transport.ble.BLECentralRequest} to be performed
     * on each peripheral discovered. Requests are performed sequentially in the order they are added.
     */
    public void addRequest(BLECentralRequest request) {
        defaultRequests.add(request);
        characteristicUUIDToRequest.put(new Pair<>(request.characteristic.getUuid(), request.requestType), request);
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
                if (connectedDevices.contains(scanResult.getDevice().getAddress())) {
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
                        } else {
//                            if (REPORT_NON_SUCCESSES) mLogger.onLogEvent("status indicates GATT Connection not yet successful");
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
                                connectedDevices.add(gatt.getDevice().getAddress());
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
                                    logEvent("Discovered Chat service");
                                    foundService = true;
                                    List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
                                    for (BluetoothGattCharacteristic characteristic : characteristics) {
                                        // TODO Refactor to make this more flexible. I don't want to have to modify this logic whenever a new request type is added e.g: notify
                                        Pair<UUID, BLECentralRequest.RequestType> readKey = new Pair<>(characteristic.getUuid(), BLECentralRequest.RequestType.READ);
                                        Pair<UUID, BLECentralRequest.RequestType> writeKey = new Pair<>(characteristic.getUuid(), BLECentralRequest.RequestType.WRITE);

                                        if (characteristicUUIDToRequest.containsKey(readKey)) {
                                            // If a request is registered for this uuid, replace the BluetoothGattCharacteristic with that
                                            // discovered on the device
                                            characteristicUUIDToRequest.get(readKey).characteristic = characteristic;
                                        }
                                        if (characteristicUUIDToRequest.containsKey(writeKey)) {
                                            characteristicUUIDToRequest.get(writeKey).characteristic = characteristic;
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logEvent("Exception analyzing discovered services " + e.getLocalizedMessage());
                            e.printStackTrace();
                        }
                        if (!foundService)
                            logEvent("Could not discover chat service!");
                        else
                            beginRequestFlowWithPeripheral(gatt);
                        super.onServicesDiscovered(gatt, status);
                    }

                    @Override
                    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                        if (status != BluetoothGatt.GATT_SUCCESS) logEvent(String.format("OnCharacteristicRead Got non-successful response!"));
                        logEvent(String.format("onCharacteristicRead %s value: %s status: %d", characteristic.getUuid(), characteristic.getValue() == null ? "null" : DataUtil.bytesToHex(characteristic.getValue()), status));
                        handleResponseForCurrentRequestToPeripheral(gatt, BLECentralRequest.RequestType.READ, characteristic, status);
                        super.onCharacteristicRead(gatt, characteristic, status);
                    }

                    @Override
                    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                        logEvent(String.format("onCharacteristicWrite %s with status %d", characteristic.getUuid(), status));
                        handleResponseForCurrentRequestToPeripheral(gatt, BLECentralRequest.RequestType.WRITE, characteristic, status);
                        super.onCharacteristicWrite(gatt, characteristic, status);
                    }

                    @Override
                    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                        String toLog = null;
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

    /**
     * Add a fresh queue of requests to {@link #requestsForDevice} for the given peripheral.
     * see {@link #defaultRequests}
     */
    private void beginRequestFlowWithPeripheral(BluetoothGatt remotePeripheral) {
        ArrayDeque<BLECentralRequest> requestsForPeripheral = defaultRequests.clone();
        String remotePeripheralAddress = remotePeripheral.getDevice().getAddress();
        requestsForDevice.put(remotePeripheralAddress, requestsForPeripheral);

        logEvent(String.format("Added %d requests for device %s", requestsForPeripheral.size(), remotePeripheralAddress));
        performCurrentRequestToPeripheral(remotePeripheral);
    }

    /**
     * Perform the next request in the queue provided by {@link #requestsForDevice} for this peripheral.
     */
    private void performCurrentRequestToPeripheral(BluetoothGatt remotePeripheral) {
        String remotePeripheralAddress = remotePeripheral.getDevice().getAddress();
        ArrayDeque<BLECentralRequest> requestsForPeripheral = requestsForDevice.get(remotePeripheralAddress);

        if (requestsForPeripheral != null && requestsForPeripheral.size() > 0) {
            boolean performedRequest = requestsForPeripheral.peek().doRequest(remotePeripheral);
            if (!performedRequest) {
                logEvent(String.format("Request not executed for %s", remotePeripheralAddress));
                // If the request could not be made for this peer, remove it from the device queue
                // and try the next request
                requestsForPeripheral.poll();
                performCurrentRequestToPeripheral(remotePeripheral);
            }
        } else {
            logEvent(String.format("performCurrentRequestToPeripheral found no requests available for device %s", remotePeripheralAddress));
        }
    }

    private void handleResponseForCurrentRequestToPeripheral(@NonNull BluetoothGatt remotePeripheral, @NonNull BLECentralRequest.RequestType type, @NonNull BluetoothGattCharacteristic characteristic, int status) {
        String remotePeripheralAddress = remotePeripheral.getDevice().getAddress();
        ArrayDeque<BLECentralRequest> requestsForPeripheral = requestsForDevice.get(remotePeripheralAddress);

        if (requestsForPeripheral != null && requestsForPeripheral.size() > 0) {
            // Check that this response is for the expected characteristic, and is expected type (READ, WRITE etc)
            if (!requestsForPeripheral.peek().characteristic.getUuid().equals(characteristic.getUuid()) ||
                    requestsForPeripheral.peek().requestType != type) {
                logEvent(String.format("handleResponseForCurrentRequestToPeripheral expected characteristic %s but got %s !",
                        requestsForPeripheral.peek().characteristic.getUuid().toString(),
                        characteristic.getUuid()));
                logEvent("Request chain stopping");
                return;
            }

            // Handle response
            if (characteristic.getValue() == null || characteristic.getValue().length == 0) {
                logEvent(String.format("Got no data for %s to %s", type.toString(), characteristic.getUuid().toString().substring(0,3)));
            }
            boolean complete = requestsForPeripheral.peek().handleResponse(remotePeripheral, characteristic, status);
            if (complete) {
                // Request is complete
                requestsForPeripheral.poll();
                logEvent(String.format("Completed %s to %s", type.toString(), characteristic.getUuid().toString().substring(0,3)));
            } else {
                logEvent(String.format("Repeating %s to %s", type.toString(), characteristic.getUuid().toString().substring(0,3)));
            }
            // Perform next request
            performCurrentRequestToPeripheral(remotePeripheral);
        } else {
            logEvent(String.format("handleCurrentResponseForPeripheral found no requests available for device %s", remotePeripheralAddress));
        }
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
