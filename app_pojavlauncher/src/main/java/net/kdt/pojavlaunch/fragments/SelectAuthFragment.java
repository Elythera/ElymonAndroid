package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

// ELYMON: Elymon only takes Microsoft accounts, so this chooser opens the Microsoft sign-in
// by itself and hides the local account button. LauncherActivity still routes "add an
// account" here; routing it straight to MicrosoftLoginFragment would make this class unused.
public class SelectAuthFragment extends Fragment {
    public static final String TAG = "AUTH_SELECT_FRAGMENT";
    private boolean mRedirected; // ELYMON

    public SelectAuthFragment(){
        super(R.layout.fragment_select_auth_method);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mMicrosoftButton = view.findViewById(R.id.button_microsoft_authentication);
        Button mLocalButton = view.findViewById(R.id.button_local_authentication);

        mMicrosoftButton.setOnClickListener(v -> openMicrosoftLogin());
        // ELYMON: no local (offline) account can join Elymon; the button stays in the layout, hidden and inert.
        mLocalButton.setVisibility(View.GONE);
    }

    // ELYMON: go straight to the Microsoft sign-in.
    @Override
    public void onResume() {
        super.onResume();
        openMicrosoftLogin();
    }

    // ELYMON: this chooser is replaced rather than kept under the sign-in page, so Back from
    // the sign-in returns to the main menu instead of landing here and bouncing forward again.
    private void openMicrosoftLogin() {
        FragmentActivity activity = getActivity();
        if(activity == null || mRedirected) return;
        FragmentManager fragmentManager = getParentFragmentManager();
        if(fragmentManager.isStateSaved()) return;
        mRedirected = true;
        fragmentManager.popBackStack();
        Tools.swapFragment(activity, MicrosoftLoginFragment.class, MicrosoftLoginFragment.TAG, null);
    }
}
