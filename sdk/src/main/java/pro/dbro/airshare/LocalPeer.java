package pro.dbro.airshare;

import java.util.Date;

/**
 * Created by davidbrodsky on 2/21/15.
 */
public class LocalPeer extends Peer {

    byte[] privateKey;

    protected LocalPeer(byte[] publicKey,
                        String alias,
                        Date lastSeen,
                        int rssi,
                        byte[] privateKey) {

        super(publicKey, alias, lastSeen, rssi);
        this.privateKey = privateKey;
    }
}
