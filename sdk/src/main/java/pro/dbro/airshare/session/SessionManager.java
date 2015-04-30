package pro.dbro.airshare.session;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Pair;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import pro.dbro.airshare.transport.Transport;
import pro.dbro.airshare.transport.TransportState;
import pro.dbro.airshare.transport.ble.BLETransport;
import pro.dbro.airshare.transport.wifi.WifiTransport;
import timber.log.Timber;

/**
 * Created by davidbrodsky on 2/21/15.
 */
public class SessionManager implements Transport.TransportCallback,
                                       SessionMessageDeserializer.SessionMessageDeserializerCallback,
                                       SessionMessageScheduler {

    private static final boolean VERBOSE = true;

    public interface SessionManagerCallback {

        public void peerStatusUpdated(@NonNull Peer peer, @NonNull Transport.ConnectionStatus newStatus, boolean isHost);

        public void peerTransportUpdated(@NonNull Peer peer, int newTransportCode, @Nullable Exception exception);

        public void messageReceivingFromPeer(@NonNull SessionMessage message, @NonNull Peer recipient, float progress);

        public void messageReceivedFromPeer(@NonNull SessionMessage message, @NonNull Peer recipient);

        public void messageSendingToPeer(@NonNull SessionMessage message, @NonNull Peer recipient, float progress);

        public void messageSentToPeer(@NonNull SessionMessage message, @NonNull Peer recipient, @Nullable Exception exception);

    }

    private Context                                   context;
    private String                                    serviceName;
    private SortedSet<Transport>                      transports;
    private LocalPeer                                 localPeer;
    private IdentityMessage                           localIdentityMessage;
    private SessionManagerCallback                    callback;
    private HashMap<String, Transport>                identifierTransports       = new HashMap<>();
    private HashMap<Peer, SortedSet<Transport>>       peerTransports             = new HashMap<>();
    private BiMap<String, SessionMessageDeserializer> identifierReceivers        = HashBiMap.create();
    private BiMap<String, SessionMessageSerializer>   identifierSenders          = HashBiMap.create();
    private final HashMap<String, Peer>               identifiedPeers            = new HashMap<>();
    private final SetMultimap<Peer, String>           peerIdentifiers            = HashMultimap.create();
    private Set<String>                               identifyingPeers           = new HashSet<>();
    private Set<String>                               hostIdentifiers            = new HashSet<>();
    private HashMap<Peer, Transport>                  peerUpgradeRequests        = new HashMap<>();
    private TransportState                            baseTransportState         = new TransportState(false, false, false);

    // <editor-fold desc="Public API">

    public SessionManager(Context context,
                          String serviceName,
                          LocalPeer localPeer,
                          SessionManagerCallback callback) {

        this.context     = context;
        this.serviceName = serviceName;
        this.localPeer   = localPeer;
        this.callback    = callback;

        localIdentityMessage = new IdentityMessage(this.context, this.localPeer);

        initializeTransports(serviceName);
    }

    public String getServiceName() {
        return serviceName;
    }

    public void advertiseLocalPeer() {
        // Only advertise on the "base" (first) transport
        transports.first().advertise();
        baseTransportState = new TransportState(baseTransportState.isStopped, true, baseTransportState.wasScanning);
    }

    public void scanForPeers() {
        // Only scan on the "base" (first) transport
        transports.first().scanForPeers();
        baseTransportState = new TransportState(baseTransportState.isStopped, baseTransportState.wasAdvertising, true);
    }

    /**
     * Send a message to the given recipient. If the recipient is not currently available,
     * delivery will occur next time the peer is available
     */
    // TODO : This  method needs to be re-evaluated to be more robust
    // If preferred transport not available, queue on base transport?
    public void sendMessage(SessionMessage message, Peer recipient) {

        Set<String> recipientIdentifiers = peerIdentifiers.get(recipient);
        String targetRecipientIdentifier = null;

        if (recipientIdentifiers == null || recipientIdentifiers.size() == 0) { // TODO: Does HashMultiMap return null or empty collection?
            Timber.e("No Identifiers for peer %s", recipient.getAlias());
            return;
        }

        Transport transport = getPreferredTransportForPeer(recipient);

        if (transport == null) {
            Timber.e("No transport for %s", recipient.getAlias());
            return;
        }

        for (String recipientIdentifier : recipientIdentifiers) {
            if (identifierTransports.get(recipientIdentifier).equals(transport))
                targetRecipientIdentifier = recipientIdentifier;
        }

        if (targetRecipientIdentifier == null) {
            Timber.e("Could not find identifier for %s on preferred transport %d", recipient.getAlias(), transport.getTransportCode());
            return;
            // TODO : Fall back to base transport
        }

        if (!identifierSenders.containsKey(targetRecipientIdentifier))
            identifierSenders.put(targetRecipientIdentifier, new SessionMessageSerializer(message));
        else
            identifierSenders.get(targetRecipientIdentifier).queueMessage(message);

        SessionMessageSerializer sender = identifierSenders.get(targetRecipientIdentifier);

        transport.sendData(sender.getNextChunk(transport.getMtuForIdentifier(targetRecipientIdentifier)),
                               targetRecipientIdentifier);
//        else
//            Timber.d("Send queued. No transport available for identifier %s", targetRecipientIdentifier);

        // If no transport for the peer is available, data will be sent next time peer is available
    }

    public Set<Peer> getAvailablePeers() {
        return new HashSet<Peer>(identifiedPeers.values());
    }

    public synchronized void stop() {
        // Stop all running transports
        for (Transport transport : transports)
            transport.stop();

        reset();
    }

    public void requestTransportUpgrade(Peer remotePeer) {
        Timber.d("Transport upgrade with %s requested", remotePeer.getAlias());
        Transport supplementalTransport = null;
        Iterator<Transport> transports = this.transports.iterator();

        while (transports.hasNext()) {
            supplementalTransport = transports.next();

            if (!remotePeer.supportsTransport(supplementalTransport.getTransportCode())) {
                Timber.d("Peer does not support supplementary transport code %d", supplementalTransport.getTransportCode());
                supplementalTransport = null;
            }
        }

        if (supplementalTransport != null) {
            // We found an available upgrade transport
            Timber.d("Sending transport upgrade message for transport %d to peer %s",
                     supplementalTransport.getTransportCode(),
                     remotePeer.getAlias());


            peerUpgradeRequests.put(remotePeer, supplementalTransport);
            upgradeTransport(remotePeer, supplementalTransport.getTransportCode());
            sendMessage(new TransportUpgradeMessage(supplementalTransport.getTransportCode()), remotePeer);
        } else {
            Timber.w("Transport upgrade could not proceed. No suitable transport found");
        }
    }

    public int getTransportCodeForPeer(Peer peer) {
        return getPreferredTransportForPeer(peer).getTransportCode();
    }

    // </editor-fold desc="Public API">

    // <editor-fold desc="Private API">

    private void reset() {

        identifierTransports.clear();
        peerTransports.clear();
        identifierReceivers.clear();
        identifierSenders.clear();
        identifiedPeers.clear();
        identifyingPeers.clear();
        hostIdentifiers.clear();
        peerUpgradeRequests.clear();
        peerIdentifiers.clear();

        baseTransportState = new TransportState(false, false, false);
    }

    private void initializeTransports(String serviceName) {
        // First transport is considered "base" transport
        // Additional transports are considered supplementary and
        // will only be activated upon request
        transports = new TreeSet<>();
        transports.add(new BLETransport(context, serviceName, this));
        transports.add(new WifiTransport(context, serviceName, this));
    }

    private Transport getAvailableTransportByCode(int transportCode) {
        Transport requestedTransport = null;
        for (Transport transport : transports) {
            if (transport.getTransportCode() == transportCode) {
                requestedTransport = transport;
            }
        }
        return requestedTransport;
    }

    private void upgradeTransport(Peer remotePeer, int transportCode) {
        Transport requestedTransport = getAvailableTransportByCode(transportCode);

        if (requestedTransport == null) {
            Timber.d("Cannot find requested transport %d. Ignoring request", transportCode);
            callback.peerTransportUpdated(remotePeer, -1, new UnsupportedOperationException(String.format("Device does not support requested transport with code %d", transportCode)));
            return;
        }

        // Preserve host / client relationship in new transport
        if (hostIdentifiers.contains(peerIdentifiers.get(remotePeer).iterator().next())) { // TODO : Guard against remotePeer not in peerIdentifiers
            Timber.d("Transport upgrade requested with host peer, acting as client on new transport");
            requestedTransport.scanForPeers();
        } else {
            Timber.d("Transport upgrade requested with client peer, acting as host on new transport");
            requestedTransport.advertise();
        }
    }


    private @Nullable Transport getPreferredTransportForPeer(Peer peer) {

        if (!peerTransports.containsKey(peer))
                return null;

        // Return the Transport with the highest value (largest MTU)
        return peerTransports.get(peer)
                             .last();
    }

    private boolean shouldIdentifyPeer(String identifier) {
        // TODO : Might have banned peers etc.
        return !identifyingPeers.contains(identifier);
    }

    private void registerTransportForIdentifier(Transport transport, String identifier) {
        if (identifierTransports.containsKey(identifier)) {
            //Timber.w("Transport already registered for identifier %s", identifier);
            return;
        }

        Timber.d("Transported added for identifier %s", identifier);
        identifierTransports.put(identifier, transport);
    }

    private void registerTransportForPeer(Transport transport, Peer peer) {
        if (!peerTransports.containsKey(peer))
            peerTransports.put(peer, new TreeSet<Transport>());

        boolean newTransport = peerTransports.get(peer).add(transport);
        if (newTransport) {
            Timber.d("Transport added for peer %s", peer.getAlias());
            if (peerUpgradeRequests.containsKey(peer) &&
                peerUpgradeRequests.get(peer).getTransportCode() == transport.getTransportCode()) {

                Timber.d("Established upgraded transport connection with %s", peer.getAlias());
                peerUpgradeRequests.remove(peer);
                // Important: client must call callback.peerTransportUpdated(peer, transport, null);
                // when appropriate
            }
        }
    }

    // </editor-fold desc="Private API">

    // <editor-fold desc="TransportCallback">

    @Override
    public synchronized void dataReceivedFromIdentifier(Transport transport, byte[] data, String identifier) {

        // An asymmetric transport may not receive connection events
        // so we use this opportunity to associate the identifier with its transport
        registerTransportForIdentifier(transport, identifier);

        if (!identifierReceivers.containsKey(identifier))
            identifierReceivers.put(identifier, new SessionMessageDeserializer(context, this));

        identifierReceivers.get(identifier)
                           .dataReceived(data);
    }

    @Override
    public synchronized void dataSentToIdentifier(Transport transport, byte[] data, String identifier, Exception exception) {

        if (exception != null) {
            Timber.w("Data failed to send to %s", identifier);
            return;
        }

        SessionMessageSerializer sender = identifierSenders.get(identifier);

        if (sender == null) {
            Timber.w("No sender for dataSentToIdentifier to %s", identifier);
            return;
        }

        Pair<SessionMessage, Float> messagePair = sender.ackChunkDelivery();

        if (messagePair != null) {

            SessionMessage message = messagePair.first;
            float progress = messagePair.second;

            if (VERBOSE) Timber.d("%d %s bytes (%.0f pct) sent to %s",
                                  data.length,
                                  message.getType(),
                                  progress * 100,
                                  identifier);

            if (progress == 1 && message.equals(localIdentityMessage)) {
                Timber.d("Local identity acknowledged by recipient");
                identifyingPeers.add(identifier);
            }

            Peer recipient = identifiedPeers.get(identifier);
            if (recipient != null) {
                if (progress == 1) {

                    // Process completely sent AirShare messages, pass non-AirShare messages
                    // up via messageSentToPeer
                    if (message.equals(localIdentityMessage)) {

                        if (peerIdentifiers.get(recipient).size() == 1) {
                            Timber.d("Reporting peer connected after last id sent");
                            callback.peerStatusUpdated(recipient,
                                                       Transport.ConnectionStatus.CONNECTED,
                                                       hostIdentifiers.contains(identifier));
                        }

                    } else if (message.getType().equals(TransportUpgradeMessage.HEADER_TYPE)) {
                        // Report transport upgraded once peer connects over new transport
                        // don't report to #messageSendingToPeer
                        Timber.d("Sent TranportUpgradeMessage");

                    } else {
                        callback.messageSentToPeer(message,
                                identifiedPeers.get(identifier),
                                null);
                    }

                } else {

                    callback.messageSendingToPeer(message,
                            identifiedPeers.get(identifier),
                            progress);
                }
            } else
                Timber.w("Cannot report %s message send, %s not yet identified",
                    message.getType(), identifier);

            byte[] toSend = sender.getNextChunk(transport.getMtuForIdentifier(identifier));
            if (toSend != null)
                transport.sendData(toSend, identifier);
        } else
            Timber.w("No current message corresponding to dataSentToIdentifier");
    }

    @Override
    public synchronized void identifierUpdated(Transport transport,
                                  String identifier,
                                  Transport.ConnectionStatus status,
                                  boolean peerIsHost,
                                  Map<String, Object> extraInfo) {
        switch(status) {
            case CONNECTED:
                Timber.d("Connected to %s", identifier);
                if (peerIsHost) hostIdentifiers.add(identifier);

                // Only one peer (client) needs to initiate identification
                if (peerIsHost && shouldIdentifyPeer(identifier)) {
                    Timber.d("Queuing identity to %s", identifier);
                    if (!identifierSenders.containsKey(identifier)) {
                        identifierSenders.put(identifier, new SessionMessageSerializer(localIdentityMessage));
                    } else
                        Timber.w("Outgoing messages already exist for unidentified peer %s", identifier);
                }

                registerTransportForIdentifier(transport, identifier);

                // Send outgoing messages to peer
                SessionMessageSerializer sender = identifierSenders.get(identifier);

                if (sender != null && sender.getCurrentMessage() != null) {

                    boolean sendingIdentity = sender.getCurrentMessage() instanceof IdentityMessage;

                    byte[] toSend = sender.getNextChunk(transport.getMtuForIdentifier(identifier));
                    if (toSend == null) return;

                    if (transport.sendData(toSend, identifier)) {
                        if (sendingIdentity) {
                            Timber.d("Sent identity to %s", identifier);
                        }
                    } else
                        Timber.w("Failed to send %s message to new peer %s",
                                sender.getCurrentMessage().getType(),
                                identifier);
                }

                break;

            case DISCONNECTED:
                if (peerIsHost) hostIdentifiers.remove(identifier);

                Peer peer = identifiedPeers.get(identifier);

                if (peer != null) {

                    Timber.d("Disconnected from %s (%s). Had %d transports",
                            identifier,
                            peer.getAlias(),
                            peerIdentifiers.get(peer).size());

                    peerTransports.get(peer).remove(transport);
                    Set<String> identifiers = peerIdentifiers.get(peer);
                    identifiers.remove(identifier);

                    // If all transports for this peer are disconnected, send disconnect
                    if (identifiers.size() == 0) {
                        Timber.d("Disconnected from %s", peer.getAlias());

                        callback.peerStatusUpdated(identifiedPeers.get(identifier),
                                Transport.ConnectionStatus.DISCONNECTED,
                                peerIsHost);

                        if (baseTransportState.isStopped) {
                            Transport baseTransport = transports.first();
                            baseTransportState = new TransportState(false,
                                                                    baseTransportState.wasAdvertising,
                                                                    baseTransportState.wasScanning);

                            if (baseTransportState.wasAdvertising) baseTransport.advertise();
                            if (baseTransportState.wasScanning) baseTransport.scanForPeers();
                        }

                    } else if (identifiers.size() > 0) {
                        Timber.d("Transport disconnected from %s. %d others remain", peer.getAlias(), identifiers.size());
                        // One of the peers' identifiers disconnected.
                        // If it was a supplementary transport, we should report the base transport
                        // as active
                        Transport remainingTransport = getPreferredTransportForPeer(peer);
                        // If remainingTransport is null, our state is borked, as we have remaining
                        // identifiers for this peer. In this case, we should crash because it's the fault
                        // of this code
                        if (remainingTransport instanceof BLETransport) {
                            callback.peerTransportUpdated(peer, remainingTransport.getTransportCode(), null);
                        }

                    }

                } else
                    Timber.w("Could not report disconnection, peer not identified");

                identifierTransports.remove(identifier);
                identifyingPeers.remove(identifier);
                identifiedPeers.remove(identifier);
                identifierSenders.remove(identifier);
                identifierReceivers.remove(identifier);
                break;
        }
    }

    // </editor-fold desc="TransportCallback">

    // <editor-fold desc="SessionMessageReceiverCallback">

    @Override
    public void onHeaderReady(SessionMessageDeserializer receiver, SessionMessage message) {

        String senderIdentifier = identifierReceivers.inverse().get(receiver);
        Timber.d("Received header for %s message from %s", message.getType(), senderIdentifier);
    }

    @Override
    public void onBodyProgress(SessionMessageDeserializer receiver, SessionMessage message, float progress) {

        String senderIdentifier = identifierReceivers.inverse().get(receiver);
        if (VERBOSE) Timber.d("Received %s message with progress %f from %s", message.getType(), progress, senderIdentifier);

        callback.messageReceivingFromPeer(message, identifiedPeers.get(senderIdentifier), progress);
    }

    @Override
    public void onComplete(SessionMessageDeserializer receiver, SessionMessage message, Exception e) {

        // Process messages belonging to the AirShare framework and propagate
        // application level messages via our callback

        String senderIdentifier = identifierReceivers.inverse().get(receiver);

        if (e == null) {

            Timber.d("Received complete %s message from %s", message.getType(), senderIdentifier);

            if (message instanceof IdentityMessage) {

                Peer peer = ((IdentityMessage) message).getPeer();

                peerIdentifiers.put(peer, senderIdentifier);

                boolean sentIdentityToSender = identifyingPeers.contains(senderIdentifier);
                boolean newIdentity = !identifiedPeers.containsKey(senderIdentifier); // should never be false

                identifyingPeers.remove(senderIdentifier);
                identifiedPeers.put(senderIdentifier, peer);

                Transport identifierTransport = identifierTransports.get(senderIdentifier);
                boolean newTransport = peerTransports.get(peer) == null || !peerTransports.get(peer).contains(identifierTransport);
                registerTransportForPeer(identifierTransport, peer);

                if (newIdentity) {
                    Timber.d("Received #%s identifier for %s. %s", String.valueOf(peerIdentifiers.get(peer).size()),
                                                                 peer.getAlias(),
                                                                 sentIdentityToSender ? "" : "Responding with own.");
                    // As far as upper layers are concerned, connection events occur when the remote
                    // peer is identified.
                    if (!sentIdentityToSender)
                        sendMessage(localIdentityMessage, peer); // Report peer connected after identity send ack'd
                    else if (peerIdentifiers.get(peer).size() == 1) // If peer is already connected via another transport, don't re-notify
                        callback.peerStatusUpdated(peer, Transport.ConnectionStatus.CONNECTED, hostIdentifiers.contains(senderIdentifier));
                }

                // We must notify client of new transport *after* sending identity, if necessary. Else they might queue data ahead of it
                if (newTransport && peerIdentifiers.get(peer).size() > 1) {
                    callback.peerTransportUpdated(peer, identifierTransport.getTransportCode(), null);

                    // TESTING : Stop base transport when upgrade successful
                    Timber.d("Stopping base transport. %d identifiers for peer", peerIdentifiers.get(peer).size());
                    baseTransportState = new TransportState(true, baseTransportState.wasAdvertising, baseTransportState.wasScanning);
                    Transport baseTransport = transports.first();
                    baseTransport.stop();
                }

            } else if (message instanceof TransportUpgradeMessage) {
                Peer peer = identifiedPeers.get(senderIdentifier);
                int transportCode = ((TransportUpgradeMessage) message).getTransportCode();
                Timber.d("Got TransportUpgradeMessage for transport %d from %s", transportCode, peer.getAlias());
                peerUpgradeRequests.put(peer, getAvailableTransportByCode(transportCode));
                upgradeTransport(peer, transportCode);

            } else if (identifiedPeers.containsKey(senderIdentifier)) {
                // This message is not involved in the AirShare framework, so we notify the next layer up
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
