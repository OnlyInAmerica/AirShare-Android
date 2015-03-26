package pro.dbro.airshare.app;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Map;

import pro.dbro.airshare.session.DataTransferMessage;
import pro.dbro.airshare.session.FileTransferMessage;
import pro.dbro.airshare.session.Peer;
import pro.dbro.airshare.session.SessionMessage;
import pro.dbro.airshare.session.SessionMessageScheduler;
import timber.log.Timber;

/**
 * An OutgoingTransfer abstracts the sending of a large disk-based asset transfer, which
 * requires the recipient's acceptance, or a small memory-based asset transfer.
 *
 * 1. Constructed with a File or InputStream
 * 2. Sends an "offer" FileTransferMessage.
 * 3. Listens for "accept" FileTransferMessage
 * 4. Send "transfer" FileTransferMessage.
 *
 * 1. Constructed with a byte[]
 * 2. Sends a DataTransferMessage
 *
 * Created by davidbrodsky on 3/13/15.
 */
public class OutgoingTransfer extends Transfer implements IncomingMessageListener, MessageDeliveryListener {

    public static enum State {

        /** If un-accepted data message, awaiting data transfer delivery" */
        AWAITING_DATA_ACK,

        /** Awaiting "offer" delivery */
        AWAITING_OFFER_ACK,

        /** Awaiting incoming offer "accept" message  */
        AWAITING_ACCEPT,

        /** Awaiting "transfer" delivery */
        AWAITING_TRANSFER_ACK,

        /** Transfer completed */
        COMPLETE
    }

    private Peer recipient;
    private SessionMessageScheduler messageSender;
    private State state;

    private SessionMessage offerMessage;

    // <editor-fold desc="Outgoing Constructors">

    public OutgoingTransfer(@Nullable Map<String, Object> headers,
                            @Nullable byte[] data, Peer recipient,
                            @NonNull SessionMessageScheduler messageSender) {

        init(recipient, messageSender);

        transferMessage = DataTransferMessage.createOutgoing(headers, data);
        messageSender.sendMessage(transferMessage, recipient);

        state = State.AWAITING_DATA_ACK;
    }

    public OutgoingTransfer(byte[] data,
                            Peer recipient,
                            SessionMessageScheduler messageSender) {

        init(recipient, messageSender);

        transferMessage = DataTransferMessage.createOutgoing(null, data);
        messageSender.sendMessage(transferMessage, recipient);

        state = State.AWAITING_DATA_ACK;
    }

    public OutgoingTransfer(File file,
                            Peer recipient,
                            SessionMessageScheduler messageSender)
                            throws FileNotFoundException {

        init(recipient, messageSender);

        offerMessage = new FileTransferMessage(file, FileTransferMessage.TransferType.OFFER);
        transferMessage = new FileTransferMessage(file, FileTransferMessage.TransferType.TRANSFER);

        messageSender.sendMessage(offerMessage, recipient);

        state = State.AWAITING_OFFER_ACK;
    }

    public OutgoingTransfer(InputStream inputStream,
                            String filename,
                            int lengthBytes,
                            Peer recipient,
                            SessionMessageScheduler messageSender)

                            throws FileNotFoundException {

        init(recipient, messageSender);

        offerMessage = new FileTransferMessage(inputStream, filename, lengthBytes, FileTransferMessage.TransferType.OFFER);
        transferMessage = new FileTransferMessage(inputStream, filename, lengthBytes, FileTransferMessage.TransferType.TRANSFER);

        messageSender.sendMessage(offerMessage, recipient);

        state = State.AWAITING_OFFER_ACK;
    }

    // </editor-fold desc="Outgoing Constructors">

    private void init(Peer recipient, SessionMessageScheduler sender) {
        this.recipient = recipient;
        this.messageSender = sender;
    }

    public String getFilename() {
        return (String) offerMessage.getHeaders().get(FileTransferMessage.HEADER_FILENAME);
    }

    public String getTransferId() {
        if (transferMessage == null) return null;
        return (String) transferMessage.getHeaders().get(SessionMessage.HEADER_ID);
    }

    public Peer getRecipient() {
        return recipient;
    }

    @Override
    public boolean onMessageReceived(SessionMessage message, Peer recipient) {
        if (state == State.AWAITING_ACCEPT) {
            Timber.d("Got filetransfer accept!");
            FileTransferMessage fileTransferMessage = (FileTransferMessage) message;

            // TODO : Need a FileTransferRequest id. Something unique per offer-accept-transfer group
            // filename works for now, but should be replaced
            if (fileTransferMessage.getType().equals(FileTransferMessage.HEADER_TYPE_ACCEPT) &&
                offerMessage != null &&
                fileTransferMessage.getHeaders().get(FileTransferMessage.HEADER_FILENAME)
                    .equals(offerMessage.getHeaders().get(FileTransferMessage.HEADER_FILENAME))) {

                Timber.d("Sending transfer");
                messageSender.sendMessage(transferMessage, recipient);

                state = State.AWAITING_TRANSFER_ACK;

                return false;
            }
        }

        return true;
    }

    // </editor-fold desc="IncomingMessageInterceptor">

    // <editor-fold desc="MessageDeliveryListener">

    @Override
    public boolean onMessageDelivered(SessionMessage message, Peer recipient, Exception exception) {

        if (state == State.AWAITING_OFFER_ACK && offerMessage != null && message.equals(offerMessage)) {

            state = State.AWAITING_ACCEPT;
        }
        else if ((state == State.AWAITING_DATA_ACK || state == State.AWAITING_TRANSFER_ACK) &&
                 transferMessage != null && message.equals(transferMessage)) {

            state = State.COMPLETE;
            return false;
        }

        return true;
    }

    // </editor-fold desc="MessageDeliveryListener">

    @Override
    public boolean isComplete() {
        return state == State.COMPLETE;
    }
}
