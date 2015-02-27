package pro.dbro.airshare.session;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.UUID;

import pro.dbro.airshare.DataUtil;
import timber.log.Timber;

/**
 * Represents a Session segment suitable for transport via a {@link pro.dbro.airshare.transport.Transport}
 *
 * Note: Children of this class are intended to be immutable. e.g: {@link #populateHeaders()}
 * will only be called once across multiple serializations.
 *
 * Created by davidbrodsky on 2/22/15.
 */
public abstract class SessionMessage {

    /** SessionMessage version. Must be representable by {@link #HEADER_VERSION_BYTES} bytes */
    public static final int CURRENT_HEADER_VERSION = 1;

    /** Leading byte specifies header format version */
    public static final int HEADER_VERSION_BYTES   = 1;

    /** Next bytes specify header size in bytes. Max header size: 16.777216 MB */
    public static final int HEADER_LENGTH_BYTES    = 3;

    /** Required header map keys */
    public static final String HEADER_TYPE           = "type";
    public static final String HEADER_PAYLOAD_LENGTH = "length";
    public static final String HEADER_ID             = "id";

    protected int    version;
    protected String type;
    protected int    payloadLengthBytes;
    protected String id;
    private   byte[] cachedHeader;

    public SessionMessage() {
        type = getClass().getSimpleName();
        payloadLengthBytes = 0;
        version = CURRENT_HEADER_VERSION;
        id = UUID.randomUUID().toString().substring(28);
    }

    protected HashMap<String, Object> populateHeaders() {
        HashMap<String, Object> headerMap = new HashMap<>();
        headerMap.put(HEADER_TYPE,    type);
        headerMap.put(HEADER_PAYLOAD_LENGTH,  payloadLengthBytes);
        headerMap.put(HEADER_ID,      id);
        return headerMap;
    }

    public String getType() {
        return type;
    }

    /**
     * @return the length of the blob payload in bytes
     */
    public long getDataLengthBytes() {
        return payloadLengthBytes;
    }

    public abstract byte[] getPayloadDataAtOffset(int offset, int length);

    /**
     * Serialize this SessionMessage for transport. Note that when the returned byte[]
     * has length less than length, serialization is complete.
     *
     * The general format of the serialized bytstream:
     *
     * byte idx | description
     * ---------|------------
     * [0]      | SessionMessage version
     * [1-3]    | Header length
     * [3-X]    | Header (json string)
     * [X-Y]    | Payload
     *
     * @param length should never be less than {@link #HEADER_LENGTH_BYTES} + {@link #HEADER_VERSION_BYTES}
     *
     */
    public byte[] serialize(int offset, int length) {

        seralizeAndCacheHeaders();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            int idx = 0;
            // Write SessionMessage header version if offset dictates
            if (offset + idx < HEADER_LENGTH_BYTES) {
                outputStream.write((byte) CURRENT_HEADER_VERSION);

                idx += HEADER_VERSION_BYTES;
            }
            // Write SessionMessage header length if offset dictates
            if (offset + idx < HEADER_LENGTH_BYTES) {
                ByteBuffer lengthBuffer = ByteBuffer.allocate(4)
                                                    .putInt(cachedHeader.length);
                lengthBuffer.rewind();
                lengthBuffer.position(1);
                byte[] truncatedLength = new byte[HEADER_LENGTH_BYTES];
                // BigEndian -> truncate first bit
                lengthBuffer.get(truncatedLength, 0, 3);
                Timber.d(String.format("Serialized header length %d as %s",
                        cachedHeader.length, DataUtil.bytesToHex(truncatedLength)));
                outputStream.write(truncatedLength);

                idx += HEADER_LENGTH_BYTES;
            }
            // Write SessionMessage HashMap header if offset dictates
            if (offset + idx >= HEADER_LENGTH_BYTES + HEADER_VERSION_BYTES &&
                offset + idx < cachedHeader.length) {
                int headerBytesToCopy = Math.min(length - idx,
                                                 cachedHeader.length);

                outputStream.write(cachedHeader,
                                   offset + idx - (HEADER_LENGTH_BYTES + HEADER_VERSION_BYTES),
                                   headerBytesToCopy);
                idx += headerBytesToCopy;
            }

            // Write raw payload if offset dictates
            if (idx < length) {
                int payloadOffset = offset - cachedHeader.length;
                    outputStream.write(getPayloadDataAtOffset(payloadOffset, length - idx));

            }

        } catch (IOException e) {
            Timber.e(e, "IOException while serializing SessionMessage");
        }

        byte[] result = outputStream.toByteArray();
        Timber.d(String.format("Serialized SessionMessage in %d bytes", result.length));
        return result;
    }

    /**
     * Serialize the entire message in one go. Must only be used for messages less than 2 MB.
     */
    public byte[] serialize() {
        seralizeAndCacheHeaders();

        if (getTotalLengthBytes() > Integer.MAX_VALUE)
            Timber.e("Message too long for serialize! Will be truncated");

        return serialize(0, (int) getTotalLengthBytes());
    }

    /**
     * @return the length of the total SessionMessage in bytes
     */
    private long getTotalLengthBytes() {

        return HEADER_LENGTH_BYTES + HEADER_VERSION_BYTES + cachedHeader.length + getDataLengthBytes();
    }

    private void seralizeAndCacheHeaders() {
        // Cache serialized version of header HashMap if necessary
        if (cachedHeader == null) {
            HashMap<String, Object> headers = populateHeaders();
            cachedHeader = new JSONObject(headers).toString().getBytes();
        }
    }

}
