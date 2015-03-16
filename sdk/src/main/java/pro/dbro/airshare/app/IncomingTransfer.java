package pro.dbro.airshare.app;

import java.io.InputStream;

import pro.dbro.airshare.session.FileTransferMessage;
import pro.dbro.airshare.session.Peer;
import pro.dbro.airshare.session.SessionMessage;
import pro.dbro.airshare.session.SessionMessageScheduler;

/**
 * Facilitates responding to incoming transfer requests that require user acceptance to proceed.
 *
 * 1. Constructed with an "offer" FileTransferMessage
 * 2. Client must either call {@link #accept()} or {@link #decline()}
 * 3. If accepted, sends "accept" FileTransferMessage. If declined, sends "decline" FileTransferMessage
 * 4. If accepted, client will receive complete "transfer" FileTransferMessage
 *
 * 1. Constructed with a DataTransferMessage
 *
 * Created by davidbrodsky on 3/13/15.
 */
public class IncomingTransfer implements IncomingMessageListener, MessageDeliveryListener, Transfer {

    public static enum State {

        /** "offer" received. Awaiting user acceptance */
        AWAITING_USER_ACCEPT,

        /** awaiting delivery of "accept" message */
        AWAITING_ACCEPT_ACK,

        /** awaiting "transfer" message response to "accept" message */
        AWAITING_TRANSFER,

        /** "transfer" message received */
        COMPLETE
    }

    private Peer sender;
    private SessionMessageScheduler messageSender;
    private FileTransferMessage transferMessage;
    private FileTransferMessage messageAwaitingAccept;
    private SessionMessage messageAwaitingAck;
    private State state;


    // <editor-fold desc="Incoming Constructors">

    public IncomingTransfer(FileTransferMessage fileMessage, Peer sender, SessionMessageScheduler messageSender) {

        this.sender = sender;
        this.messageSender = messageSender;

        if (fileMessage.getType().equals(FileTransferMessage.HEADER_TYPE_OFFER)) {
            messageAwaitingAccept = fileMessage;
            state = State.AWAITING_USER_ACCEPT;
        } else
            throw new IllegalArgumentException("FileTransferMessage must be of offer type");
    }

    // </editor-fold desc="Incoming Constructors">

    public String getFilename() {
        return (String) messageAwaitingAccept.getHeaders().get(FileTransferMessage.HEADER_FILENAME);
    }

    public Peer getSender() {
        return sender;
    }

    public InputStream getBody() {
        return transferMessage.getBody();
    }

    public boolean isComplete() {
        return state == State.COMPLETE;
    }

    public boolean accept() {
        if (state != State.AWAITING_USER_ACCEPT) return false;

        messageAwaitingAck = FileTransferMessage.createAcceptForOffer(messageAwaitingAccept);
        messageSender.sendMessage(messageAwaitingAck, sender);

        state = State.AWAITING_ACCEPT_ACK;

        return true;
    }

    public boolean decline() {
        if (state != State.AWAITING_USER_ACCEPT) return false;

        // TODO : Send decline message

        return true;
    }

    // <editor-fold desc="IncomingMessageInterceptor">

    @Override
    public boolean onMessageReceived(SessionMessage message, Peer recipient) {
        if (state == State.AWAITING_TRANSFER &&
            message.getType().equals(FileTransferMessage.HEADER_TYPE_TRANSFER)) {

            FileTransferMessage fileTransferMessage = (FileTransferMessage) message;

            if (fileTransferMessage.getHeaders().get(FileTransferMessage.HEADER_FILENAME)
                    .equals(messageAwaitingAck.getHeaders().get(FileTransferMessage.HEADER_FILENAME))) {

                state = State.COMPLETE;

                return false;
            }
        }

        return true;
    }

    // </editor-fold desc="IncomingMessageInterceptor">

    // <editor-fold desc="MessageDeliveryListener">

    @Override
    public boolean onMessageDelivered(SessionMessage message, Peer recipient, Exception exception) {

        if (state == State.AWAITING_ACCEPT_ACK && messageAwaitingAck != null && messageAwaitingAck.equals(message)) {

            state = State.AWAITING_TRANSFER;

            return false;
        }

        return true;
    }

    // </editor-fold desc="MessageDeliveryListener">
}
