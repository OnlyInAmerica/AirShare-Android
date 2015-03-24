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

    private ByteBuffer data;

    public DataTransferMessage(@NonNull HashMap<String, Object> headers,
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

    public DataTransferMessage(byte[] data) {
        super();
        init();
        setBody(data);
        bodyLengthBytes = data.length;
        serializeAndCacheHeaders();

    }

    private void init() {
        type = HEADER_TYPE;
    }

    public void setBody(@NonNull byte[] body) {
        if (data != null)
            throw new IllegalStateException("Attempted to set existing message body");

        data = ByteBuffer.wrap(body);
        status = Status.COMPLETE;
    }

    @Override
    protected Map<String, Object> getHeaderExtras() {
        return null;
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
