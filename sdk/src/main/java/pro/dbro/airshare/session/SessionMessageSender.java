package pro.dbro.airshare.session;

import android.support.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by davidbrodsky on 3/12/15.
 */
public class SessionMessageSender {

    private ArrayDeque<SessionMessage> messages;
    private int marker;

    public SessionMessageSender(final SessionMessage message) {
        this(new ArrayList<SessionMessage>() {{ add(message); }});
    }

    public SessionMessageSender(List<SessionMessage> messages) {
        this.messages = new ArrayDeque<>();
        this.messages.addAll(messages);
        marker = 0;
    }

    public @Nullable SessionMessage getCurrentMessage() {
        return messages.peek();
    }

    public void queueMessage(SessionMessage message) {
        messages.offer(message);
    }

    public float getCurrentMessageProgress() {
        if (getCurrentMessage() == null) return 1;

        return ((float)marker) / getCurrentMessage().getTotalLengthBytes();
    }

    /**
     * Read up to length bytes of the current outgoing SessionMessage.
     * If length is 0, a fixed memory-safe size will be read.
     */
    public byte[] readNextChunk(int length) {
        if (messages.size() == 0) return null;
        length = Math.min(length, 500 * 1024);

        byte[] result = messages.peek().serialize(marker, length);

        if (result == null) {

            messages.poll();
            marker = 0;
            return readNextChunk(length);

        } else {
            marker += result.length;
        }

        return result;
    }

}
