package pro.dbro.airshare.session;

import pro.dbro.airshare.crypto.KeyPair;

/**
 * Created by davidbrodsky on 2/21/15.
 */
public class LocalPeer extends Peer {

    byte[] privateKey;

    public LocalPeer(KeyPair keyPair,
                        String alias) {

        super(keyPair.publicKey, alias, null, 0);
        this.privateKey = keyPair.secretKey;
    }
}
