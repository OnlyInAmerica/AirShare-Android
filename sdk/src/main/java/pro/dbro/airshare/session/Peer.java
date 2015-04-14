package pro.dbro.airshare.session;

import java.util.Arrays;
import java.util.Date;
import java.util.Objects;

/**
 * Created by davidbrodsky on 2/21/15.
 */
public class Peer {

    private byte[] publicKey;
    private String alias;
    private Date lastSeen;
    private int rssi;

    public Peer(byte[] publicKey,
                   String alias,
                   Date lastSeen,
                   int rssi) {

        this.publicKey = publicKey;
        this.alias = alias;
        this.lastSeen = lastSeen;
        this.rssi = rssi;
    }

    public byte[] getPublicKey() {
        return publicKey;
    }

    public String getAlias() {
        return alias;
    }

    public Date getLastSeen() {
        return lastSeen;
    }

    public int getRssi() {
        return rssi;
    }

    @Override
    public String toString() {
        return "Peer{" +
                "publicKey=" + Arrays.toString(publicKey) +
                ", alias='" + alias + '\'' +
                ", lastSeen=" + lastSeen +
                ", rssi=" + rssi +
                '}';
    }

    @Override
    public boolean equals(Object obj) {

        if(obj == this) return true;
        if(obj == null) return false;

        if (getClass().equals(obj.getClass()))
        {
            Peer other = (Peer) obj;
            return Arrays.equals(publicKey, other.publicKey);
        }

        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(publicKey);
    }
}
