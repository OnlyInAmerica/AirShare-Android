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
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.widget.TextView;

import pro.dbro.airshare.app.AirShareService;
import pro.dbro.airshare.session.LocalPeer;
import pro.dbro.airshare.transport.ble.BLEUtil;
import timber.log.Timber;

/**
 * Convenience fragment for interacting with AirShare. Handles connecting to AirShare Service,
 * prompting user to enable Bluetooth etc.
 *
 * Implementation classes
 * must implement {@link pro.dbro.airshare.app.ui.AirShareFragment.Callback}
 */
public class AirShareFragment extends Fragment implements ServiceConnection {

    public interface Callback {

        /**
         * Indicates AirShare is ready
         */
        void onServiceReady(@NonNull AirShareService.ServiceBinder serviceBinder);

        /**
         * Indicates the AirShare service is finished.
         * This would occur if the user declined to enable required resources like Bluetooth
         */
        void onFinished(@Nullable Exception exception);

    }

    protected static final String ARG_USERNAME = "uname";
    protected static final String ARG_SERVICENAME = "sname";

    private Callback callback;
    private String username;
    private String servicename;
    private AirShareService.ServiceBinder serviceBinder;
    private boolean didIssueServiceUnbind = false;
    private boolean serviceBound = false;  // Are we bound to the ChatService?
    private boolean bluetoothReceiverRegistered = false; // Are we registered for Bluetooth status broadcasts?
    private boolean operateInBackground = false;

    private AlertDialog mBluetoothEnableDialog;

    private LocalPeer localPeer;

    public static AirShareFragment newInstance(String username, String serviceName, Callback callback) {

        Bundle bundle = new Bundle();
        bundle.putString(ARG_USERNAME, username);
        bundle.putString(ARG_SERVICENAME, serviceName);

        AirShareFragment fragment = new AirShareFragment();
        fragment.setArguments(bundle);
        fragment.setAirShareCallback(callback);
        return fragment;
    }

    public AirShareFragment() {
        super();
    }

    public void setAirShareCallback(Callback callback) {
        this.callback = callback;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Retain our instance across Activity re-creations unless added to back stack
        setRetainInstance(true);
        serviceBound = false; // onServiceDisconnected may not be called before fragment destroyed

        username = getArguments().getString(ARG_USERNAME);
        servicename = getArguments().getString(ARG_SERVICENAME);

        if (username == null || servicename == null)
            throw new IllegalStateException("username and servicename cannot be null");
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
            Timber.d("Unbinding service. %s", shouldServiceContinueInBackground() ? "service will continue in bg" : "service will be closed");
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
        return operateInBackground;
    }

    public void setShouldServiceContinueInBackground(boolean shouldContinueInBackground) {
        operateInBackground = shouldContinueInBackground;
    }

    public void stopService() {
        Timber.d("Stopping service");
        Activity host = getActivity();
        Intent intent = new Intent(host, AirShareService.class);
        host.stopService(intent);
    }

    private void startAndBindToService() {
        Timber.d("Starting service");
        Activity host = getActivity();
        Intent intent = new Intent(host, AirShareService.class);
        host.startService(intent);
        host.bindService(intent, this, 0);
    }

    private void unBindService() {
        getActivity().unbindService(this);
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
        if (!BLEUtil.isBluetoothEnabled(getActivity())) {
            // Bluetooth is not Enabled.
            // await result in OnActivityResult
            registerBroadcastReceiver();
            showEnableBluetoothDialog();
        } else {
            // Bluetooth Enabled, Register primary identity
            serviceBinder.registerLocalUserWithService(username, servicename);

            if (callback != null) callback.onServiceReady(serviceBinder);
        }
    }

    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        getActivity().registerReceiver(mBluetoothBroadcastReceiver, filter);
        bluetoothReceiverRegistered = true;
    }

    private void unregisterBroadcastReceiver() {
        if (bluetoothReceiverRegistered) {
            getActivity().unregisterReceiver(mBluetoothBroadcastReceiver);
            bluetoothReceiverRegistered = false;
        }
    }

    private void showEnableBluetoothDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle("Enable Bluetooth")
                .setMessage("This app requires Bluetooth on to function. May we enable Bluetooth?")
                .setPositiveButton("Enable Bluetooth", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        mBluetoothEnableDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                        mBluetoothEnableDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                        ((TextView) mBluetoothEnableDialog.findViewById(android.R.id.message)).setText("Enabling...");
                        BLEUtil.getManager(AirShareFragment.this.getActivity()).getAdapter().enable();
                    }
                })
                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        if (callback != null)
                            callback.onFinished(new UnsupportedOperationException("User declined to enable Bluetooth"));
                    }
                });
        builder.setCancelable(false);
        mBluetoothEnableDialog = builder.create();

        mBluetoothEnableDialog.show();
    }

}
