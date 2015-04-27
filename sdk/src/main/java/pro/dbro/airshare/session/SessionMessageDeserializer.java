package pro.dbro.airshare.session;

import android.content.Context;
import android.support.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import timber.log.Timber;

/**
 * This class facilitates deserializing a {@link pro.dbro.airshare.session.SessionMessage} from
 * in-order data streams.
 *
 * After construction, call {@link #dataReceived(byte[])} as data arrives and await
 * notification of deserialization events via the
 * {@link pro.dbro.airshare.session.SessionMessageDeserializer.SessionMessageDeserializerCallback}
 * passed to the constructor.
 *
 * This class assumes contiguous serialized SessionMessage chunks will be delivered in-order and
 * that any discontinuities in the data stream will be reported by the client of this class via
 * {@link #reset()}. A call to {@link #reset()} will result in the loss of the partially accumulated
 * SessionMessage.
 *
 * Created by davidbrodsky on 2/24/15.
 */
public class SessionMessageDeserializer {

    public static interface SessionMessageDeserializerCallback {

        public void onHeaderReady(SessionMessageDeserializer receiver, SessionMessage message);

        public void onBodyProgress(SessionMessageDeserializer receiver, SessionMessage message, float progress);

        public void onComplete(SessionMessageDeserializer receiver, SessionMessage message, Exception e);

    }

    /** Bodies over this size will be stored on disk */
    private static final int BODY_SIZE_CUTOFF_BYTES = 2 * 1000 * 1000; // 2 MB

    private Context                            context;
    private ByteBuffer                         buffer;
    private SessionMessageDeserializerCallback callback;
    private File                               bodyFile;
    private OutputStream                       bodyStream;
    private HashMap<String, Object>            headers;
    private ByteBuffer                         headerLengthBuffer;
    private SessionMessage                     sessionMessage;

    private boolean gotVersion;
    private boolean gotHeaderLength;
    private boolean gotHeader;
    private boolean gotBody;
    private boolean gotBodyBoundary;

    private int headerLength;
    private int bodyLength;
    private int bodyBytesReceived;

    public SessionMessageDeserializer(Context context, SessionMessageDeserializerCallback callback) {
        buffer = ByteBuffer.allocate(5 * 1000);
        this.callback = callback;
        this.context = context;

        init();
    }

    /**
     * Reset the state of the receiver in preparation for a new SessionMessage, losing any
     * partially accumulated SessionMessage. Call this if the incoming data stream is interrupted
     * and not expected to be immediately resumed.
     * e.g: the source of incoming data becomes unavailable.
     */
    public void reset() {
        gotVersion      = false;
        gotHeaderLength = false;
        gotHeader       = false;
        gotBody         = false;
        gotBodyBoundary = false;

        headerLength      = 0;
        bodyLength        = 0;
        bodyBytesReceived = 0;

        buffer.clear();

        bodyFile = null;

        if (bodyStream != null) {
            try { bodyStream.close(); } catch (IOException e) { e.printStackTrace(); }
            bodyStream = null;
        }
    }

