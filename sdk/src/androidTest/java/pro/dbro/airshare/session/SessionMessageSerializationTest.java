package pro.dbro.airshare.session;

import android.app.Application;
import android.content.res.AssetFileDescriptor;
import android.test.ApplicationTestCase;

import com.google.common.util.concurrent.AtomicDouble;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import pro.dbro.airshare.crypto.KeyPair;
import pro.dbro.airshare.crypto.SodiumShaker;
import pro.dbro.airshare.transport.ble.BLETransport;
import timber.log.Timber;

/**
 * Tests the serialization of {@link pro.dbro.airshare.session.SessionMessage}s
 * as well as their deserialization using {@link pro.dbro.airshare.session.SessionMessageReceiver}
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
        initializeDataTransferMessage(messages);
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

    private void initializeDataTransferMessage(List<SessionMessage> messages) {

        byte[] payload = ("And the end of all our exploring\n"   +
                          "Will be to arrive where we started\n" +
                          "And know the place for the first time.").getBytes();

        DataTransferMessage dataTransferMessage = new DataTransferMessage(payload);

        messages.add(dataTransferMessage);
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

    private boolean compareMessageBodies(SessionMessage first, SessionMessage second) {
        if (first.getHeaderLengthBytes() != second.getHeaderLengthBytes()) return false;

        final int BUFFER_SIZE_BYTES = 50 * 1024;
        int startIdx = first.getHeaderLengthBytes() +
                       SessionMessage.HEADER_LENGTH_BYTES +
                       SessionMessage.HEADER_VERSION_BYTES;

        int readIdx = startIdx;
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

            readIdx += thisChunk.length;
        }

        if (readIdx - startIdx > 0)
            Timber.d("Compared " + (readIdx - startIdx) + " body bytes successfully");
        return true;
    }

    public void testSerializationAndDeserialization() throws InterruptedException {

        final AtomicBoolean isComplete = new AtomicBoolean(false);

        final AtomicInteger headerReadyCount = new AtomicInteger(0);
        final AtomicInteger onCompleteCount = new AtomicInteger(0);
        final AtomicDouble lastProgress = new AtomicDouble(0);

        SessionMessageSender sender = new SessionMessageSender(messages);

        SessionMessageReceiver receiver = new SessionMessageReceiver(mContext,

                new SessionMessageReceiver.SessionMessageReceiverCallback() {

                    @Override
                    public void onHeaderReady(SessionMessageReceiver receiver, SessionMessage message) {
                        //Timber.d("SessionMessage Header ready");
                        headerReadyCount.incrementAndGet();
                    }

                    @Override
                    public void onBodyProgress(SessionMessageReceiver receiver, SessionMessage message, float progress) {
                        //Timber.d("SessionMessage received progress " + progress);
                        assertTrue(lastProgress.get() <= progress);
                        lastProgress.set(progress);
                    }

                    @Override
                    public void onComplete(SessionMessageReceiver receiver, SessionMessage deserializedMessage, Exception e) {
                        Timber.d("Deserialized Message " + deserializedMessage.getHeaders().get(SessionMessage.HEADER_TYPE));

                        SessionMessage originalMessage = messages.get(onCompleteCount.getAndIncrement());
                        assertEquals(originalMessage, deserializedMessage);
                        assertTrue(compareMessageBodies(originalMessage, deserializedMessage));
                        isComplete.set(true);
                        lastProgress.set(0);
                    }
                }
        );

        while (true) {
            byte[] chunk = sender.readNextChunk(BLETransport.MTU_BYTES);

            if (chunk == null) break;

            receiver.dataReceived(chunk);

        }

        assertEquals(onCompleteCount.get(), messages.size());
        assertEquals(headerReadyCount.get(), messages.size());

    }
}