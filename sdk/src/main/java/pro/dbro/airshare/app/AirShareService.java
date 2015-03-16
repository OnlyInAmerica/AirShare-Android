package pro.dbro.airshare.app;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.Nullable;

import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualHashBidiMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import pro.dbro.airshare.crypto.KeyPair;
import pro.dbro.airshare.crypto.SodiumShaker;
import pro.dbro.airshare.session.FileTransferMessage;
import pro.dbro.airshare.session.LocalPeer;
import pro.dbro.airshare.session.Peer;
import pro.dbro.airshare.session.SessionManager;
import pro.dbro.airshare.session.SessionMessage;
import pro.dbro.airshare.transport.Transport;
import timber.log.Timber;

/**
 * Created by davidbrodsky on 11/4/14.
 */
public class AirShareService extends Service implements ActivityRecevingMessagesIndicator,
                                                        SessionManager.SessionManagerCallback {

    public static interface AirShareSenderCallback {

        public void onTransferOfferResponse(OutgoingTransfer transfer, Peer recipient, boolean recipientDidAccept);

        public void onTransferProgress(OutgoingTransfer transfer, Peer recipient, float progress);

        public void onTransferComplete(OutgoingTransfer transfer, Peer recipient, Exception exception);

    }

    public static interface AirShareReceiverCallback {

        public void onTransferOffered(IncomingTransfer transfer, Peer sender);

        public void onTransferProgress(IncomingTransfer transfer, Peer sender, float progress);

        public void onTransferComplete(IncomingTransfer transfer, Peer sender, Exception exception);

    }

    public static interface AirSharePeerCallback {

        public void peerStatusUpdated(Peer peer, Transport.ConnectionStatus newStatus);
    }

    private static SessionManager sessionManager;
    private AirShareReceiverCallback rCallback;
    private AirShareSenderCallback sCallback;
    private AirSharePeerCallback pCallback;
    private boolean activityRecevingMessages;
    private BidiMap<Peer, ArrayDeque<OutgoingTransfer>> outPeerTransfers = new DualHashBidiMap<>();
    private BidiMap<Peer, ArrayDeque<IncomingTransfer>> inPeerTransfers = new DualHashBidiMap<>();
    private Set<IncomingMessageListener> incomingMessageListeners = new HashSet<>();
    private Set<MessageDeliveryListener> messageDeliveryListeners = new HashSet<>();

    private ServiceBinder binder;

    private Looper backgroundLooper;
    private BackgroundThreadHandler backgroundHandler;
    private Handler foregroundHandler;

    /** Handler Messages */
    public static final int ADVERTISE     = 0;
    public static final int SCAN          = 1;
    public static final int SEND_MESSAGE  = 2;
    public static final int SHUTDOWN      = 3;

    @Override
    public void onCreate() {
        Timber.d("onCreate");
        // Start up the thread running the service.  Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block.  We also make it
        // background priority so CPU-intensive work will not disrupt our UI.
        HandlerThread thread = new HandlerThread("AirShareService", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();

        // Get the HandlerThread's Looper and use it for our Handler
        backgroundLooper = thread.getLooper();
        backgroundHandler = new BackgroundThreadHandler(backgroundLooper);
        foregroundHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (binder == null) binder = new ServiceBinder();
        Timber.d("Bind service");
        return binder;
    }

    @Override
    public int onStartCommand (Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    /** ActivityReceivingMessagesIndicator */
    @Override
    public boolean isActivityReceivingMessages() {
        return activityRecevingMessages;
    }

    /** Binder through which Activities can interact with this Service */
    public class ServiceBinder extends Binder {

        public void registerLocalUserWithService(String userAlias, String serviceName) {
            KeyPair keyPair = SodiumShaker.generateKeyPair();
            LocalPeer localPeer = new LocalPeer(keyPair, userAlias);

            if (sessionManager != null) sessionManager.stop();

            sessionManager = new SessionManager(AirShareService.this, serviceName, localPeer, AirShareService.this);
        }

        public void advertiseLocalUser() {
            sessionManager.advertiseLocalPeer();
        }

        public void scanForOtherUsers() {
            sessionManager.scanForPeers();
        }

        public void stop() {
            sessionManager.stop();
        }

        public void setSenderCallback(AirShareSenderCallback callback) {
            sCallback = callback;
        }

        public void setReceiverCallback(AirShareReceiverCallback callback) {
            rCallback = callback;
        }

        public void setPeerCallback(AirSharePeerCallback callback) {
            pCallback = callback;
        }

        public void offer(File file, Peer recipient) throws FileNotFoundException {
            addOutgoingTransfer(new OutgoingTransfer(file, recipient, sessionManager));
        }

        public void offer(byte[] data, Peer recipient) {
            addOutgoingTransfer(new OutgoingTransfer(data, recipient, sessionManager));
        }

        /**
         * Set by Activity bound to this Service. If isActive is false, this Service
         * should post incoming messages as Notifications.
         */
        public void setActivityReceivingMessages(boolean receivingMessages) {
            activityRecevingMessages = receivingMessages;
        }
    }

    private void addIncomingTransfer(IncomingTransfer transfer) {
        Peer recipient = transfer.getSender();

        incomingMessageListeners.add(transfer);
        messageDeliveryListeners.add(transfer);

        if (!inPeerTransfers.containsKey(recipient))
            inPeerTransfers.put(recipient, new ArrayDeque<IncomingTransfer>());

        inPeerTransfers.get(recipient).add(transfer);
    }

    private void addOutgoingTransfer(OutgoingTransfer transfer) {
        Peer recipient = transfer.getRecipient();

        incomingMessageListeners.add(transfer);
        messageDeliveryListeners.add(transfer);

        if (!outPeerTransfers.containsKey(recipient))
            outPeerTransfers.put(recipient, new ArrayDeque<OutgoingTransfer>());

        outPeerTransfers.get(recipient).add(transfer);
    }

    /** Handler that processes Messages on a background thread */
    private final class BackgroundThreadHandler extends Handler {
        public BackgroundThreadHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
//            switch (msg.what) {
//                case ADVERTISE:
//                    Log.i(TAG, "handling connect");
//                    sessionManager.advertiseLocalPeer();
//                    break;
//                case SEND_MESSAGEE:
//                    mApp.sendPublicMessageFromPrimaryIdentity((String) msg.obj);
//                    break;
//                case SHUTDOWN:
//                    Log.i(TAG, "handling shutdown");
//                    mApp.makeUnavailable();
//
//                    // Stop the service using the startId, so that we don't stop
//                    // the service in the middle of handling another job
//                    stopSelf(msg.arg1);
//                    break;
//            }
        }
    }

    private @Nullable IncomingTransfer getIncomingTransferForFileTransferMessage(FileTransferMessage fileMessage,
                                                                                 Peer sender) {

        IncomingTransfer incomingTransfer = null;
        for (IncomingTransfer transfer : inPeerTransfers.get(sender)) {
            if (transfer.getFilename().equals(fileMessage.getHeaders().get(FileTransferMessage.HEADER_FILENAME)))
                incomingTransfer = transfer;
        }

        return incomingTransfer;
    }

    private @Nullable OutgoingTransfer getOutgoingTransferForFileTransferMessage(FileTransferMessage fileMessage,
                                                                                 Peer recipient) {

        OutgoingTransfer outgoingTransfer = null;
        for (OutgoingTransfer transfer : outPeerTransfers.get(recipient)) {
            if (transfer.getFilename().equals(fileMessage.getHeaders().get(FileTransferMessage.HEADER_FILENAME)))
                outgoingTransfer = transfer;
        }

        return outgoingTransfer;
    }

    // <editor-fold desc="SessionManagerCallback">

    @Override
    public void peerStatusUpdated(Peer peer, Transport.ConnectionStatus newStatus) {

        if (pCallback != null)
            pCallback.peerStatusUpdated(peer, newStatus);

    }

    @Override
    public void messageReceivingFromPeer(SessionMessage message, Peer recipient, float progress) {
        if (message.getType().equals(FileTransferMessage.HEADER_TYPE_TRANSFER)) {

            FileTransferMessage fileMessage = (FileTransferMessage) message;
            IncomingTransfer incomingTransfer = getIncomingTransferForFileTransferMessage(fileMessage, recipient);

            if (incomingTransfer == null) {
                Timber.w("No OutgoingTransfer for outgoing FileTransferMessage");
            }

            if (rCallback != null)
                rCallback.onTransferProgress(incomingTransfer, recipient, progress);
        }
    }

    @Override
    public void messageReceivedFromPeer(SessionMessage message, Peer sender) {

        Iterator<IncomingMessageListener> iterator = incomingMessageListeners.iterator();
        IncomingMessageListener listener;

        while (iterator.hasNext()) {
            listener = iterator.next();

            if (!listener.onMessageReceived(message, sender))
                iterator.remove();

        }

            IncomingTransfer incomingTransfer = null;
            switch(message.getType()) {

                case FileTransferMessage.HEADER_TYPE_OFFER:
                    // An incoming transfer offer
                    incomingTransfer = new IncomingTransfer((FileTransferMessage) message, sender, sessionManager);

                    addIncomingTransfer(incomingTransfer);

                    if (rCallback != null) rCallback.onTransferOffered(incomingTransfer, sender);
                    break;

                case FileTransferMessage.HEADER_TYPE_ACCEPT:
                    // An Acceptance was received that wasn't intercepted by a corresponding offer
                    OutgoingTransfer outgoingTransfer = getOutgoingTransferForFileTransferMessage((FileTransferMessage) message, sender);

                    if (outgoingTransfer == null) {
                        Timber.w("Received accept filetransfermessage but no corresponding OutgoingTransfer registered");
                        break;
                    }

                    // TODO : When we implement decline messages, check for accept / decline header
                    if (sCallback != null) sCallback.onTransferOfferResponse(outgoingTransfer, sender, true);

                    break;

                case FileTransferMessage.HEADER_TYPE_TRANSFER:
                    // An incoming transfer

                    incomingTransfer = getIncomingTransferForFileTransferMessage((FileTransferMessage) message, sender);

                    if (incomingTransfer == null) {
                        Timber.w("Received transfer filetransfermessage but no corresponding incomingtransfer registered");
                        break;
                    }

                    if (rCallback != null) rCallback.onTransferComplete(incomingTransfer, sender, null);

                    break;
            }
    }

    @Override
    public void messageSendingToPeer(SessionMessage message, Peer recipient, float progress) {

        if (message.getType().equals(FileTransferMessage.HEADER_TYPE_TRANSFER)) {

            FileTransferMessage fileMessage = (FileTransferMessage) message;
            OutgoingTransfer outgoingTransfer = getOutgoingTransferForFileTransferMessage(fileMessage, recipient);

            if (outgoingTransfer == null) {
                Timber.w("No OutgoingTransfer for outgoing FileTransferMessage");
            }

            if (sCallback != null)
                sCallback.onTransferProgress(outgoingTransfer, recipient, progress);
        }
    }

    @Override
    public void messageSentToPeer(SessionMessage message, Peer recipient, Exception exception) {

        Iterator<MessageDeliveryListener> iterator = messageDeliveryListeners.iterator();
        MessageDeliveryListener listener;

        while (iterator.hasNext()) {
            listener = iterator.next();

            if (!listener.onMessageDelivered(message, recipient, exception))
                iterator.remove();
        }

        switch (message.getType()) {
            case FileTransferMessage.HEADER_TYPE_TRANSFER:

                OutgoingTransfer outgoingTransfer = getOutgoingTransferForFileTransferMessage((FileTransferMessage) message, recipient);

                if (outgoingTransfer == null) {
                    Timber.w("Sent transfer FileTransferMessage but no OutgoingTransfer registered");
                    return;
                }

                if (sCallback != null) sCallback.onTransferComplete(outgoingTransfer, recipient, null);
                break;
        }
    }

    // </editor-fold desc="SessionManagerCallback">
}