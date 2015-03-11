package pro.dbro.airshare.session;

import android.support.annotation.Nullable;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;

import timber.log.Timber;

/**
 * Represents a Session segment suitable for transport via a {@link pro.dbro.airshare.transport.Transport}
 *
 * Note: Headers are intended to be immutable and so {@link #populateHeaders()}
 * will only be called once across multiple serializations. Child classes must call
 * {@link #serializeAndCacheHeaders()} before {@link #serialize(int, int)} is invoked, ideally
 * within their constructors.
 *
 * Created by davidbrodsky on 2/22/15.
 */
public abstract class SessionMessage {

    /** SessionMessage version. Must be representable by {@link #HEADER_VERSION_BYTES} bytes */
    public static final int CURRENT_HEADER_VERSION = 1;

    /** Leading byte specifies header format version */
    public static final int HEADER_VERSION_BYTES   = 1;

    /** Next bytes specify header size in bytes as uint16. Max header size: 65.535 kB */
    public static final int HEADER_LENGTH_BYTES    = 2;

    /** Required header map keys */
    public static final String HEADER_TYPE         = "type";
    public static final String HEADER_BODY_LENGTH  = "body-length";
    public static final String HEADER_ID           = "id";

    protected int    version;
    protected String type;
    protected int    bodyLengthBytes;
    protected String id;
    protected HashMap<String, Object> headers;
    private   byte[] serializedHeaders;

    /**
     * Construct a SessionMessage with a given id.
     * This constructor should be used for deserialization of
     * incoming SessionMessages.
     */
    public SessionMessage(String id) {
        type = getClass().getSimpleName();
        bodyLengthBytes = 0;
        version = CURRENT_HEADER_VERSION;
        this.id = id;

        // Child classes should call serializeAndCacheHeaders()
        // in their constructors or else before serialize(..)
    }

    /**
     * Construct a new SessionMessage with a unique identifier.
     * This constructor should be used for creating new outgoing SessionMessages
     */
    public SessionMessage() {
        this(UUID.randomUUID().toString().substring(28));
    }


    /**
     * @return a HashMap representation of the message headers.
     * This method will be called once, and so it should not
     * rely on state that cannot be represented on construction.
     */
    protected HashMap<String, Object> populateHeaders() {
        HashMap<String, Object> headerMap = new HashMap<>();
        headerMap.put(HEADER_TYPE,    type);
        headerMap.put(HEADER_BODY_LENGTH, bodyLengthBytes);
        headerMap.put(HEADER_ID,      id);
        return headerMap;
    }

    public String getType() {
        return type;
    }

    /**
     * @return the length of the serialized headers in bytes, or -1 if not
     * yet known. See {@link #serializeAndCacheHeaders()}
     */
    public int getHeaderLengthBytes() {
        return serializedHeaders == null ? -1 : serializedHeaders.length;
    }

    public HashMap<String, Object> getHeaders() {
        return headers;
    }

    /**
     * @return the length of the blob body in bytes
     */
    public long getBodyLengthBytes() {
        return bodyLengthBytes;
    }

    public abstract @Nullable byte[] getBodyAtOffset(int offset, int length);

