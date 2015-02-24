package pro.dbro.airshare.transport.ble;

import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.support.annotation.NonNull;

import org.apache.commons.codec.digest.DigestUtils;

import java.util.List;
import java.util.Map;
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
public class BLETransport extends Transport implements BLETransportCallback {

    private final UUID serviceUUID;
    private final UUID dataUUID    = UUID.fromString("72A7700C-859D-4317-9E35-D7F5A93005B1");

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
    public boolean sendData(byte[] data, List<String> identifiers) {
        boolean didSendAll = true;

        for (String identifier : identifiers) {
            boolean didSend = sendData(data, identifier);

            if (!didSend) didSendAll = false;
        }
        return didSendAll;
    }

    @Override
    public boolean sendData(byte[] data, String identifier) {
        boolean didSend = false;
        if (central.isConnectedTo(identifier)) {
            dataCharacteristic.setValue(data);
            didSend = central.write(dataCharacteristic, identifier);
        }
        else if (peripheral.isConnectedTo(identifier)) {
            dataCharacteristic.setValue(data);
            didSend = peripheral.indicate(dataCharacteristic, identifier);
        }
        else {
            Timber.e("SendData called but peer not connected. Need to buffer data");
            // TODO : Queue data
        }

        if (didSend && callback.get() != null)
            callback.get().dataSentToIdentifier(this, data, identifier);

        return didSend;
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
    }

    // </editor-fold desc="BLETransportCallback">
}
