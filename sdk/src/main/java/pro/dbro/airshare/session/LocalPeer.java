package pro.dbro.airshare.session;

import java.util.Date;

import pro.dbro.airshare.session.Peer;

/**
 * Created by davidbrodsky on 2/21/15.
 */
public class LocalPeer extends Peer {

    byte[] privateKey;

    protected LocalPeer(byte[] publicKey,
                        byte[] privateKey,
                        String alias,
                        Date lastSeen,
                        int rssi) {

        super(publicKey, alias, lastSeen, rssi);
        this.privateKey = privateKey;
    }
}
