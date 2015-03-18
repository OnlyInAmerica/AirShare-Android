package pro.dbro.airshare.session;

import android.content.Context;
import android.support.annotation.Nullable;

import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualHashBidiMap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import pro.dbro.airshare.transport.Transport;
import pro.dbro.airshare.transport.ble.BLETransport;
import timber.log.Timber;

/**
 * Created by davidbrodsky on 2/21/15.
 */
public class SessionManager implements Transport.TransportCallback,
                                       SessionMessageDeserializer.SessionMessageDeserializerCallback,
        SessionMessageScheduler {

    public interface SessionManagerCallback {

        public void peerStatusUpdated(Peer peer, Transport.ConnectionStatus newStatus);

        public void messageReceivingFromPeer(SessionMessage message, Peer recipient, float progress);

        public void messageReceivedFromPeer(SessionMessage message, Peer recipient);

        public void messageSendingToPeer(SessionMessage message, Peer recipient, float progress);

        public void messageSentToPeer(SessionMessage message, Peer recipient, Exception exception);

    }

    private Context                                     context;
    private String                                      serviceName;
    private Set<Transport>                              transports;
    private LocalPeer                                   localPeer;
    private IdentityMessage                             localIdentityMessage;
    private SessionManagerCallback                      callback;
    private HashMap<String, Set<Transport>>             identifierTransports  = new HashMap<>();
    private BidiMap<String, SessionMessageDeserializer> identifierReceivers   = new DualHashBidiMap<>();
    private BidiMap<String, SessionMessageSerializer>   identifierSenders     = new DualHashBidiMap<>();
    private BidiMap<String, Peer>                       identifiedPeers       = new DualHashBidiMap<>();
    private Set<String>                                 identifyingPeers      = new HashSet<>();

    // <editor-fold desc="Public API">

    public SessionManager(Context context,
                          String serviceName,
                          LocalPeer localPeer,
                          SessionManagerCallback callback) {

        this.context     = context;
        this.serviceName = serviceName;
        this.localPeer   = localPeer;
        this.callback    = callback;

        localIdentityMessage = new IdentityMessage(this.localPeer);

        initializeTransports(serviceName);
    }

    public String getServiceName() {
        return serviceName;
    }

    public void advertiseLocalPeer() {
        for (Transport transport : transports)
            transport.advertise();
    }

    public void scanForPeers() {
        for (Transport transport : transports)
            transport.scanForPeers();
    }

    /**
     * Send a message to the given recipient. If the recipient is not currently available,
     * delivery will occur next time the peer is available
     */
    public void sendMessage(SessionMessage message, Peer recipient) {

        String recipientIdentifier = identifiedPeers.getKey(recipient);

        if (recipientIdentifier == null) {
            Timber.e("No Identifier for peer %s", recipient.getAlias());
            return;
        }

        Transport transport = getPreferredTransportForPeer(recipient);

        if (!identifierSenders.containsKey(recipientIdentifier))
            identifierSenders.put(recipientIdentifier, new SessionMessageSerializer(message));
        else
            identifierSenders.get(recipientIdentifier).queueMessage(message);

        SessionMessageSerializer sender = identifierSenders.get(recipientIdentifier);

        if (transport != null)
            transport.sendData(sender.readNextChunk(transport.getMtuForIdentifier(recipientIdentifier)),
                               recipientIdentifier);
        else
            Timber.d("Send queued. No transport available for identifier %s", recipientIdentifier);

        // If no transport for the peer is available, data will be sent next time peer is available
    }

    public Set<Peer> getAvailablePeers() {
        return identifiedPeers.values();
    }

    public void stop() {
        for (Transport transport : transports)
            transport.stop();
    }

    // </editor-fold desc="Public API">

    // <editor-fold desc="Private API">

    private void initializeTransports(String serviceName) {
        transports = new HashSet<>();
        transports.add(new BLETransport(context, serviceName, this));
    }

    private @Nullable Transport getPreferredTransportForPeer(Peer peer) {
        // TODO : Provide Transport preference order. Perhaps each Transport has a unique int preference score
        if (!identifiedPeers.containsValue(peer) ||
            !identifierTransports.containsKey(identifiedPeers.getKey(peer)))
                return null;

        return identifierTransports.get(identifiedPeers.getKey(peer))
                                   .iterator()
                                   .next();
    }

    private boolean shouldIdentifyPeer(String identifier) {
        return !identifiedPeers.containsKey(identifier) && !identifyingPeers.contains(identifier);
    }

    private void registerTransportForIdentifier(Transport transport, String identifier) {
        if (!identifierTransports.containsKey(identifier))
            identifierTransports.put(identifier, new HashSet<Transport>());

        boolean newTransport = identifierTransports.get(identifier).add(transport);
        if (newTransport)
            Timber.d("Transport added for identifier %s", newTransport, identifier);
    }

    // </editor-fold desc="Private API">

    // <editor-fold desc="TransportCallback">

    @Override
    public void dataReceivedFromIdentifier(Transport transport, byte[] data, String identifier) {

        // An asymmetric transport may not receive connection events
        // so we use this opportunity to associate the identifier with its transport
        registerTransportForIdentifier(transport, identifier);

        if (!identifierReceivers.containsKey(identifier))
            identifierReceivers.put(identifier, new SessionMessageDeserializer(context, this));

        identifierReceivers.get(identifier)
                           .dataReceived(data);
    }

    @Override
    public void dataSentToIdentifier(Transport transport, byte[] data, String identifier) {

        SessionMessageSerializer sender = identifierSenders.get(identifier);

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

            byte[] toSend = sender.readNextChunk(transport.getMtuForIdentifier(identifier));
            if (toSend != null)
                transport.sendData(toSend, identifier);
        }
    }

    @Override
    public void identifierUpdated(Transport transport,
                                  String identifier,
                                  Transport.ConnectionStatus status,
                                  Map<String, Object> extraInfo) {
        switch(status) {
            case CONNECTED:
                Timber.d("Connected to %s", identifier);
                if (shouldIdentifyPeer(identifier)) {
                    if (!identifierSenders.containsKey(identifier)) {
                        Timber.d("Sending identity to %s", identifier);
                        identifierSenders.put(identifier, new SessionMessageSerializer(localIdentityMessage));
                    } else
                        Timber.w("Outgoing messages already exist for unidentified peer %s", identifier);
                }

                registerTransportForIdentifier(transport, identifier);

                // Send outgoing messages to peer
                SessionMessageSerializer sender = identifierSenders.get(identifier);

                if (sender.getCurrentMessage() != null) {

                    boolean sendingIdentity = sender.getCurrentMessage() instanceof IdentityMessage;

                    if (transport.sendData(sender.readNextChunk(transport.getMtuForIdentifier(identifier)),
                                           identifier)) {
                        if (sendingIdentity) {
                            identifyingPeers.add(identifier);
                            Timber.d("Sent identity to %s", identifier);
                        }
                    } else
                        Timber.w("Failed to send %s message to new peer %s",
                                sender.getCurrentMessage().getType(),
                                identifier);
                }

                break;

            case DISCONNECTED:
                Timber.d("Disconnected from %s", identifier);
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
    public void onHeaderReady(SessionMessageDeserializer receiver, SessionMessage message) {

        String senderIdentifier = identifierReceivers.getKey(receiver);
        Timber.d("Received header for %s message from %s", message.getType(), senderIdentifier);
    }

    @Override
    public void onBodyProgress(SessionMessageDeserializer receiver, SessionMessage message, float progress) {

        String senderIdentifier = identifierReceivers.getKey(receiver);
        Timber.d("Received %s message with progress %f from %s", message.getType(), progress, senderIdentifier);
    }

    @Override
    public void onComplete(SessionMessageDeserializer receiver, SessionMessage message, Exception e) {

        String senderIdentifier = identifierReceivers.getKey(receiver);

        if (e == null) {

            Timber.d("Received complete %s message from %s", message.getType(), senderIdentifier);

            if (message instanceof IdentityMessage) {

                Peer peer = ((IdentityMessage) message).getPeer();

                boolean newIdentity = !identifiedPeers.containsKey(senderIdentifier);

                identifyingPeers.remove(senderIdentifier);
                identifiedPeers.put(senderIdentifier, peer);

                if (newIdentity) {
                    Timber.d("Received identity for %s. Responding with local identity", senderIdentifier);
                    sendMessage(localIdentityMessage, peer);
                    // As far as upper layers are concerned, connection events occur when the remote
                    // peer is identified.
                    callback.peerStatusUpdated(peer, Transport.ConnectionStatus.CONNECTED);
                }

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
