package pro.dbro.airshare.transport;

import android.support.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;

/**
 * Created by davidbrodsky on 2/21/15.
 */
public abstract class Transport {

    public static interface TransportCallback {

        public void dataReceivedFromIdentifier(Transport transport,
                                               byte[] data,
                                               String identifier);

        public void identifierUpdated(Transport transport,
                                      String identifier,
                                      Map<String, Object> extraInfo);

    }

    protected String serviceName;
    protected WeakReference<TransportCallback> callback;

    public Transport(String serviceName, TransportCallback callback) {
        this.serviceName = serviceName;
        this.callback = new WeakReference<>(callback);
    }

    @Nullable
    public TransportCallback getCallback() {
        return callback.get();
    }

    public abstract void sendData(byte[] data, List<String> identifier);

    public abstract void advertise();

    public abstract void scanForPeers();

    public abstract void stop();

}
