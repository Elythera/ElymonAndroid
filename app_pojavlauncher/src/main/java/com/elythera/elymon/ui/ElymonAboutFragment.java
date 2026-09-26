package com.elythera.elymon.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.prefs.screens.LauncherPreferenceFragment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * "À propos et licences": version, where the source is (LGPL-3.0 requires it to be
 * available), the projects Elymon comes from, the third-party components with their
 * licences, the licence texts bundled in assets/licenses, and the Mojang notice.
 * Opened from the Elymon section of the settings (pref_main.xml).
 */
public class ElymonAboutFragment extends LauncherPreferenceFragment {
    private static final String TAG = "ElymonAbout";
    private static final String LICENSES_ASSET_DIR = "licenses";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.pref_elymon_about);
        Context context = requireContext();

        Preference app = findPreference("elymon_about_app");
        if (app != null) {
            app.setSummary(getString(R.string.elymon_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
                    + "\n" + getString(R.string.elymon_about_tagline));
        }
        link(findPreference("elymon_about_source"), R.string.elymon_about_source_url);
        link(findPreference("elymon_about_license"), R.string.elymon_about_license_url);
        link(findPreference("elymon_about_amethyst"), R.string.elymon_about_amethyst_url);
        link(findPreference("elymon_about_pojav"), R.string.elymon_about_pojav_url);

        PreferenceCategory components = findPreference("elymon_about_components");
        if (components != null) {
            String[] names = getResources().getStringArray(R.array.elymon_about_component_names);
            String[] licenses = getResources().getStringArray(R.array.elymon_about_component_licenses);
            String[] urls = getResources().getStringArray(R.array.elymon_about_component_urls);
            int count = Math.min(names.length, Math.min(licenses.length, urls.length));
            for (int i = 0; i < count; i++) {
                Preference component = newItem(context, names[i], licenses[i]);
                final String url = urls[i];
                component.setOnPreferenceClickListener(p -> openUrl(url));
                components.addPreference(component);
            }
        }

        PreferenceCategory files = findPreference("elymon_about_files");
        if (files != null) {
            String[] names = listLicenseFiles(context);
            for (String name : names) {
                Preference file = newItem(context, name, getString(R.string.elymon_about_file_summary));
                file.setOnPreferenceClickListener(p -> showLicenseFile(name));
                files.addPreference(file);
            }
            files.setVisible(names.length > 0);
        }
    }

    private static Preference newItem(Context context, String title, String summary) {
        Preference preference = new Preference(context);
        preference.setPersistent(false);
        preference.setTitle(title);
        preference.setSummary(summary);
        return preference;
    }

    private void link(Preference preference, @StringRes int url) {
        if (preference != null) {
            final String target = getString(url);
            preference.setOnPreferenceClickListener(p -> openUrl(target));
        }
    }

    private boolean openUrl(@NonNull String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.elymon_about_no_browser, Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private static String[] listLicenseFiles(Context context) {
        try {
            String[] names = context.getAssets().list(LICENSES_ASSET_DIR);
            return names != null ? names : new String[0];
        } catch (IOException e) {
            Log.w(TAG, "assets/" + LICENSES_ASSET_DIR + " illisible", e);
            return new String[0];
        }
    }

    private boolean showLicenseFile(String name) {
        String text;
        try (InputStream in = requireContext().getAssets().open(LICENSES_ASSET_DIR + "/" + name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "Licence illisible : " + name, e);
            Toast.makeText(requireContext(), R.string.elymon_about_file_error, Toast.LENGTH_LONG).show();
            return true;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(name)
                .setMessage(text)
                .setPositiveButton(R.string.elymon_about_close, null)
                .show();
        return true;
    }
}
