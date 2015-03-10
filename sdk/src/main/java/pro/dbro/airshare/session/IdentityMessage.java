package pro.dbro.airshare.session;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import pro.dbro.airshare.DataUtil;
import pro.dbro.airshare.Peer;

/**
 * Created by davidbrodsky on 2/22/15.
 */
public class IdentityMessage extends SessionMessage {

    public static final String HEADER_TYPE = "identity";

    private Peer localPeer;

    /**
     * Convenience creator for deserialization
     */
    public static IdentityMessage fromHeaders(Map<String, Object> headers) {
        Peer peer = new Peer(DataUtil.hexToBytes((String) headers.get("pubkey")),
                             (String) headers.get("alias"),
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

        headerMap.put("type",   HEADER_TYPE);
        headerMap.put("alias",  localPeer.getAlias());
        headerMap.put("pubkey", DataUtil.bytesToHex(localPeer.getPublicKey()));

        return headerMap;
    }

    @Override
    public byte[] getBodyAtOffset(int offset, int length) {
        return new byte[0];
    }

}
