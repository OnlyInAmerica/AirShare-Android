package pro.dbro.airshare.session;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;

import timber.log.Timber;

/**
 * Represents a Session segment suitable for transport via a {@link pro.dbro.airshare.transport.Transport}
 *
 * Created by davidbrodsky on 2/22/15.
 */
public abstract class SessionMessage {

    /** int length header for maximum size of 16.777216 MB */
    public static final int MAX_HEADER_LENGTH_BYTES = 3;

    public static final String HEADER_VERSION= "version";
    public static final String HEADER_TYPE   = "type";
    public static final String HEADER_LENGTH = "length";

    protected String type;
    protected long   payloadLengthBytes;
    private   byte[] cachedHeader;

    public SessionMessage() {
        type = getClass().getSimpleName();
        payloadLengthBytes = 0;
    }

    protected HashMap<String, Object> populateHeaders() {
        HashMap<String, Object> headerMap = new HashMap<>();
        headerMap.put(HEADER_VERSION, 1);
        headerMap.put(HEADER_TYPE, type);
        headerMap.put(HEADER_LENGTH, payloadLengthBytes);
        return headerMap;
    }

    public String getType() {
        return type;
    }

    public long getDataLengthBytes() {
        return payloadLengthBytes;
    }

    public abstract byte[] getPayloadDataAtOffset(int offset, int length);

    /**
     * Serialize this SessionMessage for transport.
     *
     * The general format of the serialized bytstream:
     *
     * byte idx | description
     * ---------|------------
     * [0-2]    | Header length
     * [2-X]    | Header (json string)
     * [X-Y]    | Payload
     *
     * @param length should never be less than {@link #MAX_HEADER_LENGTH_BYTES}
     *
     */
    public byte[] serialize(int offset, int length) {

        // Cache serialized version of header HashMap if necessary
        if (cachedHeader == null) {
            HashMap<String, Object> headers = populateHeaders();
            cachedHeader = new JSONObject(headers).toString().getBytes();
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            int idx = 0;
            // Write SessionMessage header length if offset dictates
            if (offset + idx < MAX_HEADER_LENGTH_BYTES) {
                outputStream.write(
                        ByteBuffer.allocate(MAX_HEADER_LENGTH_BYTES)
                                  .putInt(cachedHeader.length)
                                  .array());

                idx += MAX_HEADER_LENGTH_BYTES;
            }
            // Write SessionMessage HashMap header if offset dictates
            if (offset + idx < cachedHeader.length) {
                int headerBytesToCopy = Math.min(length, cachedHeader.length - (offset + idx));

                outputStream.write(cachedHeader,
                                   offset + idx,
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

        return outputStream.toByteArray();
    }

}
