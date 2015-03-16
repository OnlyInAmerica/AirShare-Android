package pro.dbro.airshare.session;

import java.util.Date;

import pro.dbro.airshare.crypto.KeyPair;
import pro.dbro.airshare.session.Peer;

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
