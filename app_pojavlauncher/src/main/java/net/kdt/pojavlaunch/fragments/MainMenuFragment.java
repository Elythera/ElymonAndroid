package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.elythera.elymon.ui.ElymonHomeStatus;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;

// ELYMON: the Elymon home screen. Upstream's menu (wiki, Discord, custom controls, .jar
// runner, share logs, game folder), the profile spinner and its edit button are gone: the
// only profile is Elymon's (ElymonProfile, set up by TestStorageActivity before this screen,
// which replaces the spinner's side effects), and the useful entries moved to the settings.
// Without the spinner, the profile editor, the mod-loader installers and the modpack
// screens have no way in.
public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private TextView mStatusView;

    public MainMenuFragment(){
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mPlayButton = view.findViewById(R.id.play_button);
        mStatusView = view.findViewById(R.id.elymon_home_status);

        // ELYMON: no Sodium warning. Elymon ships Sodium by design, and the upstream dialog offered to
        // delete it (and Iris) from the instance, which the next sync would download again.
        mPlayButton.setOnClickListener(v -> ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));
    }

    @Override
    public void onResume() {
        super.onResume();
        // ELYMON: app and pack versions; the pack version changes after a sync
        ElymonHomeStatus.show(mStatusView);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mStatusView = null;
    }
}
