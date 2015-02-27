package pro.dbro.airshare.session;

import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.Map;

import pro.dbro.airshare.LocalPeer;
import pro.dbro.airshare.Peer;
import pro.dbro.airshare.transport.Transport;


/**
 * Created by davidbrodsky on 2/21/15.
 */
public class Session implements Transport.TransportCallback {

    public static enum SessionState { INITIATED, ACCEPTED }

    public static interface SessionCallback {
        public void messageOffered(Session session, SessionMessage message);
    }

    public static interface SessionMessageCallback {
        public void onProgress(SessionMessage message, float progress);
        public void onCompletion(SessionMessage message, Exception exception);
    }

    public Session(Transport transport,
                   LocalPeer localPeer,
                   Peer peer,
                   SessionCallback callback) {

        this.transport = transport;
        this.transport.setTransportCallback(this);
        this.localPeer = localPeer;
        this.peer = peer;
        this.callback = new WeakReference<>(callback);
    }

    private Transport transport;
    private LocalPeer localPeer;
    private Peer peer;
    private SessionState state;

    private WeakReference<SessionCallback> callback;

    public Session() {
        state = SessionState.INITIATED;
    }

    public void acceptMessage(SessionMessage message, SessionMessageCallback callback) {

    }

    public void offerMessage(SessionMessage message, SessionMessageCallback callback) {

    }

    public static void loadIdenticonForSession(Session session, ImageView target, boolean isReceiver) {

    }

    public Transport getTransport() {
        return transport;
    }

    // <editor-fold desc="TransportCallback">

    @Override
    public void dataReceivedFromIdentifier(Transport transport, byte[] data, String identifier) {

    }

    @Override
    public void dataSentToIdentifier(Transport transport, byte[] data, String identifier) {

    }

    @Override
    public void identifierUpdated(Transport transport, String identifier, Transport.ConnectionStatus status, Map<String, Object> extraInfo) {

    }

    // </editor-fold desc="TransportCallback">
}
