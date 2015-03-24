package pro.dbro.airshare.sample.ui.fragment;

import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import pro.dbro.airshare.sample.R;

public class WelcomeFragment extends Fragment {

    private WelcomeFragmentListener listener;

    private EditText username;

    public WelcomeFragment() {
        // Required empty public constructor
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View root = inflater.inflate(R.layout.fragment_welcome, container, false);
        EditText entry = (EditText) root.findViewById(R.id.quote_entry);
        entry.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    listener.onUsernameSelected(v.getText().toString());
                }
                return false;
            }
        });
        return root;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            listener = (WelcomeFragmentListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement WelcomeFragmentListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }

    public interface WelcomeFragmentListener {
        public void onUsernameSelected(String username);
    }

}
