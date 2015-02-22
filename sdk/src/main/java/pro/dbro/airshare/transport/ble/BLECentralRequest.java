package pro.dbro.airshare.transport.ble;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import timber.log.Timber;

/**
 * A request the BLECentral should perform on a remote BLEPeripheral
 *
 * Created by davidbrodsky on 10/20/14.
 */
public abstract class BLECentralRequest {

    public static enum RequestType { READ, WRITE }

    public BluetoothGattCharacteristic characteristic;
    public RequestType requestType;

    public BLECentralRequest(BluetoothGattCharacteristic characteristic, RequestType requestType) {
        this.characteristic = characteristic;
        this.requestType = requestType;
    }

    /**
     * Return true if a request was made, false if there is no appropriate request
     * for the given remotePeripheral
     */
    public final boolean doRequest(BluetoothGatt remotePeripheral) {
        boolean success = false;
        switch (requestType) {
            case READ:
                success = remotePeripheral.readCharacteristic(characteristic);
                break;
            case WRITE:
                byte[] dataToWrite = getDataToWrite(remotePeripheral);
                if (dataToWrite != null) {
                    characteristic.setValue(dataToWrite);
                    success = remotePeripheral.writeCharacteristic(characteristic);
                } else {
                    Timber.d(characteristic.getUuid() + ". no data to send to this peripheral");
                }
                break;
        }
        Timber.d(String.format("%s success: %b", characteristic.getUuid(), success));
        return success;
    }

    /**
     * Handle the request response and return whether this request should
     * be considered complete. If it is not complete, it will be re-issued
     * along with any modifications made to characteristic.
     *
     * @return true if this request should be considered complete. false if it should
     * be re-issued with characteristic
     */
    public abstract boolean handleResponse(BluetoothGatt remotePeripheral, BluetoothGattCharacteristic characteristic, int status);

    /**
     * If this is a WRITE request, Override to return data to write
     * with knowledge of the actual remotePeripheral
     */
    public byte[] getDataToWrite(BluetoothGatt remotePeripheral) {
        return null;
    }

}
