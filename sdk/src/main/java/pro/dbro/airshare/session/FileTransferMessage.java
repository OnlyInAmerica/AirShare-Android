package pro.dbro.airshare.session;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

import timber.log.Timber;

/**
 * FileTransferMessage embodies a transfer of an on-disk asset available to the client as a
 * {@link java.io.File} or {@link java.io.InputStream}.
 *
 * A FileTransferMessage has three types represented by
 * {@link pro.dbro.airshare.session.FileTransferMessage.Type} which form the three-part
 * sequence of offering, accepting, and completing a transfer.
 *
 * {@link Type#OFFER} is the sender's offering of a transfer. This message consists of only
 * a header which describes the asset's filename and length. This type is indicated by
 * the SessionMessage {@link SessionMessage#HEADER_TYPE} header having value
 * {@link #HEADER_TYPE_OFFER}
 *
 * {@link Type#ACCEPT} is the receiver's acceptance of a sender's offer message. This message
 * differs from the offer message only in that the type indicated by
 * the SessionMessage {@link SessionMessage#HEADER_TYPE} header will have value
 * {@link #HEADER_TYPE_ACCEPT}
 *
 * {@link Type#TRANSFER} is the sender's actual transfer message in response to the receiver's
 * accept message. This message consists of both a header which describes the asset's filename and
 * length as well as the corresponding asset payload. This type is indicated by
 * the SessionMessage {@link SessionMessage#HEADER_TYPE} header having value
 * {@link #HEADER_TYPE_TRANSFER}.
 *
 * Created by davidbrodsky on 2/22/15.
 */
public class FileTransferMessage extends SessionMessage {

    public static enum Type { OFFER, ACCEPT, TRANSFER }

    /** Describe the size of the offered payload. This differs from
     * {@link SessionMessage#HEADER_PAYLOAD_LENGTH} because the payload is not actually
     * included in the offer message.
     */
    public static final String HEADER_OFFER_LENGTH = "filetransfer-offer-length";

    /** Values for SessionMessage {@link SessionMessage#HEADER_TYPE} header */
    public static final String HEADER_TYPE_OFFER    = "filetransfer-offer";
    public static final String HEADER_TYPE_ACCEPT   = "filetransfer-accept";
    public static final String HEADER_TYPE_TRANSFER = "filetransfer";

    private @Nullable File file;
    private @NonNull  BufferedInputStream inputStream;
    private @NonNull  Type type;
    private @NonNull  String filename;

    private int payloadBytesRead;

    /**
     * Convenience creator for deserialization
     */
    public static FileTransferMessage fromHeadersAndPayload(HashMap<String, Object> headers,
                                                            InputStream payload) {

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

            default:
                throw new IllegalArgumentException(String.format("Unknown type '%s. " +
                        "Is FileTransferMessage#fromHeadersAndPayload up to date with the allowed " +
                        "values of FileTransferMessage#Type?", typeStr));
        }

        return new FileTransferMessage((String) headers.get(SessionMessage.HEADER_ID),
                                       payload,
                                       (String) headers.get("filename"),
                                       (int) headers.get(SessionMessage.HEADER_PAYLOAD_LENGTH),
                                       type);
    }

    /**
     * Construct a FileTransferMessage from an InputStream source.
     *
     * Note that we use {@link java.io.InputStream} instead of {@link java.io.FileInputStream}
     * to accommodate files bundled as Android Assets or available via the Storage Access Framework
     * that are most naturally accessible as InputStreams.
     */
    public FileTransferMessage(@NonNull InputStream inputStream,
                               @NonNull String filename,
                               int length,
                               @NonNull Type type) {
        super();
        this.type = type;
        this.filename = filename;
        payloadLengthBytes = length;
        this.inputStream = new BufferedInputStream(inputStream);

        init();
    }

    /**
     * Construct a FileTransferMessage from an InputStream source and a given id.
     * This should only be used for deserialiation of incoming FileTransferMessages.
     * To create a new FileTransferMessage for transmission, allow a new unique
     * identifier to be generated via
     * {@link #FileTransferMessage(java.io.InputStream, String, int, pro.dbro.airshare.session.FileTransferMessage.Type)}
     */
    public FileTransferMessage(@NonNull String id,
                               @NonNull InputStream inputStream,
                               @NonNull String filename,
                               int length,
                               @NonNull Type type) {
        super(id);
        this.type = type;
        this.filename = filename;
        payloadLengthBytes = length;
        this.inputStream = new BufferedInputStream(inputStream);

        init();
    }

    /**
     * Create a message describing a File transfer. The length of file should be no
     * more than 2.14 GB because it is stored as a signed 32 bit int.
     * @throws FileNotFoundException
     */
    public FileTransferMessage(@NonNull File file, @NonNull Type type) throws FileNotFoundException {
        super();
        this.file = file;
        this.filename = file.getName();
        this.type = type;
        payloadLengthBytes = (int) file.length();

        inputStream = new BufferedInputStream(new FileInputStream(file));

        init();
    }

    private void init() {
        this.inputStream.mark(payloadLengthBytes);
        payloadBytesRead = 0;

        seralizeAndCacheHeaders();
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
        headerMap.put("type",     typeHeader);
        headerMap.put("filename", filename);

        // If this is an Offer or Accept message, the payload is not included so
        // it's length should be exclusively reported in a special FileTransferMessage header
        if (type != Type.TRANSFER) {
            headerMap.put(SessionMessage.HEADER_PAYLOAD_LENGTH, 0);
            headerMap.put(HEADER_OFFER_LENGTH, payloadLengthBytes);
        }

//        headerMap.put("resumeoffset", 439439);

        return headerMap;
    }

    @Override
    public long getPayloadLengthBytes() {
        // Only the TRANSFER message actually includes the payload
        // OFFER and ACCEPT use payloadLengthBytes to populate the HEADER_OFFER_LENGTH header
        return type == Type.TRANSFER ? payloadLengthBytes : 0;
    }

    @Override
    public @Nullable byte[] getPayloadDataAtOffset(int offset, int length) {
        // NOTE : This method is written to be generic to a BufferedInputStream
        // for potential re-use as part of an abstract InputStreamMessage etc.

        if (offset > payloadLengthBytes - 1) return null;

        try {
            if (offset != payloadBytesRead) {
                Timber.w("FileTransferMessage skip requested. Offset is %d but bytes read is %d", offset, payloadBytesRead);
                if (!inputStream.markSupported())
                    throw new UnsupportedOperationException("InputStream does not support non-sequential reading");

                long bytesSkippedActual = 0;
                long bytesSkippedRequested = 0;
                if (offset > payloadBytesRead) {
                    bytesSkippedRequested = offset - payloadBytesRead;

                } else if (offset < payloadBytesRead) {
                    bytesSkippedRequested = offset;
                    inputStream.reset();
                }

                bytesSkippedActual = inputStream.skip(offset);

                if (bytesSkippedActual != bytesSkippedRequested)
                    throw new UnsupportedOperationException("Unable to skip by requested interval");
            }

            int bytesToRead = Math.min(length, payloadLengthBytes - offset);
            byte[] result = new byte[bytesToRead];
            int bytesRead = inputStream.read(result, 0, bytesToRead);
            payloadBytesRead += bytesRead;

            if (bytesRead < length)
                Timber.d("getPayloadDataAtOffset read end of file");

            return result;

        } catch (IOException e) {
            Timber.e(e, String.format("Failed to serialize %s at offset %d", filename, offset));
        }
        return null;
    }

}
