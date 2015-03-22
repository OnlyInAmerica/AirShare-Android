package pro.dbro.airshare.app;

import pro.dbro.airshare.session.Peer;
import pro.dbro.airshare.session.SessionMessage;

/**
 * An item that listens for incoming {@link pro.dbro.airshare.session.SessionMessage}s
 *
 * Created by davidbrodsky on 3/13/15.
 */
public interface IncomingMessageListener {

    /**
     * Called whenever an incoming message is received.
     *
     * @return true if this interceptor should continue receiving messages. if false
     * the interceptor will not receive further events.
     *
     */
    public boolean onMessageReceived(SessionMessage message, Peer recipient);

}
