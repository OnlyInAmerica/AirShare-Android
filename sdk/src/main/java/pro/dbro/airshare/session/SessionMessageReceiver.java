package pro.dbro.airshare.session;

import android.content.Context;
import android.support.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import timber.log.Timber;

/**
 * This class facilitates deserializing a {@link pro.dbro.airshare.session.SessionMessage} from
 * in-order data streams.
 *
 * After construction, call {@link #dataReceived(byte[])} as data arrives and await
 * notification of deserialization events via the
 * {@link pro.dbro.airshare.session.SessionMessageReceiver.SessionMessageReceiverCallback}
 * passed to the constructor.
 *
 * This class assumes contiguous serialized SessionMessage chunks will be delivered in-order and
 * that any discontinuities in the data stream will be reported by the client of this class via
 * {@link #reset()}. A call to {@link #reset()} will result in the loss of the partially accumulated
 * SessionMessage.
 *
 * Created by davidbrodsky on 2/24/15.
 */
public class SessionMessageReceiver {

    public static interface SessionMessageReceiverCallback {

        public void onHeaderReady(HashMap<String, Object> header);
        public void onProgress(float progress);
        public void onComplete(SessionMessage message, Exception e);

    }

    private Context context;
    private ByteBuffer buffer;
    private SessionMessageReceiverCallback callback;
    private File payloadFile;
    private FileOutputStream payloadStream;

    private HashMap<String, Object> headers;

    private boolean gotVersion;
    private boolean gotHeaderLength;
    private boolean gotHeader;
    private boolean gotPayload;
    private boolean gotPayloadBoundary;

    private int headerLength;
    private int dataBytesProcessed;
    private int payloadLength;
    private int payloadBytesReceived;

    public SessionMessageReceiver(Context context, SessionMessageReceiverCallback callback) {
        buffer = ByteBuffer.allocate(5 * 1000);
        this.callback = callback;
        this.context = context;

        init();
    }

    /**
     * Reset the state of the receiver in preparation for a new SessionMessage, losing any
     * partially accumulated SessionMessage. Call this if the incoming data stream is interrupted
     * and not expected to be immediately resumed.
     * e.g: the source of incoming data becomes unavailable.
     */
    public void reset() {
        gotVersion         = false;
        gotHeaderLength    = false;
        gotHeader          = false;
        gotPayload         = false;
        gotPayloadBoundary = false;

        headerLength         = 0;
        payloadLength        = 0;
        payloadBytesReceived = 0;

        buffer.position(0);
        init();
    }

