package pro.dbro.airshare.transport.ble;

import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.support.annotation.NonNull;

import org.apache.commons.codec.digest.DigestUtils;

import java.util.List;
import java.util.UUID;

import pro.dbro.airshare.DataUtil;
import pro.dbro.airshare.transport.ConnectionListener;
import pro.dbro.airshare.transport.Transport;

/**
 * Created by davidbrodsky on 2/21/15.
 */
public class BLETransport extends Transport implements ConnectionListener {

    private final UUID serviceUUID = UUID.fromString("B491602C-C912-47AE-B639-9C17A4AADB06");
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

        // TODO - Once we determine an algorithm shared between iOS / Android
        //serviceUUID = generateUUIDFromString(serviceName);

        central = new BLECentral(context, serviceUUID);
        central.setConnectionListener(this);
        peripheral = new BLEPeripheral(context, serviceUUID);
        peripheral.setConnectionListener(this);
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
        for (String identifier : identifiers) {
            boolean didSend = false;
            if (central.isConnectedTo(identifier)) {
                dataCharacteristic.setValue(data);
                central.write(dataCharacteristic, identifier);
                didSend = true;
            }
            else if (peripheral.isConnectedTo(identifier)) {
                dataCharacteristic.setValue(data);
                peripheral.indicate(dataCharacteristic, identifier);
                didSend = true;
            }
            else {
                // TODO : Queue data
            }

            if (didSend && callback.get() != null)
                callback.get().dataSentToIdentifier(this, data, identifier);
            return didSend;
        }
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
        if (central.isScanning()) central.stop();
    }

    // </editor-fold desc="Transport">

    // <editor-fold desc="ConnectionListener">

    @Override
    public void connectedTo(String deviceAddress) {
        if (callback.get() != null)
            callback.get().identifierUpdated(this,
                                             deviceAddress,
                                             ConnectionStatus.CONNECTED,
                                             null);
    }

    @Override
    public void disconnectedFrom(String deviceAddress) {
        if (callback.get() != null)
            callback.get().identifierUpdated(this,
                                             deviceAddress,
                                             ConnectionStatus.DISCONNECTED,
                                             null);
    }

    // </editor-fold desc="ConnectionListener">
}
