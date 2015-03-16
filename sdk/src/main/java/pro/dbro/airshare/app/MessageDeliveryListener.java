package pro.dbro.airshare.app;

import pro.dbro.airshare.session.Peer;
import pro.dbro.airshare.session.SessionMessage;

/**
 * An item that listens for outgoing {@link pro.dbro.airshare.session.SessionMessage} delivery events.
 *
 * Created by davidbrodsky on 3/14/15.
 */
public interface MessageDeliveryListener {

    /**
     * Called whenever an outgoing message is delivered.
     *
     * @return true if this listener should continue receiving delivery events. if false
     * the listener will not receive further events.
     *
     */
    public boolean onMessageDelivered(SessionMessage message, Peer recipient, Exception exception);
}
