package com.elythera.elymon.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.R;

import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/**
 * Checks a downloaded APK before it is handed to PackageInstaller: it must be this app
 * (same package name), newer than the installed build and announced by the feed, and signed
 * by the same key as the installed app. Android would refuse a different key anyway; checking
 * first gives the player a clear message and never shows a system error for a bad file.
 */
final class ApkVerifier {
    private ApkVerifier() {}

    /** Null when the APK can be installed, otherwise the French reason (string resource id). */
    static Integer check(Context context, File apk, UpdateManifest manifest) {
        PackageManager pm = context.getPackageManager();
        @SuppressWarnings("deprecation")
        int flags = PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (archive == null) {
            return R.string.elymon_update_invalid_apk;
        }
        if (!context.getPackageName().equals(archive.packageName)) {
            return R.string.elymon_update_wrong_package;
        }
        long archiveVersion = archive.getLongVersionCode();
        if (archiveVersion <= BuildConfig.VERSION_CODE || archiveVersion != manifest.versionCode) {
            return R.string.elymon_update_not_newer;
        }
        PackageInfo installed;
        try {
            installed = pm.getPackageInfo(context.getPackageName(), flags);
        } catch (PackageManager.NameNotFoundException e) {
            return R.string.elymon_update_wrong_signature;
        }
        Set<String> installedSigners = currentSigners(installed);
        Set<String> archiveSigners = acceptedSigners(archive);
        if (installedSigners.isEmpty() || !archiveSigners.containsAll(installedSigners)) {
            return R.string.elymon_update_wrong_signature;
        }
        return null;
    }

    /** SHA-256 digests of the certificates the installed app is signed with now. */
    private static Set<String> currentSigners(PackageInfo info) {
        Set<String> out = new HashSet<>();
        SigningInfo signing = info.signingInfo;
        if (signing != null) {
            addAll(out, signing.getApkContentsSigners());
        }
        if (out.isEmpty()) {
            addAll(out, legacySignatures(info));
        }
        return out;
    }

    /**
     * SHA-256 digests of the certificates the new APK vouches for: its signers, plus its
     * signing history when it was signed with a rotated key (APK Signature Scheme v3).
     * A multi-signer APK has no history: its signers must then all match.
     */
    private static Set<String> acceptedSigners(PackageInfo info) {
        Set<String> out = new HashSet<>();
        SigningInfo signing = info.signingInfo;
        if (signing != null) {
            addAll(out, signing.getApkContentsSigners());
            if (!signing.hasMultipleSigners()) {
                addAll(out, signing.getSigningCertificateHistory());
            }
        }
        if (out.isEmpty()) {
            addAll(out, legacySignatures(info));
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] legacySignatures(PackageInfo info) {
        return info.signatures;
    }

    private static void addAll(Set<String> out, Signature[] signatures) {
        if (signatures == null) {
            return;
        }
        for (Signature signature : signatures) {
            if (signature != null) {
                out.add(sha256(signature.toByteArray()));
            }
        }
    }

    private static String sha256(byte[] data) {
        try {
            return UpdateHttp.hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