    /**
     * Process sequential chunk of a serialized {@link pro.dbro.airshare.session.SessionMessage}
     *
     * This method will call {@link #reset()} internally if data provided completes a SessionMessage.
     *
     * @param data sequential chunk of a serialized {@link pro.dbro.airshare.session.SessionMessage}
     */
    public void dataReceived(byte[] data) {
        int dataBytesProcessed = 0;

        if (data.length > buffer.capacity() - buffer.position())
            resizeBuffer(data.length);

        try {

            /** Write incoming data to memory buffer if accumulated bytes received (since construction
             * or call to {@link #reset()}) indicates we are still receiving the SessionMessage prefix
             * or header. If accumulated bytes received indicates we are receiving body, write to body OutputStream
             */
            if (gotHeaderLength && buffer.position() >= getPrefixAndHeaderLengthBytes()) {

                if (bodyLength > BODY_SIZE_CUTOFF_BYTES) {

                    if (bodyStream == null) prepareBodyOutputStream();

                    bodyStream.write(data);
                } else
                    buffer.put(data);

                bodyBytesReceived += data.length;

                if (callback != null && bodyLength > 0)
                    callback.onBodyProgress(this, sessionMessage, bodyBytesReceived / (float) bodyLength);
            }
            else {
                buffer.put(data);
            }

        } catch (IOException e) {
            Timber.e(e, "Failed to write data to body outputStream");
        }


        /** Deserialize SessionMessage Header version byte, if not yet done since construction
         * or last call to {@link #reset()}
         */
        if (!gotVersion && buffer.position() >= SessionMessage.HEADER_VERSION_BYTES) {
            // Get version int from first byte
            // Check we can deserialize this version
            int version = new BigInteger(new byte[] { buffer.get(0) }).intValue();
            Timber.d("Deserialized header version " + version);
            if (version != SessionMessage.CURRENT_HEADER_VERSION) {
                Timber.e("Unknown SessionMessage version");
                if (callback != null)
                    callback.onComplete(this, null, new UnsupportedOperationException("Unknown SessionMessage version " + version));
                return;
            }
            dataBytesProcessed += SessionMessage.HEADER_VERSION_BYTES;
            gotVersion = true;
        }

        /** Deserialize SessionMessage Header length bytes, if not yet done since construction
         * or last call to {@link #reset()}
         */
        if (!gotHeaderLength && buffer.position() >= SessionMessage.HEADER_VERSION_BYTES +
                                                     SessionMessage.HEADER_LENGTH_BYTES) {

            // Get header length and store. Deserialize header when possible
            byte[] headerLengthBytes = new byte[SessionMessage.HEADER_LENGTH_BYTES];
            int originalPosition = buffer.position();
            buffer.position(dataBytesProcessed);
            buffer.get(headerLengthBytes, 0, headerLengthBytes.length);
            buffer.position(originalPosition);

            headerLengthBuffer.clear();
            headerLengthBuffer.put(headerLengthBytes);
            headerLengthBuffer.rewind();

            headerLength = headerLengthBuffer.getInt();
            Timber.d("Deserialized header length " + headerLength);
            gotHeaderLength = true;
            dataBytesProcessed += SessionMessage.HEADER_LENGTH_BYTES;
        }

        /** Deserialize SessionMessage Header content, if not yet done since construction
         * or last call to {@link #reset()}
         */
        if (!gotHeader && gotHeaderLength && buffer.position() >= getPrefixAndHeaderLengthBytes()) {

            byte[] headerString = new byte[headerLength];
            int originalBufferPosition = buffer.position();
            buffer.position(SessionMessage.HEADER_VERSION_BYTES + SessionMessage.HEADER_LENGTH_BYTES);
            buffer.get(headerString, 0, headerLength);
            buffer.position(originalBufferPosition);

            try {
                JSONObject jsonHeader = new JSONObject(new String(headerString, "UTF-8"));
                headers = toMap(jsonHeader);
                bodyLength = (int) headers.get(SessionMessage.HEADER_BODY_LENGTH);
                sessionMessage = sessionMessageFromHeaders(headers);
                Timber.d(String.format("Deserialized %s header indicating body length %d", (String) headers.get(SessionMessage.HEADER_TYPE), (int) headers.get(SessionMessage.HEADER_BODY_LENGTH)));
                if (sessionMessage != null && callback != null)
                    callback.onHeaderReady(this, sessionMessage);
            } catch (JSONException | UnsupportedEncodingException e) {
                // TODO : We should reset or otherwise abort this message
                e.printStackTrace();
            }

            gotHeader = true;
            dataBytesProcessed += headerLength;
        }
//        else if (!gotHeader)
//            Timber.d(String.format("Got %d / %d header bytes", buffer.position(), SessionMessage.HEADER_VERSION_BYTES + SessionMessage.HEADER_LENGTH_BYTES + headerLength));

        /** If the boundary between header content and body content occurred within {@link data},
         * update {@link #bodyBytesReceived} appropriately. Additionally, if this SessionMessage
         * requires off-memory body storage we remove body data from {@link buffer} and insert it into
         * the {@link bodyStream}. Must be performed before determining body completion.
         *
         * Performed at most once per SessionMessage
         */
        if (!gotBodyBoundary &&
            gotHeader        &&
            bodyLength > 0   &&
            buffer.position() >= getPrefixAndHeaderLengthBytes()) {

            try {
                int bodyBytesJustReceived = data.length - dataBytesProcessed;

                if (bodyLength > BODY_SIZE_CUTOFF_BYTES) {

                    if (bodyStream == null) prepareBodyOutputStream();

                    byte[] bodyBytes = new byte[bodyBytesJustReceived];
                    buffer.position(buffer.position() - bodyBytesJustReceived);
                    buffer.get(bodyBytes, 0, bodyBytesJustReceived);
                    buffer.position(buffer.position() - bodyBytesJustReceived);
                    bodyStream.write(bodyBytes, 0, bodyBytesJustReceived);
                }

                bodyBytesReceived += bodyBytesJustReceived;

                if (callback != null && bodyLength > 0)
                    callback.onBodyProgress(this, sessionMessage, bodyBytesReceived / (float) bodyLength);

                Timber.d(String.format("Splitting received data between header (%d bytes) and body (%d bytes)", dataBytesProcessed, bodyBytesJustReceived));
                gotBodyBoundary = true;

            } catch (IOException e) {
                Timber.d(e, "IOException");
            }
        }

        /** Construct and deliver complete SessionMessage if deserialized header and body are received */
        if (gotHeader && !gotBody && bodyBytesReceived == bodyLength) {

            Timber.d("Got body!");
            // Construct appropriate SessionMessage or child object
            if (bodyLength > BODY_SIZE_CUTOFF_BYTES) {

                if (sessionMessage instanceof DataTransferMessage) {
                    // TODO This shouldn't happen. We should enforce an upper limit on DataTransferMessage
                    throw new UnsupportedOperationException("Cannot have a disk-backed DataTransferMessage");
                }
            } else {
                byte[] body = new byte[bodyLength];
                buffer.position(getPrefixAndHeaderLengthBytes());
                buffer.get(body, 0, bodyLength);

                if (sessionMessage instanceof DataTransferMessage) {
                    ((DataTransferMessage) sessionMessage).setBody(body);
                }
            }

            if (callback != null) callback.onComplete(this, sessionMessage, null);

            gotBody = true;

            // Prepare for next incoming message
            reset();
        }
//        else if (!gotBody && gotHeader) {
//            Timber.d(String.format("Read %d / %d body bytes", bodyBytesReceived, bodyLength));
//        }
    }

