package pro.dbro.airshare.session;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by davidbrodsky on 2/22/15.
 */
public class TransportUpgradeMessage extends SessionMessage {

    public static final String HEADER_TYPE = "transport-upgrade";

    public static final String HEADER_TRANSPORT_CODE = "transport-code";

    private int transportCode;

    // <editor-fold desc="Incoming Constructors">

    TransportUpgradeMessage(@NonNull Map<String, Object> headers) {

        super((String) headers.get(SessionMessage.HEADER_ID));
        init();
        this.transportCode = (int) headers.get(HEADER_TRANSPORT_CODE);
        this.headers       = headers;
        bodyLengthBytes    = (int) headers.get(HEADER_BODY_LENGTH);
        status             = Status.COMPLETE;

        serializeAndCacheHeaders();

    }

    // </editor-fold desc="Incoming Constructors">

    // <editor-fold desc="Outgoing Constructors">

    public TransportUpgradeMessage(int transportCode) {
        super();
        init();
        this.transportCode = transportCode;
        serializeAndCacheHeaders();
    }

    // </editor-fold desc="Outgoing Constructors">

    public int getTransportCode() {
        return transportCode;
    }

    private void init() {
        type = HEADER_TYPE;
    }

    @Override
    protected HashMap<String, Object> populateHeaders() {
        HashMap<String, Object> headerMap = super.populateHeaders();

        headerMap.put(HEADER_TRANSPORT_CODE, transportCode);

        return headerMap;
    }

    @Nullable
    @Override
    public byte[] getBodyAtOffset(int offset, int length) {
        return null;
    }
}
