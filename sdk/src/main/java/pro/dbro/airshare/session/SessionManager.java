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
public class SessionManager implements Transport.TransportCallback, SessionMessageReceiver.SessionMessageReceiverCallback {

    public interface SessionManagerCallback {

        public void peerStatusUpdated(Peer peer, Transport.ConnectionStatus newStatus);

        public void messageReceivedFromPeer(SessionMessage message, Peer recipient);

        public void messageSendingToPeer(SessionMessage message, Peer recipient, float progress);

        public void messageSentToPeer(SessionMessage message, Peer recipient, Exception exception);

    }

    private Context                                 context;
    private Set<Transport>                          transports;
    private LocalPeer                               localPeer;
    private IdentityMessage                         localIdentityMessage;
    private SessionManagerCallback                  callback;
    private HashMap<Peer, Set<Transport>>           peerTransports        = new HashMap<>();
    private BidiMap<String, SessionMessageReceiver> identifierReceivers   = new DualHashBidiMap<>();
    private BidiMap<String, Peer>                   identifiedPeers       = new DualHashBidiMap<>();
    private Set<String>                             identifyingPeers      = new HashSet<>();

    // <editor-fold desc="Public API">

    public SessionManager(Context context,
                          String serviceName,
                          LocalPeer localPeer,
                          SessionManagerCallback callback) {

        this.localPeer = localPeer;
        this.callback = callback;
        this.context = context;

        localIdentityMessage = new IdentityMessage(localPeer);

        initializeTransports(serviceName);
    }

    public void advertiseLocalPeer() {
        for (Transport transport : transports)
            transport.advertise();
    }

    public void scanForPeers() {
        for (Transport transport : transports)
            transport.scanForPeers();
    }

    public void sendSessionMessage(SessionMessage message, Peer recipient) {
        Transport transport = getPreferredTransportForPeer(recipient);

        if (transport != null) {
            // Need SessionMessage Sender object that serializes MTU chunks
            // and awaits notification of reception
        }
    }

    // </editor-fold desc="Public API">

    // <editor-fold desc="Private API">

    private void initializeTransports(String serviceName) {
        transports = new HashSet<>();
        transports.add(new BLETransport(context, serviceName, this));
    }

    private @Nullable Transport getPreferredTransportForPeer(Peer peer) {
        // TODO : Provide Transport preference order. Perhaps each Transport has a unique int preference score
        return peerTransports.get(peer)
                             .iterator()
                             .next();
    }

    private boolean shouldIdentifyPeer(String identifier) {
        return !identifiedPeers.containsKey(identifier) && !identifyingPeers.contains(identifier);
    }

    // </editor-fold desc="Private API">

    // <editor-fold desc="TransportCallback">

    @Override
    public void dataReceivedFromIdentifier(Transport transport, byte[] data, String identifier) {

        if (!identifierReceivers.containsKey(identifier))
            identifierReceivers.put(identifier, new SessionMessageReceiver(context, this));

        identifierReceivers.get(identifier)
                     .dataReceived(data);

    }

    @Override
    public void dataSentToIdentifier(Transport transport, byte[] data, String identifier) {
        // TODO Need a lightweight SessionMessageReceiver that monitors when a message is completely accumulated,
        // TODO but doesn't save accumulating data.
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
                // We should maintain identification of disconnected peers in case their identifier re-appears
                if (identifiedPeers.containsKey(identifier))
                    callback.peerStatusUpdated(identifiedPeers.get(identifier), Transport.ConnectionStatus.DISCONNECTED);
                break;
        }
    }

    // </editor-fold desc="TransportCallback">

    // <editor-fold desc="SessionMessageReceiverCallback">

    @Override
    public void onHeaderReady(SessionMessageReceiver receiver, SessionMessage message) {

        String senderIdentifier = identifierReceivers.getKey(receiver);
        Timber.d("Received header for %s message from %s", message.getType(), senderIdentifier);
    }

    @Override
    public void onBodyProgress(SessionMessageReceiver receiver, SessionMessage message, float progress) {

        String senderIdentifier = identifierReceivers.getKey(receiver);
        Timber.d("Received %s message with progress %f from %s", message.getType(), progress, senderIdentifier);
    }

    @Override
    public void onComplete(SessionMessageReceiver receiver, SessionMessage message, Exception e) {

        String senderIdentifier = identifierReceivers.getKey(receiver);

        if (e == null) {

            Timber.d("Received complete %s message from %s", message.getType(), senderIdentifier);

            if (message instanceof IdentityMessage) {

                identifyingPeers.remove(senderIdentifier);
                identifiedPeers.put(senderIdentifier, ((IdentityMessage) message).getPeer());

                callback.peerStatusUpdated(((IdentityMessage) message).getPeer(),
                                           Transport.ConnectionStatus.CONNECTED);

            } else if (identifiedPeers.containsKey(senderIdentifier)) {

                callback.messageReceivedFromPeer(message, identifiedPeers.get(senderIdentifier));

            } else {

                Timber.w("Received complete non-identity message from unidentified peer");
            }

        } else {
            Timber.d("Incoming message from %s failed with error '%s'", senderIdentifier, e.getLocalizedMessage());
            e.printStackTrace();
        }

    }

    // </editor-fold desc="SessionMessageReceiverCallback">

}
