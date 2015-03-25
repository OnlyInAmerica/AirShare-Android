package pro.dbro.airshare.app;

import android.support.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import pro.dbro.airshare.session.DataTransferMessage;
import pro.dbro.airshare.session.FileTransferMessage;
import pro.dbro.airshare.session.SessionMessage;

/**
 * Created by davidbrodsky on 3/14/15.
 */
public abstract class Transfer {

    protected SessionMessage transferMessage;

    public abstract boolean isComplete();

    public @Nullable InputStream getBody() {
        if (transferMessage instanceof FileTransferMessage)
            return ((FileTransferMessage) transferMessage).getBody();
        else if (transferMessage instanceof DataTransferMessage)
            return new ByteArrayInputStream(getBodyBytes());

        return null;
    }

    public @Nullable byte[] getBodyBytes() {
        if (transferMessage == null) return null;

        int bodySize = (int) transferMessage.getBodyLengthBytes();

        byte[] body = null;

        try {
            if (transferMessage instanceof FileTransferMessage) {
                body = new byte[bodySize];
                ((FileTransferMessage) transferMessage).getBody().read(body, 0, bodySize);
            }
            // DataTransferMessage is the only type that we should allow completely loading
            // the body into memory. Rely on DataTransferMesssage to enforce some maximum size
            else if (transferMessage instanceof DataTransferMessage) {
                body = transferMessage.getBodyAtOffset(0, transferMessage.getBodyLengthBytes());
            }
            return body;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public @Nullable Map<String, Object> getHeaderExtras() {
        return (Map<String, Object>) transferMessage.getHeaders().get(SessionMessage.HEADER_EXTRA);
    }
}
