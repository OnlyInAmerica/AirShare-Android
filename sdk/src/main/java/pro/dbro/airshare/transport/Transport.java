package pro.dbro.airshare.transport;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.common.base.Objects;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Created by davidbrodsky on 2/21/15.
 */
public abstract class Transport implements Comparable<Transport> {

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
                                         String identifier,
                                         Exception exception);

        public void identifierUpdated(Transport transport,
                                      String identifier,
                                      ConnectionStatus status,
                                      boolean isHost,
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

    /** Return a unique code identifying this transport */
    public abstract int getTransportCode();

    /**
     * @return the Maximum Transmission Unit, in bytes, or 0 if unlimited.
     */
    public abstract int getMtuForIdentifier(String identifier);

    @Override
    public int compareTo (@NonNull Transport another) {
        return getMtuForIdentifier("") - another.getMtuForIdentifier("");
    }

    @Override
    public boolean equals(Object obj) {

        if(obj == this) return true;
        if(obj == null) return false;

        if (getClass().equals(obj.getClass()))
        {
            Transport other = (Transport) obj;
            return getTransportCode() == other.getTransportCode();
        }

        return false;
    }

    @Override
    public int hashCode() {
        return getTransportCode();
    }

}
