package pro.dbro.airshare.session;

import android.content.Context;
import android.util.Base64;

import com.google.common.base.Objects;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Representation of network identity. Closely related to {@link pro.dbro.airshare.session.Peer}
 * Created by davidbrodsky on 2/22/15.
 */
public class IdentityMessage extends SessionMessage {

    public static final String HEADER_TYPE = "identity";

    /** Header keys */
    public static final String HEADER_TRANSPORTS  = "transports";
    public static final String HEADER_PUBKEY      = "pubkey";
    public static final String HEADER_ALIAS       = "alias";

    private Peer peer;

    /**
     * Convenience creator for deserialization
     */
    public static IdentityMessage fromHeaders(Map<String, Object> headers) {
        int transports = headers.containsKey(HEADER_TRANSPORTS) ? (int) headers.get(HEADER_TRANSPORTS) : 0;

        Peer peer = new Peer(Base64.decode((String) headers.get(HEADER_PUBKEY), Base64.DEFAULT),
                             (String) headers.get(HEADER_ALIAS),
                             new Date(),
                             -1,
                             transports);

        return new IdentityMessage((String) headers.get(SessionMessage.HEADER_ID),
                                   peer);
    }

    public IdentityMessage(String id, Peer peer) {
        super(id);
        this.peer = peer;
        init();
        serializeAndCacheHeaders();
    }

    /**
     * Constructor for own identity
     * @param context Context to determine transport capabilities
     * @param peer    peer to provide keypair, alias
     */
    public IdentityMessage(Context context, Peer peer) {
        super();
        this.peer = peer;
        init();
        serializeAndCacheHeaders();
    }

    private void init() {
        type = HEADER_TYPE;
    }

    public Peer getPeer() {
        return peer;
    }

    @Override
    protected HashMap<String, Object> populateHeaders() {
        HashMap<String, Object> headerMap = super.populateHeaders();

        headerMap.put(HEADER_ALIAS, peer.getAlias());
        headerMap.put(HEADER_PUBKEY, Base64.encodeToString(peer.getPublicKey(), Base64.DEFAULT));
        headerMap.put(HEADER_TRANSPORTS, peer.getTransports());

        return headerMap;
    }

    @Override
    public byte[] getBodyAtOffset(int offset, int length) {
        return null;
    }

    @Override
    public int hashCode() {
        // If we only target API 19+, we can move to java.util.Objects.hash
        return Objects.hashCode(headers.get(HEADER_TYPE),
                                headers.get(HEADER_BODY_LENGTH),
                                headers.get(HEADER_ID),
                                headers.get(HEADER_ALIAS),
                                headers.get(HEADER_PUBKEY));
    }

    @Override
    public boolean equals(Object obj) {

        if(obj == this) return true;
        if(obj == null) return false;

        if (getClass().equals(obj.getClass()))
        {
            final IdentityMessage other = (IdentityMessage) obj;

            // If we only target API 19+, we can move to the java.util.Objects.equals
            return super.equals(obj) &&
                    Objects.equal(getHeaders().get(HEADER_PUBKEY),
                            other.getHeaders().get(HEADER_PUBKEY)) &&
                    Objects.equal(getHeaders().get(HEADER_ALIAS),
                            other.getHeaders().get(HEADER_ALIAS));
        }

        return false;
    }

}
