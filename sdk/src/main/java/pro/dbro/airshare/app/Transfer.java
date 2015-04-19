package pro.dbro.airshare.app;

import android.support.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

import pro.dbro.airshare.session.DataTransferMessage;
import pro.dbro.airshare.session.SessionMessage;

/**
 * Created by davidbrodsky on 3/14/15.
 */
public abstract class Transfer {

    protected SessionMessage transferMessage;

    public abstract boolean isComplete();

    public @Nullable InputStream getBody() {
        if (transferMessage instanceof DataTransferMessage)
            return new ByteArrayInputStream(getBodyBytes());
        else
            throw new IllegalStateException("Only DataTransferMessage is supported!");
    }

    public @Nullable byte[] getBodyBytes() {
        if (transferMessage == null) return null;

        byte[] body = null;

        if (transferMessage instanceof DataTransferMessage) {
            body = transferMessage.getBodyAtOffset(0, transferMessage.getBodyLengthBytes());
        } else
            throw new IllegalStateException("Only DataTransferMessage is supported!");
        return body;
    }

    public @Nullable Map<String, Object> getHeaderExtras() {
        if (transferMessage == null || !(transferMessage instanceof DataTransferMessage))
            return null;
        return (Map<String, Object>) transferMessage.getHeaders().get(DataTransferMessage.HEADER_EXTRA);
    }
}