    /**
     * Process sequential chunk of a serialized {@link pro.dbro.airshare.session.SessionMessage}
     *
     * This method will call {@link #reset()} internally if data provided completes a SessionMessage.
     *
     * @param data sequential chunk of a serialized {@link pro.dbro.airshare.session.SessionMessage}
     */
    public void dataReceived(byte[] data) {
        dataBytesProcessed = 0;

        if (data.length > buffer.capacity() - buffer.position())
            resizeBuffer(data.length);

        try {

            /** Write incoming data to memory buffer if accumulated bytes received (since contruction
             * or call to {@link #reset()}) indicates we are still receiving the SessionMessage header
             * metadata or body. If accumulated bytes received indicates we are receiving payload,
             * write to payload FileOutputStream
             */
            if (gotHeaderLength && buffer.position() == SessionMessage.HEADER_VERSION_BYTES +
                                                        SessionMessage.HEADER_LENGTH_BYTES + headerLength) {
                    payloadBytesReceived += data.length;
                    payloadStream.write(data);
            }
            else {
                buffer.put(data);
            }

        } catch (IOException e) {
            Timber.e(e, "Failed to write data to payload outputStream");
        }


        /** Deserialize SessionMessage Header version byte, if not yet done since construction
         * or last call to {@link #reset()}
         */
        if (!gotVersion && buffer.position() >= SessionMessage.HEADER_VERSION_BYTES) {
            // Get version int from first byte
            // Check we can deserialize this version
            int version = new BigInteger(new byte[] { buffer.get(0) }).intValue();
            Timber.d("Deserialized header version " + version);
            if (version != SessionMessage.CURRENT_HEADER_VERSION) {
                Timber.e("Unknown SessionMessage version");
                if (callback != null)
                    callback.onComplete(null, new UnsupportedOperationException("Unknown SessionMessage version"));
                return;
            }
            dataBytesProcessed += 1;
            gotVersion = true;
        }

        /** Deserialize SessionMessage Header length bytes, if not yet done since construction
         * or last call to {@link #reset()}
         */
        if (!gotHeaderLength && buffer.position() >=
            SessionMessage.HEADER_VERSION_BYTES + SessionMessage.HEADER_LENGTH_BYTES) {

            // Get header length and store. Deserialize header when possible
            // TODO If header is above some size, copy to FileOutputStream
            headerLength = new BigInteger(new byte[] { buffer.get(1), buffer.get(2), buffer.get(3) }).intValue();
            Timber.d("Deserialized header length " + headerLength);
            gotHeaderLength = true;
            dataBytesProcessed += 3;
        }

        /** Deserialize SessionMessage Header content, if not yet done since construction
         * or last call to {@link #reset()}
         */
        if (!gotHeader && gotHeaderLength && buffer.position() >= SessionMessage.HEADER_VERSION_BYTES +
                                                                  SessionMessage.HEADER_LENGTH_BYTES +
                                                                  headerLength) {

            // Deserialize Header
            // Get payload length from header
            // If payload is above some size, copy to FileOutputStream

            // At this point, we can start reporting progress to callback
            byte[] headerString = new byte[headerLength];
            int originalBufferPosition = buffer.position();
            buffer.position(SessionMessage.HEADER_VERSION_BYTES + SessionMessage.HEADER_LENGTH_BYTES);
            buffer.get(headerString,
                       0,
                       headerLength);
            buffer.position(originalBufferPosition);
            try {
                JSONObject jsonHeader = new JSONObject(new String(headerString, "UTF-8"));
                headers = toMap(jsonHeader);
                payloadLength = (int) headers.get(SessionMessage.HEADER_PAYLOAD_LENGTH);
                Timber.d(String.format("Deserialized header of type %s with payload length %d", (String) headers.get(SessionMessage.HEADER_TYPE), (int) headers.get(SessionMessage.HEADER_PAYLOAD_LENGTH)));
                if (callback != null)
                    callback.onHeaderReady(headers);
            } catch (JSONException | UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            gotHeader = true;
            dataBytesProcessed += headerLength;
        }
//        else if (!gotHeader)
//            Timber.d(String.format("Got %d / %d header bytes", buffer.position(), SessionMessage.HEADER_VERSION_BYTES + SessionMessage.HEADER_LENGTH_BYTES + headerLength));

        /** Ensure that if the boundary between header content and payload content occurred within this
         * received data we remove payload data from the header memory buffer and insert it into the payload
         * FileOutputStream. Must be performed before determining payload completion.
         *
         * Performed at most once per construction or call to {@link #reset()}
         */
        if (!gotPayloadBoundary && gotHeader && payloadLength > 0 && buffer.position() >=
                                                                           SessionMessage.HEADER_VERSION_BYTES +
                                                                           SessionMessage.HEADER_LENGTH_BYTES +
                                                                           headerLength) {

            try {
                int payloadBytesJustReceived = data.length - dataBytesProcessed;
                byte[] payloadBytes = new byte[payloadBytesJustReceived];
                buffer.position(buffer.position() - payloadBytesJustReceived);
                buffer.get(payloadBytes, 0, payloadBytesJustReceived);
                buffer.position(buffer.position() - payloadBytesJustReceived);
                payloadBytesReceived += payloadBytesJustReceived;
                payloadStream.write(payloadBytes, 0, payloadBytesJustReceived);

                if (callback != null && payloadLength > 0)
                    callback.onProgress(payloadBytesReceived / (float) payloadLength);

                Timber.d(String.format("Splitting received data between header (%d bytes) and payload (%d bytes)", dataBytesProcessed, payloadBytesJustReceived));
                gotPayloadBoundary = true;


            } catch (IOException e) {
                Timber.d(e, "IOException");
            }
        }

        /** Construct and deliver complete SessionMessage if deserialized header and payload are received */
        if (gotHeader && !gotPayload && payloadBytesReceived == payloadLength) {

            Timber.d("Got payload!");
            // Construct appropriate SessionMessage or child object
            try {
                payloadStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (callback != null) {
                try {

                    SessionMessage message = sessionMessageFromHeaderAndPayload(headers, new FileInputStream(payloadFile));
                    if (callback != null) callback.onComplete(message, null);
                } catch (FileNotFoundException e) {
                    Timber.e(e, "Failed to get handle on payload file for reading");
                    if (callback != null) callback.onComplete(null, e);
                }
            }
            gotPayload = true;

            // Prepare for next incoming message
            reset();
        }
//        else if (!gotPayload && gotHeader) {
//            Timber.d(String.format("Read %d / %d payload bytes", payloadBytesReceived, payloadLength));
//        }
    }

    private void init() {
        payloadFile = new File(context.getExternalFilesDir(null), UUID.randomUUID().toString().replace("-","") + ".payload");
        try {
            payloadStream = new FileOutputStream(payloadFile);
        } catch (FileNotFoundException e) {
            String msg = "Failed to open payload File: " + payloadFile.getAbsolutePath();
            Timber.e(e, msg);
            throw new IllegalArgumentException(msg);
        }
    }

    private void resizeBuffer(int minLength) {
        ByteBuffer newBuffer = ByteBuffer.allocate(Math.max(minLength, (int) (buffer.capacity() * 1.5)));
        newBuffer.put(buffer);
        buffer = newBuffer;
    }

    private static @Nullable SessionMessage sessionMessageFromHeaderAndPayload(HashMap<String, Object> headers,
                                                                               FileInputStream payload) {
        if (!headers.containsKey(SessionMessage.HEADER_TYPE))
            throw new IllegalArgumentException("headers map must have 'type' entry");

        final String headerType = (String) headers.get(SessionMessage.HEADER_TYPE);
        switch(headerType) {
            case IdentityMessage.HEADER_TYPE:
                return IdentityMessage.fromHeaders(headers);

            case FileTransferMessage.HEADER_TYPE_ACCEPT:
            case FileTransferMessage.HEADER_TYPE_OFFER:
            case FileTransferMessage.HEADER_TYPE_TRANSFER:
                return FileTransferMessage.fromHeadersAndPayload(headers, payload);

        }
        return null;
    }

    // <editor-fold desc="JSON Utils">

    public static HashMap<String, Object> toMap(JSONObject object) throws JSONException {
        HashMap<String, Object> map = new HashMap();
        Iterator keys = object.keys();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            map.put(key, fromJson(object.get(key)));
        }
        return map;
    }

    private static Object fromJson(Object json) throws JSONException {
        if (json == JSONObject.NULL) {
            return null;
        } else if (json instanceof JSONObject) {
            return toMap((JSONObject) json);
        } else if (json instanceof JSONArray) {
            return toList((JSONArray) json);
        } else {
            return json;
        }
    }

    public static List toList(JSONArray array) throws JSONException {
        List list = new ArrayList();
        for (int i = 0; i < array.length(); i++) {
            list.add(fromJson(array.get(i)));
        }
        return list;
    }

    // </editor-fold desc="JSON Utils">
}
