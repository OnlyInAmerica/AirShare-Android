package pro.dbro.airshare.sample.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import pro.dbro.airshare.app.AirShareService;
import pro.dbro.airshare.sample.AirShareSampleApp;
import pro.dbro.airshare.sample.R;
import pro.dbro.airshare.sample.ui.fragment.PeerFragment;
import pro.dbro.airshare.sample.ui.fragment.WelcomeFragment;
import pro.dbro.airshare.session.LocalPeer;
import pro.dbro.airshare.session.Peer;
import pro.dbro.airshare.transport.Transport;
import pro.dbro.airshare.transport.ble.BLEUtil;
import timber.log.Timber;


public class MainActivity extends Activity implements WelcomeFragment.WelcomeFragmentListener,
                                                      PeerFragment.PeerFragmentListener,
                                                      ServiceConnection,
                                                      AirShareService.AirSharePeerCallback {

    private PeerFragment peerFragment;
    private AirShareService.ServiceBinder serviceBinder;
    private boolean serviceBound = false;  // Are we bound to the ChatService?
    private boolean bluetoothReceiverRegistered = false; // Are we registered for Bluetooth status broadcasts?

    private AlertDialog mBluetoothEnableDialog;

    private LocalPeer localPeer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!serviceBound) {
            startAndBindToService();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (serviceBinder != null) {
            serviceBinder.setActivityReceivingMessages(true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (serviceBinder != null) {
            serviceBinder.setActivityReceivingMessages(false);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (!serviceBound) {
            unBindService();
        }
    }

    private void showPeerFragment() {
        peerFragment = new PeerFragment();

        getFragmentManager().beginTransaction()
                .replace(R.id.frame, peerFragment)
                .commit();
    }

    private void showWelcomeFragment() {
        Timber.d("Showing welcome frag ");
        getFragmentManager().beginTransaction()
                .replace(R.id.frame, new WelcomeFragment())
                .commit();
    }

    private void startAndBindToService() {
        Timber.d("Starting service");
        Intent intent = new Intent(this, AirShareService.class);
        startService(intent);
        bindService(intent, this, 0);
    }

    private void unBindService() {
        unbindService(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onPeerSelected(Peer peer) {
        // Connect to peer, or whatever
    }

    private final BroadcastReceiver mBluetoothBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        break;
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        break;
                    case BluetoothAdapter.STATE_ON:
                        if (mBluetoothEnableDialog != null && mBluetoothEnableDialog.isShowing()) {
                            mBluetoothEnableDialog.dismiss();
                        }
                        Timber.d("Bluetooth enabled");
                        checkDevicePreconditions();
                        break;
                    case BluetoothAdapter.STATE_TURNING_ON:
                        break;
                }
            }
        }
    };

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
        serviceBinder = (AirShareService.ServiceBinder) iBinder;
        serviceBound = true;
        Timber.d("Bound to service");
        checkDevicePreconditions();

        serviceBinder.setActivityReceivingMessages(true);

//        ((Switch) findViewById(R.id.onlineSwitch)).setChecked(true);
//        findViewById(R.id.onlineSwitch).setEnabled(true);
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        Timber.d("Unbound from service");
        serviceBinder = null;
        serviceBound = false;
//        ((Switch) findViewById(R.id.onlineSwitch)).setChecked(false);
    }

    private void checkDevicePreconditions() {
        if (!BLEUtil.isBluetoothEnabled(this)) {
            // Bluetooth is not Enabled.
            // await result in OnActivityResult
            registerBroadcastReceiver();
            showEnableBluetoothDialog();
        } else {
            // Bluetooth Enabled, Check if primary identity is created
            // TODO : Persist localPeer
            localPeer = serviceBinder.getLocalPeer();
            if (localPeer == null) {
                showWelcomeFragment();
            } else {
                serviceBinder.scanForOtherUsers();
                showPeerFragment();
            }
        }
    }

    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        this.registerReceiver(mBluetoothBroadcastReceiver, filter);
        bluetoothReceiverRegistered = true;
    }

    private void showEnableBluetoothDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enable Bluetooth")
                .setMessage("This app requires Bluetooth on to function. May we enable Bluetooth?")
                .setPositiveButton("Enable Bluetooth", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        mBluetoothEnableDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                        mBluetoothEnableDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                        ((TextView) mBluetoothEnableDialog.findViewById(android.R.id.message)).setText("Enabling...");
                        BLEUtil.getManager(MainActivity.this).getAdapter().enable();
                    }
                })
                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        MainActivity.this.finish();
                    }
                });
        builder.setCancelable(false);
        mBluetoothEnableDialog = builder.create();

        mBluetoothEnableDialog.show();
    }

    @Override
    public void onUsernameSelected(String username) {
        Timber.d("Username selected %s", username);

        serviceBinder.registerLocalUserWithService(username, AirShareSampleApp.AIR_SHARE_SERVICE_NAME);
        serviceBinder.setPeerCallback(this);
        serviceBinder.scanForOtherUsers();

        showPeerFragment();
    }

    @Override
    public void peerStatusUpdated(Peer peer, Transport.ConnectionStatus newStatus) {
        if (peerFragment != null)
            peerFragment.peerStatusUpdated(peer, newStatus);
    }
}
