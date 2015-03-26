package pro.dbro.airshare.sample.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.HashMap;

import pro.dbro.airshare.app.ui.PeerFragment;
import pro.dbro.airshare.sample.R;
import pro.dbro.airshare.sample.ui.fragment.QuoteWritingFragment;
import pro.dbro.airshare.sample.ui.fragment.WelcomeFragment;
import pro.dbro.airshare.session.Peer;
import timber.log.Timber;

public class MainActivity extends Activity implements WelcomeFragment.WelcomeFragmentListener,
                                                      QuoteWritingFragment.WritingFragmentListener,
                                                      PeerFragment.PeerFragmentListener {

    private static final String SERVICE_NAME = "AirShareDemo";

    private String username;
    private Button receiveButton;
    private TextView title;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        receiveButton = (Button) findViewById(R.id.receive_button);
        title = (TextView) findViewById(R.id.title);

        getFragmentManager().beginTransaction()
                .replace(R.id.frame, new WelcomeFragment())
                .commit();

        getFragmentManager().addOnBackStackChangedListener(new FragmentManager.OnBackStackChangedListener() {
            @Override
            public void onBackStackChanged() {
                int numEntries = getFragmentManager().getBackStackEntryCount();
                if (numEntries == 0) {
                    // Back at "Home" State (WritingFragment)
                    receiveButton.setVisibility(View.VISIBLE);
                    title.setText("");
                }
            }
        });
    }

    @Override
    public void onUsernameSelected(String username) {
        this.username = username;

        showWritingFragment();
    }

    @Override
    public void onShareRequested(String quote, String author) {
        HashMap<String, Object> dataToShare = new HashMap<>();
        dataToShare.put("quote", quote);
        dataToShare.put("author", author);

        getFragmentManager().beginTransaction()
                .replace(R.id.frame, PeerFragment.toSend(dataToShare, username, SERVICE_NAME))
                .addToBackStack(null)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .commit();

        receiveButton.setVisibility(View.GONE);
        title.setText("Sending Quote");
    }

    public void onReceiveButtonClick(View button) {
        getFragmentManager().beginTransaction()
                .replace(R.id.frame, PeerFragment.toReceive(username, SERVICE_NAME))
                .addToBackStack(null)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .commit();

        receiveButton.setVisibility(View.GONE);
        title.setText("Receiving Quote");
    }

    private void showWritingFragment() {
        getFragmentManager().beginTransaction()
                .replace(R.id.frame, new QuoteWritingFragment())
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .commit();

        receiveButton.setVisibility(View.VISIBLE);
    }

    @Override
    public void onDataReceived(@Nullable HashMap<String, Object> headers,
                               @Nullable byte[] data,
                               @NonNull Peer sender) {

        // In this example app, we're only using the headers data
        if (headers != null) {
            Timber.d("Got data from %s", sender.getAlias());
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Quote Received")
                    .setMessage(String.format("'%s'\n by %s", headers.get("quote"), headers.get("author")))
                    .setPositiveButton("Ok", null)
                    .show();
        }
    }

    @Override
    public void onDataSent(@Nullable HashMap<String, Object> headers,
                           @Nullable byte[] data,
                           @NonNull Peer recipient) {

        // In this example app, we're only using the headers data
        if (headers != null) {
            Timber.d("Sent data to %s", recipient.getAlias());
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Quote Sent")
                    .setMessage(String.format("'%s'\n by %s", headers.get("quote"), headers.get("author")))
                    .setPositiveButton("Ok", null)
                    .show();
        }
    }

    @Override
    public void onDataRequestedForPeer(@NonNull Peer recipient) {
        // unused
    }

    @Override
    public void onFinished(Exception exception) {
        // Remove last fragment
        getFragmentManager().popBackStack();
    }
}
