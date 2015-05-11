package pro.dbro.airshare.sample.ui.activity;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;

import pro.dbro.airshare.app.AirShareService;
import pro.dbro.airshare.app.ui.AirShareFragment;
import pro.dbro.airshare.session.Peer;
import pro.dbro.airshare.transport.Transport;
import pro.dbro.airshare.transport.wifi.WifiTransport;

/**
 * An Activity (currently unused in this app) illustrating advanced / manual use of
 * AirShare via {@link pro.dbro.airshare.app.ui.AirShareFragment}
 * and {@link pro.dbro.airshare.app.AirShareService.ServiceBinder}
 */
public class AdvancedUseActivity extends AppCompatActivity
        implements AirShareFragment.Callback {

    private AirShareFragment airShareFragment;
    private AirShareService.ServiceBinder airShareBinder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (airShareFragment == null) {
            airShareFragment
                    = AirShareFragment.newInstance("Alice",  // username
                                                   "MyChat", // service name
                                                   this);    // AirShareFragment.Callback

            // Control whether AirShareService's lifecyle
            // is tied to this Activity (false) or should continue
            // to operate in the background (true). Default is false.
            airShareFragment.setShouldServiceContinueInBackground(true);

            getSupportFragmentManager().beginTransaction()
                    .add(airShareFragment, "airshare")
                    .commit();
        }
    }

    /** AirShareFragment.AirShareCallback */

    public void onServiceReady(@NonNull AirShareService.ServiceBinder serviceBinder) {
        airShareBinder = serviceBinder;
        // You can now use serviceBinder to perform all sharing operations
        // and register for callbacks reporting network state.
        airShareBinder.setCallback(new AirShareService.Callback() {

            @Override
            public void onDataRecevied(@NonNull AirShareService.ServiceBinder binder,
                                       @Nullable byte[] data,
                                       @NonNull Peer sender,
                                       @Nullable Exception exception) {
                // Handle data received
            }

            @Override
            public void onDataSent(@NonNull AirShareService.ServiceBinder binder,
                                   @Nullable byte[] data,
                                   @NonNull Peer recipient,
                                   @Nullable Exception exception) {
                // Handle data sent
            }

            @Override
            public void onPeerStatusUpdated(@NonNull AirShareService.ServiceBinder binder,
                                            @NonNull Peer peer,
                                            @NonNull Transport.ConnectionStatus newStatus,
                                            boolean peerIsHost) {

                if (newStatus == Transport.ConnectionStatus.CONNECTED) {
                    airShareBinder.send("Hello!".getBytes(), peer);

                    if (peer.supportsTransportWithCode(WifiTransport.TRANSPORT_CODE))
                        airShareBinder.requestTransportUpgrade(peer);
                }
                // Handle peer disconnected
            }

            @Override
            public void onPeerTransportUpdated(@NonNull AirShareService.ServiceBinder binder,
                                               @NonNull Peer peer,
                                               int newTransportCode,
                                               @Nullable Exception exception) {
                if (exception == null) {
                    // Successfully upgraded connection with peer to WiFi Transport
                    airShareBinder.send("Hello at high speed!".getBytes(), peer);
                }
            }
        });
    }

    public void onFinished(@Nullable Exception exception) {
        // This is currently unused, but will report an error
        // initializing the AirShareService
    }
}