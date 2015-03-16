package pro.dbro.airshare.session;

/**
 * An item that schedules {@link pro.dbro.airshare.session.SessionMessage}s for delivery
 * to a {@link pro.dbro.airshare.session.Peer}
 *
 * Created by davidbrodsky on 3/14/15.
 */
public interface SessionMessageScheduler {

    public void sendMessage(SessionMessage message, Peer recipient);

}
