package pro.dbro.airshare.session;

import android.support.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * This class facilitates queuing {@link pro.dbro.airshare.session.SessionMessage}s
 * for sequential serialization
 *
 * Created by davidbrodsky on 3/12/15.
 */
public class SessionMessageSerializer {

    private ArrayDeque<SessionMessage> messages;
    private int marker;

    public SessionMessageSerializer(final SessionMessage message) {
        this(new ArrayList<SessionMessage>() {{ add(message); }});
    }

    public SessionMessageSerializer(List<SessionMessage> messages) {
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
     *
     * If {@param length} extends beyond the bytes left in the current message,
     * the result will be a byte[] of lesser length containing the completion of the current message.
     * This is by design so that when confirmation of that final message chunk arrives, a call to
     * {@link #getCurrentMessage()} will report the corresponding message.
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
