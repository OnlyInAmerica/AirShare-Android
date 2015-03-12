package pro.dbro.airshare.transport.ble;

import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Pair;

import org.apache.commons.codec.digest.DigestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import pro.dbro.airshare.transport.Transport;
import timber.log.Timber;

/**
 * Bluetooth Low Energy Transport. Requires Android 5.0.
 *
 * Note that only the Central device reports device connection events to {@link #callback}
 * in this implementation.
 * See {@link #identifierUpdated(pro.dbro.airshare.transport.ble.BLETransportCallback.DeviceType, String, pro.dbro.airshare.transport.Transport.ConnectionStatus, java.util.Map)}
 *
 * Created by davidbrodsky on 2/21/15.
 */

/**
 * Every Identifier gets a ByteBuffer that all outgoing data gets copied to, and
 * is read from in MTU_BYTES increments for the actual sendData call.
 *
 * *** THOUGHTS ***
 *
 * Need to have buffering at SessionManager to throttle data sent from Session
 * to Transport to match data being sent from Transport
 *
 * Error recovery
 *
 * When an error happens on a Transport write we need to inform the SessionManager for resume
 *
 * What happens when the two devices fall out of sync. What happens when partial data is transferred.
 * How to re-establish Session at new offset
 */
public class BLETransport extends Transport implements BLETransportCallback {

    public static final int MTU_BYTES = 155;

    private final UUID serviceUUID;
    private final UUID dataUUID    = UUID.fromString("72A7700C-859D-4317-9E35-D7F5A93005B1");

    private HashMap<String, ArrayDeque<ByteBuffer>> outBuffers = new HashMap<>();
    private ArrayDeque<ByteBuffer> availableBuffers = new ArrayDeque<>();

    private final BluetoothGattCharacteristic dataCharacteristic
            = new BluetoothGattCharacteristic(dataUUID,
                                              BluetoothGattCharacteristic.PROPERTY_READ |
                                              BluetoothGattCharacteristic.PROPERTY_WRITE |
                                              BluetoothGattCharacteristic.PROPERTY_INDICATE,

                                              BluetoothGattCharacteristic.PERMISSION_READ |
                                              BluetoothGattCharacteristic.PERMISSION_WRITE);

    private BLECentral    central;
    private BLEPeripheral peripheral;

    public BLETransport(@NonNull Context context,
                        @NonNull String serviceName,
                        @NonNull Transport.TransportCallback callback) {

        super(serviceName, callback);

        serviceUUID = generateUUIDFromString(serviceName);

        central = new BLECentral(context, serviceUUID);
        central.setTransportCallback(this);

        peripheral = new BLEPeripheral(context, serviceUUID);
        peripheral.setTransportCallback(this);
        peripheral.addCharacteristic(dataCharacteristic);
    }

    private UUID generateUUIDFromString(String input) {
        String hexString = DigestUtils.sha1Hex(input);
        StringBuilder uuid = new StringBuilder();
        // UUID has 32 hex 'digits'. SHA1 Hash has 40
        uuid.insert(9, hexString);

        uuid.insert(8, '-');
        uuid.insert(13,'-');
        uuid.insert(18,'-');
        uuid.insert(23,'-');
        return UUID.fromString(uuid.toString());
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
    public boolean sendData(byte[] data, String identifier) {

        queueOutgoingData(data, identifier);

        if (isConnectedTo(identifier))
            return transmitOutgoingDataForConnectedPeer(identifier);

        return false;
    }

    @Override
    public void advertise() {
        if (!peripheral.isAdvertising()) peripheral.start();
    }

    @Override
    public void scanForPeers() {
        if (!central.isScanning()) central.start();
    }

    @Override
    public void stop() {
        if (peripheral.isAdvertising()) peripheral.stop();
        if (central.isScanning())       central.stop();
    }

    @Override
    public int getMtuBytes() {
        return MTU_BYTES;
    }

    // </editor-fold desc="Transport">

    // <editor-fold desc="BLETransportCallback">

    @Override
    public void dataReceivedFromIdentifier(DeviceType deviceType, byte[] data, String identifier) {
        if (callback.get() != null)
            callback.get().dataReceivedFromIdentifier(this, data, identifier);
    }

    @Override
    public void dataSentToIdentifier(DeviceType deviceType, byte[] data, String identifier) {
        if (callback.get() != null)
            callback.get().dataSentToIdentifier(this, data, identifier);
    }

    @Override
    public void identifierUpdated(DeviceType deviceType,
                                  String identifier,
                                  ConnectionStatus status,
                                  Map<String, Object> extraInfo) {

        // Only the Central device should initiate device discovery
        if (deviceType == DeviceType.CENTRAL && callback.get() != null)
            callback.get().identifierUpdated(this, identifier, status, extraInfo);

        transmitOutgoingDataForConnectedPeer(identifier);
    }

    // </editor-fold desc="BLETransportCallback">

    /**
     * Queue data for transmission to identifier
     */
    private void queueOutgoingData(byte[] data, String identifier) {
        if (!outBuffers.containsKey(identifier)) {
            outBuffers.put(identifier, new ArrayDeque<ByteBuffer>());
        }

        int readIdx = 0;
        while (readIdx < data.length) {
            ByteBuffer nextOutBuffer = availableBuffers.poll();
            if (nextOutBuffer == null) nextOutBuffer = ByteBuffer.allocate(MTU_BYTES);
            nextOutBuffer.put(data, readIdx, MTU_BYTES);
            readIdx += MTU_BYTES;
            outBuffers.get(identifier).add(nextOutBuffer);
        }
    }

    private boolean transmitOutgoingDataForConnectedPeer(String identifier) {
        if (!outBuffers.containsKey(identifier)) return false;

        ByteBuffer toSend = outBuffers.get(identifier).poll();

        boolean didSend = false;
        if (central.isConnectedTo(identifier)) {
            dataCharacteristic.setValue(toSend.array());
            didSend = central.write(dataCharacteristic, identifier);
        }
        else if (peripheral.isConnectedTo(identifier)) {
            dataCharacteristic.setValue(toSend.array());
            didSend = peripheral.indicate(dataCharacteristic, identifier);
        }

        if (didSend) {
            availableBuffers.add(toSend);

            if (callback.get() != null)
                callback.get().dataSentToIdentifier(this, toSend.array(), identifier);
        }
        return didSend;
    }

    private boolean isConnectedTo(String identifier) {
        return central.isConnectedTo(identifier) || peripheral.isConnectedTo(identifier);
    }
}
