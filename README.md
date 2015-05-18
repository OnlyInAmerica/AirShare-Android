# [AirShare-Android](https://github.com/OnlyInAmerica/AirShare-Android)

A library designed to ease P2P communication between Android and iOS devices. Use AirShare to build software that doesn't require the Internet to discover and interact with peers, or to create unique networks based on the geographical connections of their users.

Also see the [iOS library](https://github.com/chrisballinger/AirShare).

**Under Development : APIs subject to change**

## Motivation

To abstract away the particulars of connection negotiation with clients over radio technologies like BLE and WiFi.
P2P networking should be as simple as:

1. Express an identity for your local device's user and to the service this user belongs to
1. Express an intent to discover peers on remote devices, or make your local user discoverable
1. (Optional) When a remote peer is discovered, query available transports and upgrade transport if desired.
1. Exchange arbitrary data with discovered peers over a plain serial interface

## Requirements

+ Android 5.0
+ A device capable of Bluetooth LE peripheral and central operation

## Example Apps

+ The [example](https://github.com/OnlyInAmerica/AirShare-Android/tree/master/example) module of this repository illustrates simple synchronous sharing of structured data.
+ [BLEMeshChat](https://github.com/OnlyInAmerica/BLEMeshChat) is a more advanced example featuring background operation and store-and-forward messaging.

## Usage

### Synchronous AirDrop-Style Sharing

AirShare's `PeerFragment` provides a simple sharing experience between mobile app instances in the foreground, with the sending user manually selecting the recipient. PeerFragment provides a UI to facilitate recipient selection and reports events via `PeerFragmentListener`, which your hosting Activity must implement.


```java
public class SyncShareActivity extends Activity
                               implements PeerFragment.PeerFragmentListener {

    ...

    public void onSendButtonClicked(View v) {

        addFragment(PeerFragment.toSend("Hello!".getBytes(), // payload to send
                                        "Alice",             // username
                                        "MyChat"));          // service name
    }

    public void onReceiveButtonClicked(View v) {

        addFragment(PeerFragment.toReceive("Bob",            // username
                                           "MyChat"))        // service name
    }

    private void addFragment(Fragment fragment) {
        getFragmentManager().beginTransaction()
                            .replace(R.id.frame, fragment)
                            .addToBackStack(null)                                       // Allow user to remove fragment via Back navigation. Recommended if Fragment occupies entire screen
                            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)   // Add a simple transition
                            .commit();
    }

    /** PeerFragmentListener */

    @Override
    public void onDataReceived(@NonNull PeerFragment fragment,
                               @Nullable byte[] data,
                               @NonNull Peer sender) {

        // Handle data received from sender
    }

    @Override
    public void onDataSent(@NonNull PeerFragment fragment,
                           @Nullable byte[] data,
                           @NonNull Peer recipient) {

        // Handle data sent from sender
    }

    @Override
    public void onDataRequestedForPeer(@NonNull PeerFragment fragment,
                                       @NonNull Peer recipient) {
        // If you use PeerFragment.toSendAndReceive("username", "service"),
        // this callback will indicate the user selected a peer 
        // recipient and it is your duty to provide the data via
        // fragment.sendDataToPeer("Some dynamic data".getBytes(), recipient);
    }

    @Override
    public void onFinished(@NonNull PeerFragment fragment,
                           @Nullable Exception exception) {

        // PeerFragment is finished. Remove it.
        // If exception is not null, an error occurred
        getFragmentManager().popBackStack();
    }
}
```

### Asynchronous Sharing

AirShare's `AirShareFragment` is a non-UI fragment that facilitates binding to the `AirShareService` which gives you full control of all sharing operations.

```java
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
                    binder.send("Hello!".getBytes(), peer);

                    if (peer.supportsTransportWithCode(WifiTransport.TRANSPORT_CODE))
                        binder.requestTransportUpgrade(peer);
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
                    binder.send("Hello at high speed!".getBytes(), peer);
                }
            }
        });
    }

    public void onFinished(@Nullable Exception exception) {
        // This is currently unused, but will report an error
        // initializing the AirShareService
    }
}
```

## License

    Copyright 2015 David Brodsky

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.