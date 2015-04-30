package pro.dbro.airshare.transport;

/**
 * Represents a transport state for resuming.
 *
 * e.g : If a BLE transport is stopped during a temporary WiFi connection,
 * remember whether the stopped transport was advertising, scanning, or both.
 * Created by davidbrodsky on 4/29/15.
 */
public class TransportState {

    public final boolean isStopped;
    public final boolean wasAdvertising;
    public final boolean wasScanning;

    public TransportState(boolean isStopped, boolean wasAdvertising, boolean wasScanning) {
        this.isStopped = isStopped;
        this.wasAdvertising = wasAdvertising;
        this.wasScanning = wasScanning;
    }
}
