package pro.dbro.airshare.app.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;

import pro.dbro.airshare.R;
import pro.dbro.airshare.app.AirShareService;
import pro.dbro.airshare.app.adapter.PeerAdapter;
import pro.dbro.airshare.session.Peer;
import pro.dbro.airshare.transport.Transport;

/**
 * A Fragment that supports discovering peers and sending or receiving data to/from them.
 *
 * The three static creators instantiate the Fragment in one of three modes:
 *
 * <ul>
 *     <li> SEND : The Fragment is created with a username, service name and data payload that will
 *          be sent to a peer the user selects. Completion will be indicated by the callback method
 *          {@link pro.dbro.airshare.app.ui.PeerFragment.PeerFragmentListener#onFinished(Exception)}
 *     </li>
 *
 *     <li> RECEIVE : The Fragment is created with a username and service name and will await transfer
 *          from a sending peer. Completion will be indicated by the callback method
 *          {@link pro.dbro.airshare.app.ui.PeerFragment.PeerFragmentListener#onFinished(Exception)}
 *     </li>
 *
 *     <li> BOTH : The Fragment is created with a username and service name and will await transfer
 *          from a sending peer and request data to send when a receiving peer is selected.
 *          Completion will only be indicated in case of error by the callback method
 *          {@link pro.dbro.airshare.app.ui.PeerFragment.PeerFragmentListener#onFinished(Exception)}
 *     </li>
 * </ul>
 *
 * An Activity that hosts PeerFragment must implement
 * {@link pro.dbro.airshare.app.ui.PeerFragment.PeerFragmentListener}
 */
