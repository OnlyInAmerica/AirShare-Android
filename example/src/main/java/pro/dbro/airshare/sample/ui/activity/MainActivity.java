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
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.getbase.floatingactionbutton.FloatingActionButton;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import pro.dbro.airshare.app.AirShareService;
import pro.dbro.airshare.app.IncomingTransfer;
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
                                                      AirShareService.AirSharePeerCallback,
                                                      AirShareService.AirShareReceiverCallback {

    private static final int READ_REQUEST_CODE = 42;

    private PeerFragment peerFragment;
    private AirShareService.ServiceBinder serviceBinder;
    private boolean serviceBound = false;  // Are we bound to the ChatService?
    private boolean bluetoothReceiverRegistered = false; // Are we registered for Bluetooth status broadcasts?

    private FloatingActionButton fab;

    private AlertDialog mBluetoothEnableDialog;

    private LocalPeer localPeer;
    private Peer recipientPeer;

    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (serviceBinder != null)
                    serviceBinder.advertiseLocalUser();
            }
        });

        progress = (ProgressBar) findViewById(R.id.progress);
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

        fab.setVisibility(View.VISIBLE);
    }

    private void showWelcomeFragment() {
        Timber.d("Showing welcome frag ");

        fab.setVisibility(View.GONE);

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

        if (serviceBinder != null) {
            Timber.d("Offering data to %s", peer.getAlias());
            recipientPeer = peer;
            performFileSearch();
            //serviceBinder.offer("Oh my god".getBytes(), peer);
        }
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
        serviceBinder.setReceiverCallback(this);
        serviceBinder.scanForOtherUsers();

        showPeerFragment();
    }

    @Override
    public void peerStatusUpdated(Peer peer, Transport.ConnectionStatus newStatus) {
        if (peerFragment != null)
            peerFragment.peerStatusUpdated(peer, newStatus);
    }

    @Override
    public void onTransferOffered(final IncomingTransfer transfer, final Peer sender) {

        new AlertDialog.Builder(this)
                .setTitle("Transfer Offered")
                .setMessage(String.format("%s would like to send %s (%f MB)",
                                          sender.getAlias(),
                                          transfer.getFilename(),
                                          transfer.getBodyLengthBytes() / 1024f))
                .setPositiveButton("Accept", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        transfer.accept();
                        progress.setVisibility(View.VISIBLE);
                    }
                })
                .setNegativeButton("Decline", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        transfer.decline();
                    }
                })
                .show();
    }

    @Override
    public void onTransferProgress(IncomingTransfer transfer, Peer sender, float progress) {
        this.progress.setProgress((int) progress * this.progress.getMax());
    }

    @Override
    public void onTransferComplete(IncomingTransfer transfer, Peer sender, Exception exception) {

        progress.setVisibility(View.GONE);

        Timber.d("Transfer of %s complete!", transfer.getFilename());
        if (transfer.getFilename().contains(".jpg")) {
            Bitmap bitmap = decodeSampledBitmapFromInputStream(transfer.getBody(), 640, 480);
            ImageView imageView = new ImageView(this);
            imageView.setImageBitmap(bitmap);
            String payloadStr = new String(transfer.getBodyBytes());
            new AlertDialog.Builder(this)
                    .setView(imageView)
                    .show();
        } else
            Timber.d("Transfer completed but unknown type %s", transfer.getFilename());

    }

    /**
     * Fires an intent to spin up the "file chooser" UI and select an image.
     */
    public void performFileSearch() {

        // ACTION_OPEN_DOCUMENT is the intent to choose a file via the system's file
        // browser.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);

        // Filter to only show results that can be "opened", such as a
        // file (as opposed to a list of contacts or timezones)
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        // Filter to show only images, using the image MIME data type.
        // If one wanted to search for ogg vorbis files, the type would be "audio/ogg".
        // To search for all documents available via installed storage providers,
        // it would be "*/*".
        //intent.setType("image/*");
        intent.setType("*/*");

        startActivityForResult(intent, READ_REQUEST_CODE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {

        // The ACTION_OPEN_DOCUMENT intent was sent with the request code
        // READ_REQUEST_CODE. If the request code seen here doesn't match, it's the
        // response to some other intent, and the code below shouldn't run at all.

        if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            // The document selected by the user won't be returned in the intent.
            // Instead, a URI to that document will be contained in the return intent
            // provided to this method as a parameter.
            // Pull that URI using resultData.getData().
            Uri uri = null;
            if (resultData != null) {
                uri = resultData.getData();
                if (serviceBinder != null) {

                    String assetName = null;
                    String sizeStr = null;
                    // The query, since it only applies to a single document, will only return
                    // one row. There's no need to filter, sort, or select fields, since we want
                    // all fields for one document.
                    Cursor cursor = getContentResolver().query(uri, null, null, null, null, null);

                    try {
                        // moveToFirst() returns false if the cursor has 0 rows.  Very handy for
                        // "if there's anything to look at, look at it" conditionals.
                        if (cursor != null && cursor.moveToFirst()) {

                            // Note it's called "Display Name".  This is
                            // provider-specific, and might not necessarily be the file name.
                            assetName = cursor.getString(
                                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                            Timber.d("Display Name: " + assetName);

                            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                            // If the size is unknown, the value stored is null.  But since an
                            // int can't be null in Java, the behavior is implementation-specific,
                            // which is just a fancy term for "unpredictable".  So as
                            // a rule, check if it's null before assigning to an int.  This will
                            // happen often:  The storage API allows for remote files, whose
                            // size might not be locally known.
                            sizeStr = null;
                            if (!cursor.isNull(sizeIndex)) {
                                // Technically the column stores an int, but cursor.getString()
                                // will do the conversion automatically.
                                sizeStr = cursor.getString(sizeIndex);
                            } else {
                                sizeStr = "Unknown";
                            }
                            Timber.d("Size: " + sizeStr);
                        }

                        int size = Integer.parseInt(sizeStr);

                        Timber.d("Sending %s (%d bytes) to %s", assetName, size, recipientPeer.getAlias());

                        serviceBinder.offer(getContentResolver().openInputStream(uri),
                                size,
                                assetName,
                                recipientPeer);

                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    } finally {
                        cursor.close();
                    }
                }
            }
        }
    }

    private static @Nullable
    Bitmap decodeSampledBitmapFromInputStream(@NonNull InputStream media,
                                              int reqWidth,
                                              int reqHeight) {

        final BitmapFactory.Options options = new BitmapFactory.Options();

        // First decode with inJustDecodeBounds=true to check dimensions
        // this does not allocate any memory for image data
        if (media.markSupported()) {
            media.mark(Integer.MAX_VALUE);
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(media, null, options);
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            try {
                media.reset();
            } catch (IOException e) {
                e.printStackTrace();
                Timber.e("Failed to rewind InputStream. Could not generate thumbnail");
                return null;
            }
        } else {
            Timber.w("InputStream does not support marking. Thumbnail will be full size");
        }

        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeStream(media, null, options);
    }

    /**
     * Calculating a scaling factor for loading a downsampled
     * Bitmap to be at least (reWidth x reqHeight)
     */
    private static int calculateInSampleSize(
            BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {

            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) > reqHeight
                    && (halfWidth / inSampleSize) > reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }
}
