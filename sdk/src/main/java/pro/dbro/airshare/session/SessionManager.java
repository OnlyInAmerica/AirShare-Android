package pro.dbro.airshare.session;

import android.content.Context;
import android.support.annotation.Nullable;

import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualHashBidiMap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import pro.dbro.airshare.LocalPeer;
import pro.dbro.airshare.Peer;
import pro.dbro.airshare.transport.Transport;
import pro.dbro.airshare.transport.ble.BLETransport;
import timber.log.Timber;

/**
 * Created by davidbrodsky on 2/21/15.
 */
public class SessionManager implements Transport.TransportCallback, Session.SessionCallback {

    private HashMap<Peer, Session> sessions = new HashMap<>();
    private BidiMap<String, Peer> identifiedPeers = new DualHashBidiMap<>();
    private Set<String> identifyingPeers = new HashSet<>();

    public interface SessionManagerCallback {
        public void errorEstablishingSession(SessionManager manager, Exception e);
        public void sessionEstablished(SessionManager manager, Session session);
        public void peerStatusUpdated(Peer peer, Transport.ConnectionStatus newStatus);
    }

    private Context context;
    private HashMap<Peer, Set<Transport>> peerTransports;
    private Set<Transport> transports;
    private LocalPeer localPeer;
    private IdentityMessage localIdentityMessage;
    private SessionManagerCallback callback;

    public SessionManager(Context context,
                          String serviceName,
                          LocalPeer localPeer,
                          SessionManagerCallback callback) {

        this.localPeer = localPeer;
        this.callback = callback;
        this.context = context;
        peerTransports = new HashMap<>();

        localIdentityMessage = new IdentityMessage(localPeer);

        initializeTransports(serviceName);
    }

    /**
     * Initialize the default set of Transports
     */
    private void initializeTransports(String serviceName) {
        transports = new HashSet<>();
        transports.add(new BLETransport(context, serviceName, this));
    }

    public void advertiseLocalPeer() {
        for (Transport transport : transports)
            transport.advertise();
    }

    public void scanForPeers() {
        for (Transport transport : transports)
            transport.scanForPeers();
    }

    public void startSessionWithPeer(Peer peer) {
        Transport transport = getPreferredTransportForPeer(peer);
        if (transport == null) {
            Timber.e("No transport found for peer " + peer);
            return;
        }

        if (sessions.containsKey(peer)) {
            Timber.w("Session already exists for peer " + peer);
            return;
        }
        // Session now owns transport
        Session session = new Session(transport, localPeer, peer, this);
        sessions.put(peer, session);

        if (callback != null)
            callback.sessionEstablished(this, sessions.get(peer));
    }

    public void takeOwnershipOfTransportFromSession(Session session) {
        session.getTransport().setTransportCallback(this);
    }

    @Nullable
    public Transport getPreferredTransportForPeer(Peer peer) {
        // TODO : Provide Transport preference order. Perhaps each Transport has a unique int preference score
        return peerTransports.get(peer)
                             .iterator()
                             .next();
    }

    // <editor-fold desc="TransportCallback">

    @Override
    public void dataReceivedFromIdentifier(Transport transport, byte[] data, String identifier) {
        // Accumulate data. Should only ever be an IdentityMessage
    }

    @Override
    public void dataSentToIdentifier(Transport transport, byte[] data, String identifier) {

    }

    @Override
    public void identifierUpdated(Transport transport,
                                  String identifier,
                                  Transport.ConnectionStatus status,
                                  Map<String, Object> extraInfo) {
        switch(status) {
            case CONNECTED:
                if (shouldIdentifyPeer(identifier)) {
                    if(transport.sendData(localIdentityMessage.serialize(), identifier))
                        identifyingPeers.add(identifier);
                    else Timber.w("Failed to send Identity to new peer " + identifier);
                }
                break;

            case DISCONNECTED:
                // TODO If we have a session with the disconnected peer, return ownership
                // of Transport it occupied to SessionManager
                break;
        }



    }

    // </editor-fold desc="TransportCallback">

    // <editor-fold desc="SessionCallback">

    @Override
    public void messageOffered(Session session, SessionMessage message) {

    }

    // </editor-fold desc="SessionCallback">

    private boolean shouldIdentifyPeer(String identifer) {
        return !identifiedPeers.containsKey(identifer) && !identifyingPeers.contains(identifer);
    }

}
