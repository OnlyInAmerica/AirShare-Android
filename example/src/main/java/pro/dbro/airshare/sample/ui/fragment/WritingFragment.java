package pro.dbro.airshare.sample.ui.fragment;


import android.app.Activity;
import android.os.Bundle;
import android.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import pro.dbro.airshare.sample.R;

/**
 * A simple {@link Fragment} subclass.
 */
public class WritingFragment extends Fragment {

    public static interface WritingFragmentListener {

        public void onShareRequested(String shareText);
    }

    private WritingFragmentListener listener;
    private EditText entry;


    public WritingFragment() {
        // Required empty public constructor
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View root = inflater.inflate(R.layout.fragment_writing, container, false);
        entry = (EditText) root.findViewById(R.id.entry);

        root.findViewById(R.id.share_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onShareRequested(entry.getText().toString());
            }
        });

        return root;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            listener = (WritingFragmentListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement WritingFragmentListener");
        }
    }


}
