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
    private HashMap<String, Set<Transport>>         identifierTransports  = new HashMap<>();
    private BidiMap<String, SessionMessageReceiver> identifierReceivers   = new DualHashBidiMap<>();
    private BidiMap<String, SessionMessageSender>   identifierSenders     = new DualHashBidiMap<>();
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

    public void sendMessage(SessionMessage message, Peer recipient) {

        String recipientIdentifier = identifiedPeers.getKey(recipient);
        Transport transport = getPreferredTransportForPeer(recipient);

        if (!identifierSenders.containsKey(recipientIdentifier))
            identifierSenders.put(recipientIdentifier, new SessionMessageSender(message));
        else
            identifierSenders.get(recipientIdentifier).queueMessage(message);

        SessionMessageSender sender = identifierSenders.get(recipientIdentifier);

        if (transport != null)
            transport.sendData(sender.readNextChunk(transport.getMtuBytes()), recipientIdentifier);

        // If the peer is not currently available, data will be sent next time peer is available
    }

    // </editor-fold desc="Public API">

    // <editor-fold desc="Private API">

    private void initializeTransports(String serviceName) {
        transports = new HashSet<>();
        transports.add(new BLETransport(context, serviceName, this));
    }

    private @Nullable Transport getPreferredTransportForPeer(Peer peer) {
        // TODO : Provide Transport preference order. Perhaps each Transport has a unique int preference score
        if (!identifiedPeers.containsValue(peer)) return null;

        return identifierTransports.get(identifiedPeers.getKey(peer))
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

        SessionMessageSender sender = identifierSenders.get(identifier);

        if (sender != null && sender.getCurrentMessage() != null) {

            if (sender.getCurrentMessageProgress() == 1) {

                callback.messageSentToPeer(sender.getCurrentMessage(),
                                           identifiedPeers.get(identifier),
                                           null);

            } else {

                callback.messageSendingToPeer(sender.getCurrentMessage(),
                        identifiedPeers.get(identifier),
                        sender.getCurrentMessageProgress());
            }


            transport.sendData(sender.readNextChunk(transport.getMtuBytes()), identifier);
        }
    }

    @Override
    public void identifierUpdated(Transport transport,
                                  String identifier,
                                  Transport.ConnectionStatus status,
                                  Map<String, Object> extraInfo) {
        switch(status) {
            case CONNECTED:
                if (shouldIdentifyPeer(identifier)) {

                    if (!identifierSenders.containsKey(identifier))
                        identifierSenders.put(identifier, new SessionMessageSender(localIdentityMessage));
                    else
                        Timber.w("Outgoing messages already exist for unidentified peer %s", identifier);
                }

                if (!identifierTransports.containsKey(identifier))
                    identifierTransports.put(identifier, new HashSet<Transport>());

                identifierTransports.get(identifier).add(transport);

                // Send outgoing messages to peer
                SessionMessageSender sender = identifierSenders.get(identifier);

                if (sender.getCurrentMessage() != null) {

                    boolean sendingIdentity = sender.getCurrentMessage() instanceof IdentityMessage;

                    if (transport.sendData(sender.readNextChunk(transport.getMtuBytes()), identifier))
                        if (sendingIdentity) identifyingPeers.add(identifier);
                        else Timber.w("Failed to send %s message to new peer %s",
                                sender.getCurrentMessage().getType(),
                                identifier);
                }

                break;

            case DISCONNECTED:
                // We should maintain (in memory) identifiedPeers and identifierTransports
                // in case an identifier re-appears shortly
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

                Peer peer = ((IdentityMessage) message).getPeer();

                identifyingPeers.remove(senderIdentifier);
                identifiedPeers.put(senderIdentifier, peer);

                callback.peerStatusUpdated(peer, Transport.ConnectionStatus.CONNECTED);

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
