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
public class FileTransferMessage extends SessionMessage {

    private File file;
    private FileInputStream inputStream;

    public FileTransferMessage(File file) {
        super();
        this.file = file;
        payloadLengthBytes = file.length();

        try {
            inputStream = new FileInputStream(file);
        } catch (FileNotFoundException e) {
            Timber.e(e, "Unable to initialize TransferMessage for file " + file.getAbsolutePath());
        }
    }

    @Override
    protected HashMap<String, Object> populateHeaders() {
        HashMap<String, Object> headerMap = super.populateHeaders();

        headerMap.put("content-type", "TODO");
        headerMap.put("filename", file.getName());

        return headerMap;
    }

    @Override
    public byte[] getPayloadDataAtOffset(int offset, int length) {
        if (inputStream == null) {
            Timber.e("getPayloadDataAtOffset called, but no InputStream ready");
            return null;
        }

        try {
            byte[] result = new byte[length];
            int bytesRead = inputStream.read(result, offset, length);
            if (bytesRead < length) {
                Timber.d("getPayloadDataAtOffset read end of file");
            }
            return result;
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

}
