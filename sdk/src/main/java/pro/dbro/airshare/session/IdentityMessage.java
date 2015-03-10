package pro.dbro.airshare.session;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import pro.dbro.airshare.DataUtil;
import pro.dbro.airshare.Peer;

/**
 * Created by davidbrodsky on 2/22/15.
 */
public class IdentityMessage extends SessionMessage {

    public static final String HEADER_TYPE = "identity";

    /** Header keys */
    public static final String HEADER_PUBKEY = "pubkey";
    public static final String HEADER_ALIAS  = "alias";

    private Peer localPeer;

    /**
     * Convenience creator for deserialization
     */
    public static IdentityMessage fromHeaders(Map<String, Object> headers) {
        Peer peer = new Peer(DataUtil.hexToBytes((String) headers.get(HEADER_PUBKEY)),
                             (String) headers.get(HEADER_ALIAS),
                             new Date(),
                             -1);
        return new IdentityMessage((String) headers.get(SessionMessage.HEADER_ID), peer);
    }

    public IdentityMessage(String id, Peer localPeer) {
        super(id);
        this.localPeer = localPeer;
        serializeAndCacheHeaders();
    }

    public IdentityMessage(Peer localPeer) {
        super();
        this.localPeer = localPeer;
        serializeAndCacheHeaders();
    }

    public Peer getPeer() {
        return localPeer;
    }

    @Override
    protected HashMap<String, Object> populateHeaders() {
        HashMap<String, Object> headerMap = super.populateHeaders();

        headerMap.put(SessionMessage.HEADER_TYPE,   HEADER_TYPE);
        headerMap.put(HEADER_ALIAS,                 localPeer.getAlias());
        headerMap.put(HEADER_PUBKEY,                DataUtil.bytesToHex(localPeer.getPublicKey()));

        return headerMap;
    }

    @Override
    public byte[] getBodyAtOffset(int offset, int length) {
        return new byte[0];
    }

    @Override
    public int hashCode() {
        return Objects.hash(headers.get(HEADER_TYPE),
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

            return super.equals(obj) &&
                Objects.equals(getHeaders().get(HEADER_PUBKEY),
                        other.getHeaders().get(HEADER_PUBKEY)) &&
                Objects.equals(getHeaders().get(HEADER_ALIAS),
                        other.getHeaders().get(HEADER_ALIAS));
        }

        return false;
    }

}
