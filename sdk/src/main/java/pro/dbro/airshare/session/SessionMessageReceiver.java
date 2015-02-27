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
 * Created by davidbrodsky on 2/24/15.
 */
public class SessionMessageReceiver {

    public static interface SessionMessageReceiverCallback {

        public void onHeaderReady(HashMap<String, Object> header);
        public void onProgress(float progress);
        public void onComplete(SessionMessage message, Exception e);

    }

    private ByteBuffer buffer;
    private SessionMessageReceiverCallback callback;
    private File payloadFile;
    private FileOutputStream payloadStream;

    private HashMap<String, Object> headers;

    private boolean gotVersion;
    private boolean gotHeaderLength;
    private boolean gotHeader;
    private boolean gotPayload;

    private int headerLength;
    private int payloadLength;
    private int payloadBytesReceived;

    public SessionMessageReceiver(Context context, SessionMessageReceiverCallback callback) {
        buffer = ByteBuffer.allocate(5 * 1000);
        this.callback = callback;

        payloadFile = new File(context.getExternalFilesDir(null), UUID.randomUUID().toString().replace("-","") + ".payload");
        try {
            payloadStream = new FileOutputStream(payloadFile);
        } catch (FileNotFoundException e) {
            String msg = "Failed to open payload File: " + payloadFile.getAbsolutePath();
            Timber.e(e, msg);
            throw new IllegalArgumentException(msg);
        }
    }

    public void dataReceived(byte[] data) {
        if (data.length > buffer.capacity() - buffer.position())
            resizeBuffer(data.length);

        if (gotHeaderLength && buffer.position() > SessionMessage.HEADER_VERSION_BYTES + SessionMessage.HEADER_LENGTH_BYTES + headerLength) {
            try {
                payloadBytesReceived += data.length;
                payloadStream.write(data);
                if (callback != null) callback.onProgress(payloadBytesReceived / payloadLength);
            } catch (IOException e) {
                Timber.e(e, "Failed to write data to payload outputStream");
            }
        } else
            buffer.put(data);

        if (!gotVersion && buffer.position() > SessionMessage.HEADER_VERSION_BYTES) {
            // Get version int from first byte
            // Check we can deserialize this version
            int version = new BigInteger(new byte[] { buffer.get(0) }).intValue();
            if (version != SessionMessage.CURRENT_HEADER_VERSION) {
                Timber.e("Unknown SessionMessage version");
                if (callback != null)
                    callback.onComplete(null, new UnsupportedOperationException("Unknown SessionMessage version"));
                return;
            }
            gotVersion = true;
        }

        if (!gotHeaderLength && buffer.position() >
            SessionMessage.HEADER_VERSION_BYTES + SessionMessage.HEADER_LENGTH_BYTES) {

            // Get header length and store. Deserialize header when possible
            // TODO If header is above some size, copy to FileOutputStream
            headerLength = new BigInteger(new byte[] { buffer.get(1), buffer.get(2), buffer.get(3) }).intValue();
            Timber.d("Deserialized header length " + headerLength);
            gotHeaderLength = true;
        }

        if (!gotHeader && gotHeaderLength && buffer.position() ==
            SessionMessage.HEADER_VERSION_BYTES + SessionMessage.HEADER_LENGTH_BYTES +
            headerLength) {

            // Deserialize Header
            // Get payload length from header
            // If payload is above some size, copy to FileOutputStream

            // At this point, we can start reporting progress to callback
            byte[] headerString = new byte[headerLength];
            buffer.position(SessionMessage.HEADER_VERSION_BYTES + SessionMessage.HEADER_LENGTH_BYTES);
            buffer.get(headerString,
                       0,
                       headerLength);
            try {
                JSONObject jsonHeader = new JSONObject(new String(headerString, "UTF-8"));
                headers = toMap(jsonHeader);
                payloadLength = (int) headers.get(SessionMessage.HEADER_PAYLOAD_LENGTH);
                if (callback != null)
                    callback.onHeaderReady(headers);
            } catch (JSONException | UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            gotHeader = true;
        }

        if (!gotPayload && buffer.position() ==
            SessionMessage.HEADER_VERSION_BYTES + SessionMessage.HEADER_LENGTH_BYTES +
            headerLength + payloadLength) {

            // Construct appropriate SessionMessage or child object
            try {
                payloadStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (callback != null) {
                try {
                    SessionMessage message = sessionMessageFromHeaderAndPayload(headers, new FileInputStream(payloadFile));
                    callback.onComplete(message, null);
                } catch (FileNotFoundException e) {
                    Timber.e(e, "Failed to get handle on payload file for reading");
                    callback.onComplete(null, e);
                }
            }

            gotPayload = true;
        }

    }

    private void resizeBuffer(int minLength) {
        ByteBuffer newBuffer = ByteBuffer.allocate(Math.max(minLength, buffer.capacity() * 2));
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
                return FileTransferMessage.fromheadersAndPayload(headers, payload);

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
