package pro.dbro.airshare.session;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by davidbrodsky on 2/22/15.
 */
public class DataTransferMessage extends SessionMessage {

    public static final String HEADER_TYPE = "datatransfer";

    public static final String HEADER_EXTRA = "extra";

    private ByteBuffer data;
    private Map<String, Object> extraHeaders;

    // <editor-fold desc="Incoming Constructors">

    DataTransferMessage(@NonNull Map<String, Object> headers,
                        @Nullable byte[] body) {

        super((String) headers.get(SessionMessage.HEADER_ID));
        init();
        this.headers      = headers;
        bodyLengthBytes   = (int) headers.get(HEADER_BODY_LENGTH);
        status            = body == null ? Status.HEADER_ONLY : Status.COMPLETE;

        if (body != null)
            setBody(body);

        serializeAndCacheHeaders();

    }

    // </editor-fold desc="Incoming Constructors">

    // <editor-fold desc="Outgoing Constructors">

    public static DataTransferMessage createOutgoing(@Nullable Map<String, Object> extraHeaders,
                                                     @Nullable byte[] data) {

        return new DataTransferMessage(data, extraHeaders);
    }

    // To avoid confusion between the incoming constructor which takes a
    // Map of the completely deserialized headers and byte payload, we hide
    // this contstructor behind the static creator 'createOutgoing'
    private DataTransferMessage(@Nullable byte[] data,
                                @Nullable Map<String, Object> extraHeaders) {
        super();
        this.extraHeaders = extraHeaders;
        init();
        if (data != null) {
            setBody(data);
            bodyLengthBytes = data.length;
        }
        serializeAndCacheHeaders();

    }

    // </editor-fold desc="Outgoing Constructors">

    private void init() {
        type = HEADER_TYPE;
    }

    @Override
    protected HashMap<String, Object> populateHeaders() {
        HashMap<String, Object> headerMap = super.populateHeaders();
        if (extraHeaders != null)
            headerMap.put(HEADER_EXTRA, extraHeaders);

        // The following three lines should be deleted
//        headerMap.put(HEADER_TYPE,        type);
//        headerMap.put(HEADER_BODY_LENGTH, bodyLengthBytes);
//        headerMap.put(HEADER_ID,          id);

        return headerMap;
    }

    public void setBody(@NonNull byte[] body) {
        if (data != null)
            throw new IllegalStateException("Attempted to set existing message body");

        data = ByteBuffer.wrap(body);
        status = Status.COMPLETE;
    }

    @Override
    public byte[] getBodyAtOffset(int offset, int length) {

        if (offset > bodyLengthBytes - 1) return null;

        int bytesToRead = Math.min(length, bodyLengthBytes - offset);
        byte[] result = new byte[bytesToRead];

        data.position(offset);
        data.get(result, 0, bytesToRead);

        return result;
    }

}
