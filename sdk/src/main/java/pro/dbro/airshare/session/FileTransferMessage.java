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
 * {@link pro.dbro.airshare.session.FileTransferMessage.TransferType} which form the three-part
 * sequence of offering, accepting, and completing a transfer.
 *
 * {@link pro.dbro.airshare.session.FileTransferMessage.TransferType#OFFER} is the sender's offering of a transfer. This message consists of only
 * a header which describes the asset's filename and length. This type is indicated by
 * the SessionMessage {@link SessionMessage#HEADER_TYPE} header having value
 * {@link #HEADER_TYPE_OFFER}
 *
 * {@link pro.dbro.airshare.session.FileTransferMessage.TransferType#ACCEPT} is the receiver's acceptance of a sender's offer message. This message
 * differs from the offer message only in that the type indicated by
 * the SessionMessage {@link SessionMessage#HEADER_TYPE} header will have value
 * {@link #HEADER_TYPE_ACCEPT}
 *
 * {@link pro.dbro.airshare.session.FileTransferMessage.TransferType#TRANSFER} is the sender's actual transfer message in response to the receiver's
 * accept message. This message consists of both a header which describes the asset's filename and
 * length as well as the corresponding asset body. This type is indicated by
 * the SessionMessage {@link SessionMessage#HEADER_TYPE} header having value
 * {@link #HEADER_TYPE_TRANSFER}.
 *
 * Created by davidbrodsky on 2/22/15.
 */
public class FileTransferMessage extends SessionMessage {

    public static enum TransferType { OFFER, ACCEPT, TRANSFER }

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
    private @NonNull  TransferType transferType;
    private @NonNull  String filename;

    private int bodyBytesRead;
    private int offerLengthBytes;

    // <editor-fold desc="Incoming Constructors">

    /**
     * Construct a FileTransferMessage from deserialized headers and body.
     */
    public FileTransferMessage(HashMap<String, Object> headers,
                               InputStream body) {

        super((String) headers.get(SessionMessage.HEADER_ID));

        String type = (String) headers.get(SessionMessage.HEADER_TYPE);
        TransferType transferType = null;
        switch (type) {
            case HEADER_TYPE_OFFER:
                transferType = TransferType.OFFER;
                break;

            case HEADER_TYPE_ACCEPT:
                transferType = TransferType.ACCEPT;
                break;

            case HEADER_TYPE_TRANSFER:
                transferType = TransferType.TRANSFER;
                break;

            default:
                throw new IllegalArgumentException(String.format("Unknown transferType '%s. " +
                        "Is FileTransferMessage#fromHeadersAndBody up to date with the allowed " +
                        "values of FileTransferMessage#Type?", type));

        }

        this.headers      = headers;
        this.transferType = transferType;
        filename          = (String) headers.get(HEADER_FILENAME);
        bodyLengthBytes   = (int) headers.get(HEADER_BODY_LENGTH);
        inputStream       = new BufferedInputStream(body);

        if (transferType != TransferType.TRANSFER)
            offerLengthBytes = (int) headers.get(HEADER_OFFER_LENGTH);

        init();
    }

    // </editor-fold desc="Incoming Constructors">

    // <editor-fold desc="Outgoing Constructors">

    /**
     * Construct a FileTransferMessage from a File source.
     *
     * The length of file should be no more than 2.14 GB because it is stored as a signed 32 bit int.
     * @throws FileNotFoundException
     */
    public FileTransferMessage(@NonNull File file, @NonNull TransferType transferType) throws FileNotFoundException {
        super();
        this.file = file;
        this.filename = file.getName();
        this.transferType = transferType;

        if (transferType == TransferType.TRANSFER)
            bodyLengthBytes  = (int) file.length();
        else
            offerLengthBytes = (int) file.length();

        inputStream = new BufferedInputStream(new FileInputStream(file));

        init();
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
                               @NonNull TransferType transferType) {
        super();
        this.transferType = transferType;
        this.filename = filename;
        this.inputStream = new BufferedInputStream(inputStream);

        if (transferType == TransferType.TRANSFER)
            bodyLengthBytes  = length;
        else
            offerLengthBytes = length;


        init();
    }

    // </editor-fold desc="Outgoing Constructors">

    private void init() {
        // TODO : On reflection we shouldn't be marking the stream with such a large limit. Maybe pick a small sane value?
        inputStream.mark(bodyLengthBytes);
        bodyBytesRead = 0;

        String type = null;

        switch(transferType) {
            case OFFER:
                type = HEADER_TYPE_OFFER;
                break;

            case ACCEPT:
                type =  HEADER_TYPE_ACCEPT;
                break;

            case TRANSFER:
                type = HEADER_TYPE_TRANSFER;
                break;

            default:
                throw new IllegalStateException("Unknown FileTransferMessage transferType");
        }

        this.type = type;
        serializeAndCacheHeaders();
    }

    @Override
    protected HashMap<String, Object> populateHeaders() {
        HashMap<String, Object> headerMap = super.populateHeaders();

        headerMap.put(HEADER_FILENAME, filename);

        // If this is an Offer or Accept message, the body is not included so
        // it's length should be exclusively reported in a special FileTransferMessage header
        if (transferType != TransferType.TRANSFER) {
            headerMap.put(HEADER_OFFER_LENGTH, offerLengthBytes);
        }

//        headerMap.put("resumeoffset", 439439);

        return headerMap;
    }

    @Override
    public long getBodyLengthBytes() {
        // Only the TRANSFER message actually includes the body
        // OFFER and ACCEPT use bodyLengthBytes to populate the HEADER_OFFER_LENGTH header
        return transferType == TransferType.TRANSFER ? bodyLengthBytes : 0;
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
        HashMap headers = getHeaders();
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

            boolean result = super.equals(other) &&
                    Objects.equals(getHeaders().get(HEADER_FILENAME),
                            other.getHeaders().get(HEADER_FILENAME)) &&
                    transferType == other.transferType;

            if (this.transferType != TransferType.TRANSFER) {
                result = result && Objects.equals(getHeaders().get(HEADER_OFFER_LENGTH),
                                                  other.getHeaders().get(HEADER_OFFER_LENGTH));
            }

            return result;
        }

        return false;
    }

}
