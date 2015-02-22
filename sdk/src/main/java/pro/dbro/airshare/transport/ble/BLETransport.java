package pro.dbro.airshare.transport.ble;

import android.content.Context;
import android.support.annotation.NonNull;

import java.util.List;
import java.util.UUID;

import pro.dbro.airshare.DataUtil;
import pro.dbro.airshare.transport.ConnectionListener;
import pro.dbro.airshare.transport.Transport;

/**
 * Created by davidbrodsky on 2/21/15.
 */
public class BLETransport extends Transport implements ConnectionListener {

    private UUID          serviceUUID = UUID.fromString("96F22BCA-F08C-43F9-BF7D-EEBC579C94D2");
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
    }

    private UUID generateUUIDFromString(String input) {
        String hexString = DataUtil.bytesToHex(input.getBytes());
        StringBuilder uuid = new StringBuilder();
        for(int x = 0; x < 16; x++) {
            if (x >= input.length())
                uuid.append('0');
            else
                uuid.append(input.charAt(x));
        }

        uuid.insert(8, '-');
        uuid.insert(13,'-');
        uuid.insert(18,'-');
        uuid.insert(23,'-');
        return UUID.fromString(uuid.toString());
    }

    // <editor-fold desc="Transport">

    @Override
    public void sendData(byte[] data, List<String> identifiers) {

    }

    @Override
    public void advertise() {
        peripheral.start();
    }

    @Override
    public void scanForPeers() {
        central.start();
    }

    @Override
    public void stop() {
        if (peripheral.isAdvertising()) peripheral.stop();
        if (central.isIsScanning()) central.stop();
    }

    // </editor-fold desc="Transport">

    // <editor-fold desc="ConnectionListener">

    @Override
    public void connectedTo(String deviceAddress) {

    }

    @Override
    public void disconnectedFrom(String deviceAddress) {

    }

    // </editor-fold desc="ConnectionListener">

}
