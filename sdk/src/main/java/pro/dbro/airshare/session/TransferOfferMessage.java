package pro.dbro.airshare.session;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;

import pro.dbro.airshare.LocalPeer;
import timber.log.Timber;

/**
 * Created by davidbrodsky on 2/22/15.
 */
public class TransferOfferMessage extends SessionMessage {

    private File file;

    public TransferOfferMessage(File file) {
        super();
        this.file = file;
    }

    @Override
    protected HashMap<String, Object> populateHeaders() {
        HashMap<String, Object> headerMap = super.populateHeaders();

        headerMap.put("content-type", "TODO");
        headerMap.put("filename", file.getName());

        return headerMap;
    }

    @Override
    public byte[] getBodyAtOffset(int offset, int length) {
        return new byte[0];
    }

}
