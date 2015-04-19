package pro.dbro.airshare.app;

import pro.dbro.airshare.session.DataTransferMessage;
import pro.dbro.airshare.session.Peer;
import pro.dbro.airshare.session.SessionMessage;
import pro.dbro.airshare.session.SessionMessageScheduler;

/**
 * An OutgoingTransfer wraps an outgoing data transfer.
 *
 * 1. Constructed with a byte[]
 * 2. Sends a DataTransferMessage
 *
 * Created by davidbrodsky on 3/13/15.
 */
public class OutgoingTransfer extends Transfer implements IncomingMessageListener, MessageDeliveryListener {

    public static enum State {

        /** Awaiting data transfer delivery" */
        AWAITING_DATA_ACK,

        /** Transfer completed */
        COMPLETE
    }

    private Peer recipient;
    private SessionMessageScheduler messageSender;
    private State state;

    // <editor-fold desc="Outgoing Constructors">

    public OutgoingTransfer(byte[] data,
                            Peer recipient,
                            SessionMessageScheduler messageSender) {

        init(recipient, messageSender);

        transferMessage = DataTransferMessage.createOutgoing(null, data);
        messageSender.sendMessage(transferMessage, recipient);

        state = State.AWAITING_DATA_ACK;
    }


    // </editor-fold desc="Outgoing Constructors">

    private void init(Peer recipient, SessionMessageScheduler sender) {
        this.recipient = recipient;
        this.messageSender = sender;
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
        return false;
    }

    // </editor-fold desc="IncomingMessageInterceptor">

    // <editor-fold desc="MessageDeliveryListener">

    @Override
    public boolean onMessageDelivered(SessionMessage message, Peer recipient, Exception exception) {

        if (state == State.AWAITING_DATA_ACK && transferMessage != null && message.equals(transferMessage)) {

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
