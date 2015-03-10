package pro.dbro.airshare.session;

import java.nio.ByteBuffer;
import java.util.HashMap;

/**
 * Created by davidbrodsky on 2/22/15.
 */
public class DataTransferMessage extends SessionMessage {

    private ByteBuffer data;

    public DataTransferMessage(byte[] data) {
        super();
        this.data = ByteBuffer.wrap(data);
        bodyLengthBytes = data.length;
        serializeAndCacheHeaders();
    }

    @Override
    protected HashMap<String, Object> populateHeaders() {
        HashMap<String, Object> headerMap = super.populateHeaders();

        headerMap.put("accept", true);
        headerMap.put("resumeoffset", 439439);

        return headerMap;
    }

    @Override
    public byte[] getBodyAtOffset(int offset, int length) {

        byte[] result = new byte[length];

        data.get(result, offset, length);

        return result;
    }

}
