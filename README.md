# [AirShare-Android](https://github.com/OnlyInAmerica/AirShare-Android)

**Under Development : APIs subject to change**

An experiment in easing P2P communication between Android and iOS devices. This experiment requires Android 5.0 and a device capable of operation as both a Bluetooth LE peripheral and central.

Also see the [iOS library](https://github.com/chrisballinger/AirShare)

## Motivation

To abstract away the particulars of connection negotiation with clients over radio technologies like BLE and WiFi.
P2P networking should be as simple as:

1. Assign an identity to your local user and to the service this user belongs to
1. Express an intent to discover other users, or make your local user discoverable
1. Exchange arbitrary data with discovered users over a plain serial interface

## Usage

### Synchronous Sharing

AirShare's `PeerFragment` enables a simple sharing experience inspired by Apple's AirDrop. PeerFragment provides a basic UI for sender and receiver that allows senders to select nearby recipients, and recipients to see nearby senders.

You create a `PeerFragment` with one of three static creators depending on the local client's role in the exchange (Sending, Receiving, or Both):

Below is an example `Activity` illustrating comprehensive use of `PeerFragment`.

```java
public class SyncShareActivity extends Activity implements PeerFragment.PeerFragmentListener {

    ...

    public void onSendButtonClicked(View v) {

        addFragment(PeerFragment.toSend("Hello!".getBytes(), // payload to send
                                        "Alice",             // alias to advertise to other peers
                                        "MyChat"));          // service name
    }

    public void onReceiveButtonClicked(View v) {

        addFragment(PeerFragment.toReceive("Bob",            // alias to advertise to other peers
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
    public void onDataReceived(@Nullable byte[] data,
                               @NonNull Peer sender) {

        // Handle data received from sender
    }

    @Override
    public void onDataSent(@Nullable byte[] data,
                           @NonNull Peer recipient) {

        // Handle data sent from sender
    }

    @Override
    public void onDataRequestedForPeer(@NonNull Peer recipient) {
        // If you create your PeerFragment in BOTH mode, this callback
        // will indicate the user selected a peer recipient and it is your duty
        // to provide the sendDataToPeer
    }

    @Override
    public void onFinished(@Nullable Exception exception) {
        // PeerFragment is finished. Remove it.
        // If exception is not null, an error occurred
        getFragmentManager().popBackStack();
    }
}
```

### Asynchronous Sharing

    AirShare's `AirShareFragment` is a non-UI fragment that facilitates binding to the `AirShareService` which gives you full control of all sharing operations.

```java
public class AsyncShareActivity extends Activity implements AirShareFragment.AirShareCallback {

    private AirShareFragment airShareFragment;
    private AirShareService.ServiceBinder airShareBinder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (airShareFragment == null) {
            airShareFragment = AirShareFragment.newInstance(this);

            // Control whether the AirShareService is stopped when this Activity is stopped (false)
            // or should continue to operate in the background (true). Default is false.
            airShareFragment.setShouldServiceContinueInBackground(true);

            getSupportFragmentManager().beginTransaction()
                    .add(airShareFragment, "airshare")
                    .commit();
        }
    }

    /** AirShareFragment.AirShareCallback */

    public void registrationRequired() {
        airShareFragment.registerUserForService("Alice", "MyChat);
    }

    public void onServiceReady(@NonNull AirShareService.ServiceBinder serviceBinder) {
        airShareBinder = serviceBinder;
        // You can now use serviceBinder to perform all sharing operations
        // and register for callbacks reporting network state.
        airShareBinder.setCallback(new AirShareService.Callback() {

            public void onDataRecevied(byte[] data, Peer sender, Exception exception) {}

            public void onDataSent(byte[] data, Peer recipient, Exception exception) {}

            public void onPeerStatusUpdated(Peer peer, Transport.ConnectionStatus newStatus, boolean peerIsHost) {}

            public void onPeerTransportUpdated(@NonNull Peer peer, int newTransportCode, @Nullable Exception exception) {}
        }

        // Once peers are reported via onPeerTransportUpdated(Peer aPeer ...) you can send them data!
        // airShareBinder.send("Hello!".getBytes(), aPeer);

    }

    public void onFinished(@Nullable Exception exception) {
        // This is currently unused, but will report an error initializing the AirShareService
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