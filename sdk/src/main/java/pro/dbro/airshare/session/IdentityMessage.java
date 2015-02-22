package pro.dbro.airshare.session;

import java.util.HashMap;

import pro.dbro.airshare.LocalPeer;

/**
 * Created by davidbrodsky on 2/22/15.
 */
public class IdentityMessage extends SessionMessage {

    private LocalPeer localPeer;

    public IdentityMessage(LocalPeer localPeer) {
        super();
        this.localPeer = localPeer;
    }

    @Override
    protected HashMap<String, Object> populateHeaders() {
        HashMap<String, Object> headerMap = super.populateHeaders();

        headerMap.put("alias", localPeer.getAlias());
        headerMap.put("pubkey", localPeer.getPublicKey());

        return headerMap;
    }

    @Override
    public byte[] getPayloadDataAtOffset(int offset, int length) {
        return new byte[0];
    }

}