    /**
     * Serialize this SessionMessage for transport. Note that when the returned byte[]
     * has length less than given length or is null (data ended precisely on the last call),
     * serialization is complete.
     *
     * The general format of the serialized bytstream:
     *
     * byte idx | description
     * ---------|------------
     * [0]      | SessionMessage version
     * [1-2]    | Header length
     * [2-X]    | Header JSON. 'X' is value specified by Header length
     * [X-Y]    | Body. 'Y' is value specified in 'body-length' entry of Header JSON.
     *
     * @param length should never be less than {@link #HEADER_LENGTH_BYTES} + {@link #HEADER_VERSION_BYTES}
     *
     */
    public @Nullable byte[] serialize(int offset, int length) {
        if (offset < 0)
            throw new IllegalArgumentException("Serialization offset may not be negative");

        if (serializedHeaders == null)
            throw new IllegalStateException("Must call serializeAndCacheHeaders() before serialization");

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            int bytesWritten = 0;

            // Write SessionMessage header version if offset dictates
            if (offset + bytesWritten < HEADER_LENGTH_BYTES) {

                outputStream.write((byte) CURRENT_HEADER_VERSION);

                bytesWritten += HEADER_VERSION_BYTES;
            }

            // Write SessionMessage header length if offset dictates
            if (offset + bytesWritten < HEADER_LENGTH_BYTES) {

                ByteBuffer lengthBuffer = ByteBuffer.allocate(Integer.SIZE / 8)
                                                    .order(ByteOrder.LITTLE_ENDIAN)
                                                    .putInt(serializedHeaders.length);

                byte[] truncatedLength = new byte[HEADER_LENGTH_BYTES];
                lengthBuffer.rewind();
                // limit little endian value by ignoring last (most significant) bits
                lengthBuffer.get(truncatedLength);
                outputStream.write(truncatedLength);

                bytesWritten += HEADER_LENGTH_BYTES;
            }

            // Write SessionMessage HashMap header if offset dictates
            if (offset + bytesWritten >= HEADER_LENGTH_BYTES + HEADER_VERSION_BYTES &&
                offset + bytesWritten < serializedHeaders.length) {

                int headerBytesToCopy = Math.min(length - bytesWritten,
                                                 serializedHeaders.length);

                outputStream.write(serializedHeaders,
                                   offset + bytesWritten - (HEADER_LENGTH_BYTES + HEADER_VERSION_BYTES),
                                   headerBytesToCopy);

                bytesWritten += headerBytesToCopy;
            }

            // Write raw body if offset dictates
            if (bytesWritten < length) {
                // If no non-body data was written and there is no body, return null
                if (getBodyLengthBytes() == 0 && bytesWritten == 0)
                    return null;

                int bodyOffset = Math.max(0,
                                          offset - (HEADER_LENGTH_BYTES +
                                                    HEADER_VERSION_BYTES +
                                                    serializedHeaders.length)
                );

                byte[] body = getBodyAtOffset(bodyOffset, length - bytesWritten);

                if (body != null)
                    outputStream.write(body);
            }

        } catch (IOException e) {
            Timber.e(e, "IOException while serializing SessionMessage");
        }

        byte[] result = outputStream.toByteArray();
        //Timber.d(String.format("Serialized %d SessionMessage bytes", result.length));
        // Do not return zero length byte[]. Use null to represent no more data
        return result.length == 0 ? null : result;
    }

    /**
     * Serialize the entire message in one go. Must only be used for messages less than 2 MB.
     */
    public byte[] serialize() {
        if (getTotalLengthBytes() > Integer.MAX_VALUE)
            Timber.e("Message too long for serialize! Will be truncated");

        return serialize(0, (int) getTotalLengthBytes());
    }

    /**
     * @return the length of the total SessionMessage in bytes
     */
    public long getTotalLengthBytes() {

        return HEADER_VERSION_BYTES +
               HEADER_LENGTH_BYTES +
               serializedHeaders.length +
               getBodyLengthBytes();
    }

    /**
     * Cache the serialized representation of {@link #headers}.
     * Must be called before {@link #serialize()} or {@link #serialize(int, int)}
     */
    protected void serializeAndCacheHeaders() {
        if (serializedHeaders == null) {
            if (headers == null) headers = populateHeaders();
            serializedHeaders = new JSONObject(headers).toString().getBytes();
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(headers.get(HEADER_TYPE),
                            headers.get(HEADER_BODY_LENGTH),
                            headers.get(HEADER_ID));
    }

    @Override
    public boolean equals(Object obj) {

        if(obj == this) return true;
        if(obj == null) return false;

        if (getClass().equals(obj.getClass()))
        {
            final SessionMessage other = (SessionMessage) obj;

            return Objects.equals(getHeaders().get(HEADER_TYPE),
                                  other.getHeaders().get(HEADER_TYPE)) &&
                   Objects.equals(getHeaders().get(HEADER_BODY_LENGTH),
                                  other.getHeaders().get(HEADER_BODY_LENGTH)) &&
                   Objects.equals(getHeaders().get(HEADER_ID),
                                  other.getHeaders().get(HEADER_ID));
        }

        return false;
    }

}
