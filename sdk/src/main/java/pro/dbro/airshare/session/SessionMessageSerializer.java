package pro.dbro.airshare.session;

import android.support.annotation.Nullable;
import android.util.Pair;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

/**
 * This class facilitates queuing {@link pro.dbro.airshare.session.SessionMessage}s
 * for sequential serialization
 *
 * Created by davidbrodsky on 3/12/15.
 */
public class SessionMessageSerializer {

    private ArrayList<Pair<Integer, SessionMessage>> completedMessages;
    private ArrayDeque<SessionMessage> messages;
    private int marker;
    private int serializeCount;
    private int ackCount;

    public SessionMessageSerializer(final SessionMessage message) {
        this(new ArrayList<SessionMessage>() {{ add(message); }});
    }

    public SessionMessageSerializer(List<SessionMessage> messages) {
        this.messages = new ArrayDeque<>();
        this.messages.addAll(messages);
        this.completedMessages = new ArrayList<>();
        marker = 0;
        serializeCount = 0;
        ackCount = 0;
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
     */
    public byte[] serializeNextChunk(int length) {
        if (messages.size() == 0) return null;
        length = Math.min(length, 500 * 1024);
        byte[] result = messages.peek().serialize(marker, length);

        if (result == null) {
            Timber.d("Completed %s message", messages.peek().getType());
            completedMessages.add(new Pair<>(serializeCount, messages.poll()));
            marker = 0;
            return serializeNextChunk(length);

        } else {
            marker += result.length;
            serializeCount++;
            Timber.d("serializeNextChunk");
        }

        return result;
    }

    /**
     * @return a Pair containing the message delivery progress and the
     * {@link pro.dbro.airshare.session.SessionMessage} corresponding
     * to the chunk being acknowledged. Assumes sequential delivery of chunks returned
     * by {@link #serializeNextChunk(int)}
     */
    public @Nullable Pair<SessionMessage, Float> ackChunkDelivery() {
        ackCount++;
        Timber.d("Ack");
        SessionMessage message = null;
        float progress = 0;

        for (Pair<Integer, SessionMessage> messagePair : completedMessages) {
            if (messagePair.first >= ackCount) {
                message = messagePair.second;
                progress = ((float) ackCount) / messagePair.first;
                Timber.d("ackChunkDelivery reporting prev msg progress %f", progress);
            }
        }

        if (message == null) {
            message = messages.peek();
            progress = getCurrentMessageProgress();
            Timber.d("ackChunkDelivery reporting current progress %f", progress);
        }

        if (message == null) return null;

        return new Pair<>(message, progress);
    }

}
