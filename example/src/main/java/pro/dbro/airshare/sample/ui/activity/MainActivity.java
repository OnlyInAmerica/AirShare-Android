package pro.dbro.airshare.sample.ui.activity;

import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toolbar;

import com.nispok.snackbar.Snackbar;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;

import pro.dbro.airshare.app.ui.PeerFragment;
import pro.dbro.airshare.sample.PrefsManager;
import pro.dbro.airshare.sample.R;
import pro.dbro.airshare.sample.ui.fragment.QuoteWritingFragment;
import pro.dbro.airshare.sample.ui.fragment.WelcomeFragment;
import pro.dbro.airshare.session.Peer;
import timber.log.Timber;

/**
 * An Activity illustrating use of AirShare's {@link PeerFragment} to facilitate
 * simple synchronous data exchange.
 */
public class MainActivity extends AppCompatActivity implements Toolbar.OnMenuItemClickListener,
        WelcomeFragment.WelcomeFragmentListener,
        QuoteWritingFragment.WritingFragmentListener,
        PeerFragment.PeerFragmentListener {

    private static final String SERVICE_NAME = "AirShareDemo";

    private Toolbar toolbar;
    private MenuItem receiveMenuItem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        toolbar = (Toolbar) findViewById(R.id.toolbar);

        toolbar.inflateMenu(R.menu.activity_main);

        receiveMenuItem = toolbar.getMenu().findItem(R.id.action_receive);
        toolbar.setOnMenuItemClickListener(this);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getSupportFragmentManager().popBackStack();
            }
        });

        Fragment baseFragment;

        if (PrefsManager.needsUsername(this)) {
            baseFragment = new WelcomeFragment();
            receiveMenuItem.setVisible(false);
        } else
            baseFragment = new QuoteWritingFragment();


        getSupportFragmentManager().beginTransaction()
                .replace(R.id.frame, baseFragment)
                .commit();

        getSupportFragmentManager().addOnBackStackChangedListener(new FragmentManager.OnBackStackChangedListener() {
            @Override
            public void onBackStackChanged() {
                int numEntries = getSupportFragmentManager().getBackStackEntryCount();
                if (numEntries == 0) {
                    // Back at "Home" State (WritingFragment)
                    receiveMenuItem.setVisible(true);
                    showSubtitle(false);
                    toolbar.setTitle("");
                    toolbar.setNavigationIcon(null);
                }
            }
        });
    }

    private void showSubtitle(boolean doShow) {
        if (doShow) {
            toolbar.setSubtitle(getString(R.string.as_name, PrefsManager.getUsername(this)));
        } else
            toolbar.setSubtitle("");
    }

    @Override
    public void onUsernameSelected(String username) {
        PrefsManager.setUsername(this, username);
        showWritingFragment();
    }

    @Override
    public void onShareRequested(String quote, String author) {
        HashMap<String, Object> dataToShare = new HashMap<>();
        dataToShare.put("quote", quote);
        dataToShare.put("author", author);

        byte[] payloadToShare = new JSONObject(dataToShare).toString().getBytes();

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.frame, PeerFragment.toSend(payloadToShare, PrefsManager.getUsername(this), SERVICE_NAME))
                .addToBackStack(null)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .commit();

        receiveMenuItem.setVisible(false);
        toolbar.setNavigationIcon(R.mipmap.ic_cancel);
        toolbar.setTitle(getString(R.string.sending_quote));
        showSubtitle(true);
    }

    public void onReceiveButtonClick() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.frame, PeerFragment.toReceive(PrefsManager.getUsername(this), SERVICE_NAME))
                .addToBackStack(null)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .commit();

        receiveMenuItem.setVisible(false);
        toolbar.setNavigationIcon(R.mipmap.ic_cancel);
        toolbar.setTitle(getString(R.string.receiving_quote));
        showSubtitle(true);
    }

    private void showWritingFragment() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.frame, new QuoteWritingFragment())
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .commit();

        receiveMenuItem.setVisible(true);
        showSubtitle(false);
    }

    @Override
    public void onDataReceived(@NonNull PeerFragment fragment,
                               @Nullable byte[] data,
                               @NonNull Peer sender) {

        // In this example app, we're only using the headers data
        if (data != null) {
            try {
                JSONObject json = new JSONObject(new String(data));
                Timber.d("Got data from %s", sender.getAlias());
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(getString(R.string.quote_received))
                        .setMessage(getString(R.string.quote_and_author,
                                json.get("quote"),
                                json.get("author")))
                        .setPositiveButton(getString(R.string.ok), null)
                        .show();
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onDataSent(@NonNull PeerFragment fragment,
                           @Nullable byte[] data,
                           @NonNull Peer recipient) {

        // In this example app, we're only using the headers data
        if (data != null) {
            Timber.d("Sent data to %s", recipient.getAlias());
            Snackbar.with(getApplicationContext())
                    .text(R.string.quote_sent)
                    .show(this);
        }
    }

    @Override
    public void onDataRequestedForPeer(@NonNull PeerFragment fragment, @NonNull Peer recipient) {
        // unused. If we were using PeerFragment in send and receive mode, we would
        // deliver data for peer:
        // fragment.sendDataToPeer("Some dynamic data".getBytes(), recipient);

    }

    @Override
    public void onFinished(@NonNull PeerFragment fragment, Exception exception) {
        // Remove last fragment
        getSupportFragmentManager().popBackStack();
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_receive:
                onReceiveButtonClick();
                return true;
        }

        return false;
    }
}
