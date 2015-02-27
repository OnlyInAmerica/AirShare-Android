package pro.dbro.airshare.session;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

import timber.log.Timber;

/**
 * Created by davidbrodsky on 2/22/15.
 */
public class FileTransferMessage extends SessionMessage {

    public static enum Type { OFFER, ACCEPT, TRANSFER }

    public static final String HEADER_TYPE_OFFER    = "filetransfer-offer";
    public static final String HEADER_TYPE_ACCEPT   = "filetransfer-accept";
    public static final String HEADER_TYPE_TRANSFER = "filetransfer";

    private @Nullable File file;
    private @NonNull  FileInputStream inputStream;
    private @NonNull  Type type;
    private @NonNull  String filename;

    public static FileTransferMessage fromheadersAndPayload(HashMap<String, Object> headers,
                                                            FileInputStream payload) {

        String typeStr = (String) headers.get(SessionMessage.HEADER_TYPE);
        Type type = null;
        switch (typeStr) {
            case HEADER_TYPE_OFFER:
                type = Type.OFFER;
                break;

            case HEADER_TYPE_ACCEPT:
                type = Type.ACCEPT;
                break;

            case HEADER_TYPE_TRANSFER:
                type = Type.TRANSFER;
                break;
        }

        return new FileTransferMessage(payload,
                                       (String) headers.get("filename"),
                                       (int) headers.get(SessionMessage.HEADER_PAYLOAD_LENGTH),
                                       type);
    }

    public FileTransferMessage(@NonNull FileInputStream inputStream,
                               String filename,
                               int length,
                               @NonNull Type type) {
        super();
        this.type = type;
        this.filename = filename;
        payloadLengthBytes = length;
        this.inputStream = inputStream;
    }

    /**
     * Create a message describing a File transfer. The length of file should be no
     * more than 2.14 GB, as length is serialized as a 32 bit int.
     * @throws FileNotFoundException
     */
    public FileTransferMessage(@NonNull File file, @NonNull Type type) throws FileNotFoundException {
        super();
        this.file = file;
        this.filename = file.getName();
        this.type = type;
        payloadLengthBytes = (int) file.length();

        inputStream = new FileInputStream(file);
    }

    @Override
    protected HashMap<String, Object> populateHeaders() {
        HashMap<String, Object> headerMap = super.populateHeaders();

        String typeHeader = null;

        switch(type) {
            case OFFER:
                typeHeader = HEADER_TYPE_OFFER;
                break;

            case ACCEPT:
                typeHeader =  HEADER_TYPE_ACCEPT;
                break;

            case TRANSFER:
                typeHeader = HEADER_TYPE_TRANSFER;
                break;

            default:
                throw new IllegalStateException("Unknown FileTransferMessage type");
        }
        headerMap.put("type", typeHeader);
        headerMap.put("filename", filename);

//        headerMap.put("resumeoffset", 439439);

        return headerMap;
    }

    @Override
    public byte[] getPayloadDataAtOffset(int offset, int length) {

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
