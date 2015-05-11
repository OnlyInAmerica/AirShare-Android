package pro.dbro.airshare.session;

import android.content.Context;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;

import pro.dbro.airshare.crypto.KeyPair;
import pro.dbro.airshare.transport.wifi.WifiTransport;
import timber.log.Timber;

/**
 * Created by davidbrodsky on 2/21/15.
 */
public class LocalPeer extends Peer {

    byte[] privateKey;

    public LocalPeer(Context context,
                     KeyPair keyPair,
                     String alias) {

        super(keyPair.publicKey, alias, null, 0, 0);
        this.privateKey = keyPair.secretKey;
        transports = doesDeviceSupportWifiDirect(context) ?
                        transports | WifiTransport.TRANSPORT_CODE :
                        transports;

        Timber.d("LocalPeer supports WifiDirect %b %b", doesDeviceSupportWifiDirect(context), supportsTransportWithCode(WifiTransport.TRANSPORT_CODE));

    }

    private static boolean doesDeviceSupportWifiDirect(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        FeatureInfo[] features = pm.getSystemAvailableFeatures();
        for (FeatureInfo info : features) {
            if (info != null && info.name != null && info.name.equalsIgnoreCase("android.hardware.wifi.direct")) {
                return true;
            }
        }
        return false;
    }
}
