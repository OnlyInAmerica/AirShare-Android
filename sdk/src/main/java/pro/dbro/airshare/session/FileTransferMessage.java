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
import java.util.Objects;

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
 * length as well as the corresponding asset body. This type is indicated by
 * the SessionMessage {@link SessionMessage#HEADER_TYPE} header having value
 * {@link #HEADER_TYPE_TRANSFER}.
 *
 * Created by davidbrodsky on 2/22/15.
 */
public class FileTransferMessage extends SessionMessage {

    public static enum Type { OFFER, ACCEPT, TRANSFER }

    /** Header keys */
    /** Describe the size of the offered body. This differs from
     * {@link SessionMessage#HEADER_BODY_LENGTH} because the body is not actually
     * included in the offer message.
     */
    public static final String HEADER_OFFER_LENGTH = "filetransfer-offer-length";
    public static final String HEADER_FILENAME     = "filename";

    /** Values for SessionMessage {@link SessionMessage#HEADER_TYPE} header */
    public static final String HEADER_TYPE_OFFER    = "filetransfer-offer";
    public static final String HEADER_TYPE_ACCEPT   = "filetransfer-accept";
    public static final String HEADER_TYPE_TRANSFER = "filetransfer";

    private @Nullable File file;
    private @NonNull  BufferedInputStream inputStream;
    private @NonNull  Type type;
    private @NonNull  String filename;

    private int bodyBytesRead;

    /**
     * Convenience creator for deserialization
     */
    public static FileTransferMessage fromHeadersAndBody(HashMap<String, Object> headers,
                                                         InputStream body) {

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
                        "Is FileTransferMessage#fromHeadersAndBody up to date with the allowed " +
                        "values of FileTransferMessage#Type?", typeStr));
        }

        return new FileTransferMessage((String) headers.get(SessionMessage.HEADER_ID),
                                       body,
                                       (String) headers.get(HEADER_FILENAME),
                                       (int) headers.get(SessionMessage.HEADER_BODY_LENGTH),
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
        bodyLengthBytes = length;
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
        bodyLengthBytes = length;
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
        bodyLengthBytes = (int) file.length();

        inputStream = new BufferedInputStream(new FileInputStream(file));

        init();
    }

    private void init() {
        this.inputStream.mark(bodyLengthBytes);
        bodyBytesRead = 0;

        serializeAndCacheHeaders();
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
        headerMap.put(SessionMessage.HEADER_TYPE, typeHeader);
        headerMap.put(HEADER_FILENAME,            filename);

        // If this is an Offer or Accept message, the body is not included so
        // it's length should be exclusively reported in a special FileTransferMessage header
        if (type != Type.TRANSFER) {
            headerMap.put(SessionMessage.HEADER_BODY_LENGTH, 0);
            headerMap.put(HEADER_OFFER_LENGTH, bodyLengthBytes);
        }

//        headerMap.put("resumeoffset", 439439);

        return headerMap;
    }

    @Override
    public long getBodyLengthBytes() {
        // Only the TRANSFER message actually includes the body
        // OFFER and ACCEPT use bodyLengthBytes to populate the HEADER_OFFER_LENGTH header
        return type == Type.TRANSFER ? bodyLengthBytes : 0;
    }

    @Override
    public @Nullable byte[] getBodyAtOffset(int offset, int length) {
        // NOTE : This method is written to be generic to a BufferedInputStream
        // for potential re-use as part of an abstract InputStreamMessage etc.

        if (offset > bodyLengthBytes - 1) return null;

        try {
            if (offset != bodyBytesRead) {
                Timber.w("FileTransferMessage skip requested. Offset is %d but bytes read is %d", offset, bodyBytesRead);
                if (!inputStream.markSupported())
                    throw new UnsupportedOperationException("InputStream does not support non-sequential reading");

                long bytesSkippedActual = 0;
                long bytesSkippedRequested = 0;
                if (offset > bodyBytesRead) {
                    bytesSkippedRequested = offset - bodyBytesRead;

                } else if (offset < bodyBytesRead) {
                    bytesSkippedRequested = offset;
                    inputStream.reset();
                }

                bytesSkippedActual = inputStream.skip(offset);

                if (bytesSkippedActual != bytesSkippedRequested)
                    throw new UnsupportedOperationException("Unable to skip by requested interval");
            }

            int bytesToRead = Math.min(length, bodyLengthBytes - offset);
            byte[] result = new byte[bytesToRead];
            int bytesRead = inputStream.read(result, 0, bytesToRead);
            bodyBytesRead += bytesRead;

            if (bytesRead < length)
                Timber.d("getBodyAtOffset read end of file");

            return result;

        } catch (IOException e) {
            Timber.e(e, String.format("Failed to serialize %s at offset %d", filename, offset));
        }
        return null;
    }

    @Override
    public int hashCode() {
        return Objects.hash(headers.get(HEADER_TYPE),
                headers.get(HEADER_BODY_LENGTH),
                headers.get(HEADER_ID),
                headers.get(HEADER_FILENAME));
    }

    @Override
    public boolean equals(Object obj) {

        if(obj == this) return true;
        if(obj == null) return false;

        if (getClass().equals(obj.getClass()))
        {
            final FileTransferMessage other = (FileTransferMessage) obj;

            boolean result = super.equals(obj) &&
                    Objects.equals(getHeaders().get(HEADER_FILENAME),
                            other.getHeaders().get(HEADER_FILENAME)) &&
                    type == other.type;

            if (this.type != Type.TRANSFER) {
                result = result && Objects.equals(getHeaders().get(HEADER_OFFER_LENGTH),
                                                  other.getHeaders().get(HEADER_OFFER_LENGTH));
            }

            return result;
        }

        return false;
    }

}
