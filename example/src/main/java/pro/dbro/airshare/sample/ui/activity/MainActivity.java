package pro.dbro.airshare.sample.ui.activity;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import java.util.HashMap;

import pro.dbro.airshare.app.ui.PeerFragment;
import pro.dbro.airshare.sample.R;
import pro.dbro.airshare.sample.ui.fragment.WelcomeFragment;
import pro.dbro.airshare.sample.ui.fragment.WritingFragment;
import pro.dbro.airshare.session.Peer;
import timber.log.Timber;

public class MainActivity extends Activity implements WelcomeFragment.WelcomeFragmentListener,
                                                      WritingFragment.WritingFragmentListener,
                                                      PeerFragment.PeerFragmentListener {

    private static final String SERVICE_NAME = "AirShareDemo";

    private String username;
    private Button receiveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        receiveButton = (Button) findViewById(R.id.receive_button);

        getFragmentManager().beginTransaction()
                .replace(R.id.frame, new WelcomeFragment())
                .commit();
    }

    @Override
    public void onUsernameSelected(String username) {
        this.username = username;

        showWritingFragment();
    }

    @Override
    public void onShareRequested(String shareText) {
        HashMap<String, Object> dataToShare = new HashMap<>();
        dataToShare.put("text", shareText);

        getFragmentManager().beginTransaction()
                .replace(R.id.frame, PeerFragment.toSend(dataToShare, username, SERVICE_NAME))
                .commit();

        receiveButton.setVisibility(View.GONE);
    }

    public void onReceiveButtonClick(View button) {
        getFragmentManager().beginTransaction()
                .replace(R.id.frame, PeerFragment.toReceive(username, SERVICE_NAME))
                .commit();

        receiveButton.setVisibility(View.GONE);
    }

    private void showWritingFragment() {
        getFragmentManager().beginTransaction()
                .replace(R.id.frame, new WritingFragment())
                .commit();

        receiveButton.setVisibility(View.VISIBLE);
    }

    @Override
    public void onDataReceived(HashMap<String, Object> data, Peer sender) {
        Timber.d("Got data from %s", sender.getAlias());
        // TODO : Display data in a dialog etc?
    }

    @Override
    public void onDataSent(HashMap<String, Object> data, Peer recipient) {
        Timber.d("Sent data to %s", recipient.getAlias());
    }

    @Override
    public void onDataRequestedForPeer(Peer recipient) {
        // unused
    }

    @Override
    public void onFinished(Exception exception) {
        showWritingFragment();
    }
}
