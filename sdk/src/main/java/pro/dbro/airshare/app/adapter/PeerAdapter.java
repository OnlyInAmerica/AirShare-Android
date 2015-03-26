package pro.dbro.airshare.app.adapter;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;

import pro.dbro.airshare.R;
import pro.dbro.airshare.session.Peer;


/**
 * Created by davidbrodsky on 10/12/14.
 */
public class PeerAdapter extends RecyclerView.Adapter<PeerAdapter.ViewHolder> {

    private Context mContext;
    private ArrayList<Peer> peers;

    private View.OnClickListener peerClickListener;

    // Provide a reference to the type of views that you are using
    // (custom viewholder)
    public static class ViewHolder extends RecyclerView.ViewHolder {
        public TextView textView;
        public ViewGroup container;
        public ViewHolder(View v) {
            super(v);
            container = (ViewGroup) v;
            textView = (TextView) v.findViewById(R.id.text);
        }
    }

    // Provide a suitable constructor (depends on the kind of dataset)
    public PeerAdapter(Context context, ArrayList<Peer> peers) {
        this.peers = peers;
        mContext = context;
    }

    public void setOnPeerViewClickListener(View.OnClickListener listener) {
        this.peerClickListener = listener;
    }

    // Create new views (invoked by the layout manager)
    @Override
    public PeerAdapter.ViewHolder onCreateViewHolder(ViewGroup parent,
                                                     int viewType) {
        // create a new view
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.peer_item, parent, false);
        // set the view's size, margins, paddings and layout parameters
        ViewHolder holder = new ViewHolder(v);
        holder.container.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (peerClickListener != null && v.getTag() != null) {
                    peerClickListener.onClick(v);
                }
            }
        });
        return holder;
    }

    // Replace the contents of a view (invoked by the layout manager)
    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        // - get element from your dataset at this position
        // - replace the contents of the view with that element
        Peer peer = peers.get(position);
        holder.textView.setText(peer.getAlias() == null ?
                                "Unnamed (key=" + Base64.encodeToString(peer.getPublicKey(),
                                                                        Base64.DEFAULT).substring(0, 5) + "...)" :
                                peer.getAlias());
        holder.container.setTag(peer);
    }

    // Return the size of your dataset (invoked by the layout manager)
    @Override
    public int getItemCount() {
        return peers.size();
    }

    public void notifyPeerAdded(Peer peer) {
        peers.add(peer);
        notifyItemInserted(peers.size()-1);
    }

    public void notifyPeerRemoved(Peer peer) {
        int idx = peers.indexOf(peer);
        if (idx != -1) {
            peers.remove(idx);
            notifyItemRemoved(idx);
        }
    }
}