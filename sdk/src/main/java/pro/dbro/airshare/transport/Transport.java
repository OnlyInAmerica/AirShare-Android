package pro.dbro.airshare.transport;

import android.support.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by davidbrodsky on 2/21/15.
 */
public abstract class Transport {

    public static enum ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    public static interface TransportCallback {

        public void dataReceivedFromIdentifier(Transport transport,
                                               byte[] data,
                                               String identifier);

        public void dataSentToIdentifier(Transport transport,
                                         byte[] data,
                                         String identifier);

        public void identifierUpdated(Transport transport,
                                      String identifier,
                                      ConnectionStatus status,
                                      Map<String, Object> extraInfo);

    }

    protected String serviceName;
    protected WeakReference<TransportCallback> callback;

    public Transport(String serviceName, TransportCallback callback) {
        this.serviceName = serviceName;
        this.callback = new WeakReference<>(callback);
    }

    public void setTransportCallback(TransportCallback callback) {
        this.callback = new WeakReference<>(callback);
    }

    @Nullable
    public TransportCallback getCallback() {
        return callback.get();
    }

    public abstract boolean sendData(byte[] data, Set<String> identifier);

    public abstract boolean sendData(byte[] data, String identifier);

    public abstract void advertise();

    public abstract void scanForPeers();

    public abstract void stop();

    /**
     * @return the Maximum Transmission Unit, in bytes, or 0 if unlimited.
     */
    public abstract int getMtuBytes();

}
