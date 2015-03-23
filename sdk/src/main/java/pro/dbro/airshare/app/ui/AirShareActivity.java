package pro.dbro.airshare.app.ui;

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
import android.widget.TextView;

import pro.dbro.airshare.app.AirShareService;
import pro.dbro.airshare.session.LocalPeer;
import pro.dbro.airshare.transport.ble.BLEUtil;
import timber.log.Timber;

/**
 * Convenience activity for interacting with AirShare. Implementation classes
 * must implement {@link pro.dbro.airshare.app.ui.AirShareActivity.AirShareCallback}
 */
public abstract class AirShareActivity extends Activity implements ServiceConnection {

    public static interface AirShareCallback {

        /**
         * Indicates AirShare needs to be initialized with a Username
         * and Service Name via {@link #registerUserForService(String, String)}
         */
        public void registrationRequired();

        /**
         * Indicates AirShare is ready
         */
        public void onServiceReady(AirShareService.ServiceBinder serviceBinder);

    }

    private AirShareCallback callback;
    private AirShareService.ServiceBinder serviceBinder;
    private boolean didIssueServiceUnbind = false;
    private boolean serviceBound = false;  // Are we bound to the ChatService?
    private boolean bluetoothReceiverRegistered = false; // Are we registered for Bluetooth status broadcasts?

    private AlertDialog mBluetoothEnableDialog;

    private LocalPeer localPeer;

    public void setAirShareCallback(AirShareCallback callback) {
        this.callback = callback;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


    }
    @Override
    public void onStart() {
        super.onStart();
        if (!serviceBound) {
            didIssueServiceUnbind = false;
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
        if (serviceBound && !didIssueServiceUnbind) {
            didIssueServiceUnbind = true;
            unBindService();
            unregisterBroadcastReceiver();

            if (!shouldServiceContinueInBackground())
                stopService();
        }
    }

    /**
     * @return whether the AirShareService should remain active after {@link #onStop()}
     * if false, the service will be re-started on {@link #onStart()}
     */
    public boolean shouldServiceContinueInBackground() {
        return false;
    }

    public void stopService() {
        Timber.d("Stopping service");
        Intent intent = new Intent(this, AirShareService.class);
        stopService(intent);
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
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        Timber.d("Unbound from service");
        serviceBinder = null;
        serviceBound = false;
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
                if (callback != null) callback.registrationRequired();
            } else {
                if (callback != null) callback.onServiceReady(serviceBinder);
            }
        }
    }

    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        this.registerReceiver(mBluetoothBroadcastReceiver, filter);
        bluetoothReceiverRegistered = true;
    }

    private void unregisterBroadcastReceiver() {
        if (bluetoothReceiverRegistered) {
            this.unregisterReceiver(mBluetoothBroadcastReceiver);
            bluetoothReceiverRegistered = false;
        }
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
                        BLEUtil.getManager(AirShareActivity.this).getAdapter().enable();
                    }
                })
                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        AirShareActivity.this.finish();
                    }
                });
        builder.setCancelable(false);
        mBluetoothEnableDialog = builder.create();

        mBluetoothEnableDialog.show();
    }

    public void registerUserForService(String username, String serviceName) {
        Timber.d("Username selected %s", username);

        serviceBinder.registerLocalUserWithService(username, serviceName);

        callback.onServiceReady(serviceBinder);
    }

}
