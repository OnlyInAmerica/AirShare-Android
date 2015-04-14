package pro.dbro.airshare.sample.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toolbar;

import com.nispok.snackbar.Snackbar;

import java.util.HashMap;

import pro.dbro.airshare.app.ui.PeerFragment;
import pro.dbro.airshare.sample.PrefsManager;
import pro.dbro.airshare.sample.R;
import pro.dbro.airshare.sample.ui.fragment.QuoteWritingFragment;
import pro.dbro.airshare.sample.ui.fragment.WelcomeFragment;
import pro.dbro.airshare.session.Peer;
import timber.log.Timber;

public class MainActivity extends Activity implements Toolbar.OnMenuItemClickListener,
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
                getFragmentManager().popBackStack();
            }
        });

        Fragment baseFragment;

        if (PrefsManager.needsUsername(this)) {
            baseFragment = new WelcomeFragment();
            receiveMenuItem.setVisible(false);
        } else
            baseFragment = new QuoteWritingFragment();


        getFragmentManager().beginTransaction()
                .replace(R.id.frame, baseFragment)
                .commit();

        getFragmentManager().addOnBackStackChangedListener(new FragmentManager.OnBackStackChangedListener() {
            @Override
            public void onBackStackChanged() {
                int numEntries = getFragmentManager().getBackStackEntryCount();
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

        getFragmentManager().beginTransaction()
                .replace(R.id.frame, PeerFragment.toSend(dataToShare, PrefsManager.getUsername(this), SERVICE_NAME))
                .addToBackStack(null)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .commit();

        receiveMenuItem.setVisible(false);
        toolbar.setNavigationIcon(R.mipmap.ic_cancel);
        toolbar.setTitle(getString(R.string.sending_quote));
        showSubtitle(true);
    }

    public void onReceiveButtonClick() {
        getFragmentManager().beginTransaction()
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
        getFragmentManager().beginTransaction()
                .replace(R.id.frame, new QuoteWritingFragment())
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .commit();

        receiveMenuItem.setVisible(true);
        showSubtitle(false);
    }

    @Override
    public void onDataReceived(@Nullable HashMap<String, Object> headers,
                               @Nullable byte[] data,
                               @NonNull Peer sender) {

        // In this example app, we're only using the headers data
        if (headers != null) {
            Timber.d("Got data from %s", sender.getAlias());
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(getString(R.string.quote_received))
                    .setMessage(getString(R.string.quote_and_author,
                                          headers.get("quote"),
                                          headers.get("author")))
                    .setPositiveButton(getString(R.string.ok), null)
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
            Snackbar.with(getApplicationContext())
                    .text(R.string.quote_sent)
                    .show(this);
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

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()){
            case R.id.action_receive:
                onReceiveButtonClick();
                return true;
        }

        return false;
    }
}
