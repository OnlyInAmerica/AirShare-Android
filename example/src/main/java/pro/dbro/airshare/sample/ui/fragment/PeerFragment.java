package pro.dbro.airshare.sample.ui.fragment;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;

import pro.dbro.airshare.app.AirShareService;
import pro.dbro.airshare.sample.ui.adapter.PeerAdapter;
import pro.dbro.airshare.session.Peer;
import pro.dbro.airshare.transport.Transport;

public class PeerFragment extends Fragment implements AirShareService.AirSharePeerCallback {

    private RecyclerView recyclerView;
    private PeerAdapter peerAdapter;

    private PeerFragmentListener mListener;

    public PeerFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
//        if (getArguments() != null) {
//            mParam1 = getArguments().getString(ARG_PARAM1);
//            mParam2 = getArguments().getString(ARG_PARAM2);
//        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        Context context = getActivity();
        peerAdapter = new PeerAdapter(context, new ArrayList<Peer>());
        recyclerView = new RecyclerView(context);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setAdapter(peerAdapter);

        peerAdapter.setOnPeerViewClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mListener.onPeerSelected((Peer) v.getTag());
            }
        });
        return recyclerView;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            mListener = (PeerFragmentListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement PeerFragmentListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    @Override
    public void peerStatusUpdated(Peer peer, Transport.ConnectionStatus newStatus) {
        switch (newStatus) {
            case CONNECTED:
                peerAdapter.notifyPeerAdded(peer);
                break;

            case DISCONNECTED:
                peerAdapter.notifyPeerRemoved(peer);
                break;
        }
    }

    public interface PeerFragmentListener {

        public void onPeerSelected(Peer peer);
    }

}