    private void init() {
        headerLengthBuffer = ByteBuffer.allocate(Integer.SIZE / 8).order(ByteOrder.LITTLE_ENDIAN);
    }

    private void resizeBuffer(int minLength) {
        ByteBuffer newBuffer = ByteBuffer.allocate(Math.max(minLength, (int) (buffer.capacity() * 1.5)));
        buffer.limit(buffer.position());
        buffer.position(0);
        newBuffer.put(buffer);
        buffer = newBuffer;
    }

    private void prepareBodyOutputStream() {
        bodyFile = new File(context.getExternalFilesDir(null), UUID.randomUUID().toString().replace("-","") + ".body");
        try {
            bodyStream = new FileOutputStream(bodyFile);
        } catch (FileNotFoundException e) {
            String msg = "Failed to open body File: " + bodyFile.getAbsolutePath();
            Timber.e(e, msg);
            throw new IllegalArgumentException(msg);
        }
    }

    private float getCurrentMessageProgress() {
        if (bodyLength == 0) return 0;
        return bodyBytesReceived / (float) bodyLength;
    }

    /**
     * @return the number of bytes which the prefix and header occupy
     * This method only returns a valid value if {@link #gotHeaderLength} is {@code true}
     */
    private int getPrefixAndHeaderLengthBytes() {
        return SessionMessage.HEADER_VERSION_BYTES + SessionMessage.HEADER_LENGTH_BYTES + headerLength;
    }

    private static @Nullable SessionMessage sessionMessageFromHeaders(HashMap<String, Object> headers) {
        if (!headers.containsKey(SessionMessage.HEADER_TYPE))
            throw new IllegalArgumentException("headers map must have 'type' entry");

        final String headerType = (String) headers.get(SessionMessage.HEADER_TYPE);
        switch(headerType) {
            case IdentityMessage.HEADER_TYPE:
                return IdentityMessage.fromHeaders(headers);

            case TransportUpgradeMessage.HEADER_TYPE:
                return new TransportUpgradeMessage(headers);

            case DataTransferMessage.HEADER_TYPE:
                return new DataTransferMessage(headers, null);

            default:
                Timber.w("Unable to deserialize %s message", headerType);
                return null;

        }
    }

    // <editor-fold desc="JSON Utils">

    public static HashMap<String, Object> toMap(JSONObject object) throws JSONException {
        HashMap<String, Object> map = new HashMap<>();
        Iterator keys = object.keys();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            map.put(key, fromJson(object.get(key)));
        }
        return map;
    }

    private static Object fromJson(Object json) throws JSONException {
        if (json == JSONObject.NULL) {
            return null;
        } else if (json instanceof JSONObject) {
            return toMap((JSONObject) json);
        } else if (json instanceof JSONArray) {
            return toList((JSONArray) json);
        } else {
            return json;
        }
    }

    public static List toList(JSONArray array) throws JSONException {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            list.add(fromJson(array.get(i)));
        }
        return list;
    }

    // </editor-fold desc="JSON Utils">
}