public class PeerFragment extends AirShareFragment implements AirShareService.Callback,
                                                              AirShareFragment.Callback {

    /** Bundle parameters */
    private static final String BUNDLE_USERNAME    = "user";
    private static final String BUNDLE_SERVICENAME = "service";
    private static final String BUNDLE_MODE        = "mode";
    private static final String BUNDLE_PAYLOAD     = "payload";

    public static enum Mode { SEND, RECEIVE, BOTH }

    public static interface PeerFragmentListener {

        /**
         * A transfer was received from a peer.
         * Called when mode is {@link Mode#RECEIVE} or {@link Mode#BOTH}
         */
        public void onDataReceived(@Nullable byte[] payload,
                                   @NonNull Peer sender);

        /**
         * A transfer was sent to a peer.
         * Called when mode is {@link Mode#SEND} or {@link Mode#BOTH}
         */
        public void onDataSent(@Nullable byte[] data,
                               @NonNull Peer recipient);

        /**
         * The user selected recipient to receive data. Provide that data in a call
         * to {@link #sendDataToPeer(byte[], pro.dbro.airshare.session.Peer)}
         * Called when mode is {@link Mode#BOTH}
         */
        public void onDataRequestedForPeer(@NonNull Peer recipient);

        /**
         * The fragment is complete and should be removed by the host Activity.
         *
         * If exception is null, the fragment has completed it's requested operation,
         * else an error occurred.
         */
        public void onFinished(Exception exception);

    }

    private ViewGroup emptyContainer;
    private RecyclerView recyclerView;
    private PeerAdapter peerAdapter;
    private PeerFragmentListener callback;
    private AirShareService.ServiceBinder serviceBinder;

    private Mode mode;
    private String username;
    private String serviceName;

    private byte[] payload;

    public static PeerFragment toSend(@NonNull HashMap<String, Object> toSend,
                                      @NonNull String username,
                                      @NonNull String serviceName) {
        Bundle bundle = new Bundle();
        bundle.putSerializable(BUNDLE_PAYLOAD, toSend);
        return init(bundle, Mode.SEND, username, serviceName);
    }

    public static PeerFragment toReceive(@NonNull String username,
                                         @NonNull String serviceName) {
        return init(null, Mode.RECEIVE, username, serviceName);
    }

    public static PeerFragment toSendAndReceive(@NonNull String username,
                                                @NonNull String serviceName) {
        return init(null, Mode.BOTH, username, serviceName);
    }

    private static PeerFragment init(@Nullable Bundle bundle,
                                     @NonNull Mode mode,
                                     @NonNull String username,
                                     @NonNull String serviceName) {

        if (bundle == null) bundle = new Bundle();

        bundle.putSerializable(BUNDLE_MODE, mode);
        bundle.putString(BUNDLE_USERNAME, username);
        bundle.putString(BUNDLE_SERVICENAME, serviceName);

        PeerFragment fragment = new PeerFragment();
        fragment.setArguments(bundle);
        return fragment;
    }

    public PeerFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setAirShareCallback(this);
        if (getArguments() != null) {
            mode = (Mode) getArguments().getSerializable(BUNDLE_MODE);
            username = getArguments().getString(BUNDLE_USERNAME);
            serviceName = getArguments().getString(BUNDLE_SERVICENAME);
            try {
                payload = new JSONObject((HashMap) getArguments().getSerializable(BUNDLE_PAYLOAD)).toString().getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException("Provided Map is not JSON compatible!");
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        Context context = getActivity();
        View root = inflater.inflate(R.layout.fragment_peer, container, false);
        peerAdapter = new PeerAdapter(context, new ArrayList<Peer>());
        emptyContainer = (ViewGroup) root.findViewById(R.id.empty_container);
        recyclerView = (RecyclerView) root.findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setAdapter(peerAdapter);

        peerAdapter.setOnPeerViewClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPeerSelected((Peer) v.getTag());
            }
        });
        return root;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            callback = (PeerFragmentListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement PeerFragmentListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        callback = null;
    }

    /**
     * Send data to recipient. Used when mode is {@link Mode#BOTH}.
     *
     * Should be called by a client in response to the PeerFragmentCallback method:
     * {@link pro.dbro.airshare.app.ui.PeerFragment.PeerFragmentListener#onDataRequestedForPeer(pro.dbro.airshare.session.Peer)}
     */
    public void sendDataToPeer(byte[] data, Peer recipient) {
        serviceBinder.send(data, recipient);
    }

    /** An available peer was selected from {@link pro.dbro.airshare.app.adapter.PeerAdapter} */
    public void onPeerSelected(Peer peer) {
        switch (mode) {
            case SEND:

                serviceBinder.send(payload, peer);
                break;

            case BOTH:

                callback.onDataRequestedForPeer(peer);
                break;

            case RECEIVE:
                // do nothing
                break;
        }
    }

    @Override
    public void onDataRecevied(byte[] data, Peer sender, Exception exception) {
        if (callback == null) return; // Fragment was detached but not destroyed

        callback.onDataReceived(data, sender);

        if (mode == Mode.RECEIVE)
            callback.onFinished(null);
    }

    @Override
    public void onDataSent(byte[] data, Peer recipient, Exception exception) {
        if (callback == null) return; // Fragment was detached but not destroyed
        callback.onDataSent(data, recipient);

        if (mode == Mode.SEND)
            callback.onFinished(null);
    }

    @Override
    public void onPeerStatusUpdated(Peer peer, Transport.ConnectionStatus newStatus, boolean peerIsHost) {
        switch (newStatus) {
            case CONNECTED:
                peerAdapter.notifyPeerAdded(peer);

                emptyContainer.setVisibility(View.GONE);
                break;

            case DISCONNECTED:
                peerAdapter.notifyPeerRemoved(peer);
                if (peerAdapter.getItemCount() == 0) {
                    emptyContainer.setVisibility(View.VISIBLE);
                }
                break;
        }
    }

    @Override
    public void onPeerTransportUpdated(@NonNull Peer peer, int newTransportCode, @Nullable Exception exception) {
        // do nothing for now
    }

    @Override
    public void registrationRequired() {
        registerUserForService(username, serviceName);
    }

    @Override
    public void onServiceReady(AirShareService.ServiceBinder serviceBinder) {
        this.serviceBinder = serviceBinder;
        this.serviceBinder.setCallback(this);

        switch (mode) {
            case SEND:
                this.serviceBinder.scanForOtherUsers();
                break;

            case RECEIVE:
                this.serviceBinder.advertiseLocalUser();
                break;

            case BOTH:
                this.serviceBinder.scanForOtherUsers();
                this.serviceBinder.advertiseLocalUser();
        }
    }

    @Override
    public void onFinished(Exception e) {
        if (callback == null) return; // Fragment was detached but not destroyed
        callback.onFinished(e);
    }

}
