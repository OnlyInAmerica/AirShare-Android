package pro.dbro.airshare;

import android.app.Application;
import android.content.res.AssetFileDescriptor;
import android.test.ApplicationTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import pro.dbro.airshare.crypto.KeyPair;
import pro.dbro.airshare.crypto.SodiumShaker;
import pro.dbro.airshare.session.FileTransferMessage;
import pro.dbro.airshare.session.IdentityMessage;
import pro.dbro.airshare.session.SessionMessage;
import pro.dbro.airshare.session.SessionMessageReceiver;
import pro.dbro.airshare.transport.ble.BLETransport;
import timber.log.Timber;

/**
 * Tests the serialization and deserialization of {@link pro.dbro.airshare.session.SessionMessage}s
 */
public class SessionMessageSerializationTest extends ApplicationTestCase<Application> {

    private List<SessionMessage> messages;

    public SessionMessageSerializationTest() {
        super(Application.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        Timber.plant(new Timber.DebugTree());

        messages = new ArrayList<>();

        initializeIdentityMessage(messages);
        initializeFileTransferMessages(messages);
    }

    private void initializeIdentityMessage(List<SessionMessage> messages) {
        KeyPair keyPair = SodiumShaker.generateKeyPair();
        LocalPeer localPeer = new LocalPeer(keyPair.publicKey,
                                            keyPair.secretKey,
                                            "dbro",     // alias
                                            new Date(), // lastSeen
                                            -20);       // rssi

        messages.add(new IdentityMessage(localPeer));
    }

    private void initializeFileTransferMessages(List<SessionMessage> messages) throws IOException {
        AssetFileDescriptor fd = getContext().getAssets().openFd("cats.jpg");
        int assetLength = (int) fd.getLength();

        messages.add(
                new FileTransferMessage(getContext().getAssets().open("cats.jpg"),  // inputStream
                                        "cats.jpg",                                 // filename
                                        assetLength,                                // length
                                        FileTransferMessage.TransferType.OFFER));   // type

        messages.add(
                new FileTransferMessage(getContext().getAssets().open("cats.jpg"),  // inputStream
                                        "cats.jpg",                                 // filename
                                        assetLength,                                // length
                                        FileTransferMessage.TransferType.ACCEPT));  // type

        messages.add(
                new FileTransferMessage(getContext().getAssets().open("cats.jpg"),  // inputStream
                                        "cats.jpg",                                 // filename
                                        assetLength,                                // length
                                        FileTransferMessage.TransferType.TRANSFER));// type

    }

    public void testSerializationAndDeserialization() throws InterruptedException {

        final AtomicBoolean isComplete = new AtomicBoolean(false);

        for (final SessionMessage originalMessage : messages) {

            Timber.d(String.format("Serialized Message %s is %d bytes",
                                   originalMessage.getHeaders().get(SessionMessage.HEADER_TYPE),
                                   originalMessage.getTotalLengthBytes()));

            isComplete.set(false);

            SessionMessageReceiver receiver = new SessionMessageReceiver(mContext,

                    new SessionMessageReceiver.SessionMessageReceiverCallback() {

                        @Override
                        public void onHeaderReady(SessionMessage message) {
                            //Timber.d("SessionMessage Header ready");
                        }

                        @Override
                        public void onBodyProgress(SessionMessage message, float progress) {
                            //Timber.d("SessionMessage received progress " + progress);
                        }

                        @Override
                        public void onComplete(SessionMessage deserializedMessage, Exception e) {
                            Timber.d("Deserialized Message " + originalMessage.getHeaders().get(SessionMessage.HEADER_TYPE));

                            assertEquals(originalMessage, deserializedMessage);
                            assertTrue(compareMessageBodies(originalMessage, deserializedMessage));
                            isComplete.set(true);
                        }
                    }
            );

            int readIdx = 0;
            int serializedChunkLength = BLETransport.MTU_BYTES;
            while(true) {
                byte[] serializedChunk = originalMessage.serialize(readIdx, serializedChunkLength);

                if (serializedChunk == null)
                    break;

                readIdx += serializedChunk.length;
                receiver.dataReceived(serializedChunk);
            }

            receiver.reset();

            Timber.d(String.format("'%s' message sent %d bytes to receiver",
                    originalMessage.getHeaders().get(SessionMessage.HEADER_TYPE),
                    readIdx));

            assertTrue(String.format("'%s' message was not deserialized",
                                     originalMessage.getHeaders().get(SessionMessage.HEADER_TYPE)),
                      isComplete.get());

        }
    }

    private boolean compareMessageBodies(SessionMessage first, SessionMessage second) {
        if (first.getHeaderLengthBytes() != second.getHeaderLengthBytes()) return false;

        final int BUFFER_SIZE_BYTES = 50 * 1024;
        int readIdx = first.getHeaderLengthBytes(); // skip the header
        while(true) {
            byte[] thisChunk = first.serialize(readIdx, BUFFER_SIZE_BYTES);
            byte[] otherChunk = second.serialize(readIdx, BUFFER_SIZE_BYTES);

            if (thisChunk == null && otherChunk == null)
                break;

            if (thisChunk == null || otherChunk == null) {
                Timber.d("Payloads unequal length");
                return false; // Payloads differed in length
            }

            if (!Arrays.equals(thisChunk, otherChunk)) {
                Timber.d("Payload contents differ");
                return false; // Payload chunk differed
            }

            readIdx += BUFFER_SIZE_BYTES;
        }

        Timber.d("Compared " + (readIdx - first.getHeaderLengthBytes()) + " body bytes successfully");
        return true;
    }
}