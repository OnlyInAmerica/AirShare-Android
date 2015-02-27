package pro.dbro.airshare;

import android.app.Application;
import android.test.ApplicationTestCase;

import java.nio.ByteBuffer;
import java.util.Date;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import pro.dbro.airshare.crypto.KeyPair;
import pro.dbro.airshare.crypto.SodiumShaker;
import pro.dbro.airshare.session.IdentityMessage;
import pro.dbro.airshare.session.SessionMessage;
import pro.dbro.airshare.session.SessionMessageReceiver;
import pro.dbro.airshare.transport.ble.BLETransport;
import timber.log.Timber;

/**
 * <a href="http://d.android.com/tools/testing/testing_android.html">Testing Fundamentals</a>
 */
public class SessionTest extends ApplicationTestCase<Application> {

    private LocalPeer localPeer;

    public SessionTest() {
        super(Application.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        Timber.plant(new Timber.DebugTree());

        KeyPair keyPair = SodiumShaker.generateKeyPair();
        localPeer = new LocalPeer(keyPair.publicKey,
                                  keyPair.secretKey,
                                  "dbro",     // alias
                                  new Date(), // lastSeen
                                  -20);       // rssi

    }

    /**
     * Create an {@link pro.dbro.airshare.session.IdentityMessage},
     * serialize it and then deserialize it via a
     * {@link pro.dbro.airshare.session.SessionMessageReceiver}
     *
     * @throws InterruptedException
     */
    public void testSerialization() throws InterruptedException {
        final IdentityMessage originalIdentity = new IdentityMessage(localPeer);

        byte[] serializedIdentity = originalIdentity.serialize();
        ByteBuffer serializedIdentityBuffer = ByteBuffer.wrap(serializedIdentity);

        final Object lock = new Object();
        final AtomicBoolean isComplete = new AtomicBoolean(false);

        SessionMessageReceiver receiver = new SessionMessageReceiver(mContext,

            new SessionMessageReceiver.SessionMessageReceiverCallback() {
                @Override
                public void onHeaderReady(HashMap<String, Object> header) {
                    Timber.d("Header ready");
                }

                @Override
                public void onProgress(float progress) {
                    Timber.d("SessionMessage received progress " + progress);
                }

                @Override
                public void onComplete(SessionMessage message, Exception e) {

                    isComplete.set(true);
                    synchronized (lock) {
                        lock.notify();
                    }

                    IdentityMessage deserializedIdentity = (IdentityMessage) message;

                    assertEquals(originalIdentity, deserializedIdentity);

                }
            });

        int mtu = BLETransport.MTU_BYTES / 2; // Force SessionMessageReceiver to receive data in chunks
        serializedIdentityBuffer.position(0);
        while (serializedIdentityBuffer.position() < serializedIdentity.length) {
            int bytesToCopy = Math.min(mtu,
                                       serializedIdentityBuffer.capacity() - serializedIdentityBuffer.position());
            Timber.d("buffer pos " + serializedIdentityBuffer.position() + " identity length " + serializedIdentity.length + " will copy #bytes: " + bytesToCopy);
            byte[] toWrite = new byte[bytesToCopy];
            serializedIdentityBuffer.get(toWrite, 0, bytesToCopy);
            receiver.dataReceived(toWrite);
        }

        synchronized (lock) {
            while (!isComplete.get()) {
                lock.wait();
            }
        }
    }

}