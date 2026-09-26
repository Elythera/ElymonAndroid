package com.elythera.elymon.sync;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Every French text the sync engine shows to the player: stage labels and
 * error messages.
 *
 * The engine is pure Java and cannot read Android resources, so the texts live
 * twice: here, as defaults, and in res/values/elymon_sync_strings.xml under
 * "elymon_sync_" + key. The host tests check that both copies are equal. The
 * Android caller may plug {@link Resolver} into {@link SyncOptions#text} to
 * read them from the resources instead.
 */
public final class SyncText {

    /** Looks a text up by key ("stage_verify"...). Returns null for "use the default". */
    public interface Resolver {
        String get(String key);
    }

    public static final String STAGE_DISTRIBUTION = "stage_distribution";
    public static final String STAGE_PREPARE = "stage_prepare";
    public static final String STAGE_VERIFY = "stage_verify";
    public static final String STAGE_DOWNLOAD = "stage_download";
    public static final String STAGE_OVERLAYS = "stage_overlays";
    public static final String STAGE_OPTIONS = "stage_options";
    public static final String STAGE_PRUNE = "stage_prune";
    public static final String STAGE_DONE = "stage_done";

    public static final String ERROR_BUSY = "error_busy";
    public static final String ERROR_OPTIONS = "error_options";
    public static final String ERROR_POLICY = "error_policy";
    public static final String ERROR_UNREACHABLE = "error_unreachable";
    public static final String ERROR_SERVER_MISSING = "error_server_missing";
    public static final String ERROR_INVALID = "error_invalid";
    public static final String ERROR_UNSAFE_PATH = "error_unsafe_path";
    public static final String ERROR_NO_VERSION = "error_no_version";
    public static final String ERROR_DOWNLOAD = "error_download";
    public static final String ERROR_WRITE = "error_write";
    public static final String ERROR_SPACE = "error_space";
    public static final String ERROR_CANCELLED = "error_cancelled";
    public static final String ERROR_DECLINED = "error_declined";
    public static final String ERROR_UNEXPECTED = "error_unexpected";

    public static final String SIZE_BYTES = "size_bytes";
    public static final String SIZE_KB = "size_kb";
    public static final String SIZE_MB = "size_mb";
    public static final String SIZE_GB = "size_gb";

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<String, String>();

    static {
        DEFAULTS.put(STAGE_DISTRIBUTION, "Récupération de la distribution");
        DEFAULTS.put(STAGE_PREPARE, "Préparation de l'installation");
        DEFAULTS.put(STAGE_VERIFY, "Vérification des fichiers");
        DEFAULTS.put(STAGE_DOWNLOAD, "Téléchargement (%1$d/%2$d)");
        DEFAULTS.put(STAGE_OVERLAYS, "Application des réglages Android");
        DEFAULTS.put(STAGE_OPTIONS, "Préparation des options du jeu");
        DEFAULTS.put(STAGE_PRUNE, "Nettoyage des anciens fichiers");
        DEFAULTS.put(STAGE_DONE, "Fichiers à jour");

        DEFAULTS.put(ERROR_BUSY, "Une installation d'Elymon est déjà en cours.");
        DEFAULTS.put(ERROR_OPTIONS, "L'installation d'Elymon est mal configurée dans l'application.");
        DEFAULTS.put(ERROR_POLICY, "Les réglages Android d'Elymon sont illisibles. Réinstallez l'application.");
        DEFAULTS.put(ERROR_UNREACHABLE, "Impossible de récupérer la liste des fichiers d'Elymon, et aucune copie n'est enregistrée sur l'appareil. Vérifiez votre connexion Internet puis réessayez.");
        DEFAULTS.put(ERROR_SERVER_MISSING, "Elymon est introuvable dans la distribution Elythera.");
        DEFAULTS.put(ERROR_INVALID, "La distribution d'Elymon est invalide. Réessayez plus tard ; si le problème persiste, prévenez l'équipe Elythera.");
        DEFAULTS.put(ERROR_UNSAFE_PATH, "La distribution Elythera contient un chemin de fichier dangereux (%1$s). Par sécurité, rien n'a été installé.");
        DEFAULTS.put(ERROR_NO_VERSION, "La distribution d'Elymon ne fournit pas la version NeoForge à lancer.");
        DEFAULTS.put(ERROR_DOWNLOAD, "Le téléchargement de %1$s a échoué. Vérifiez votre connexion Internet puis réessayez : les fichiers déjà téléchargés sont conservés.");
        DEFAULTS.put(ERROR_WRITE, "Impossible d'écrire %1$s sur l'appareil.");
        DEFAULTS.put(ERROR_SPACE, "Espace de stockage insuffisant : il faut %1$s, il reste %2$s.");
        DEFAULTS.put(ERROR_CANCELLED, "Installation annulée. Les fichiers déjà téléchargés sont conservés.");
        DEFAULTS.put(ERROR_DECLINED, "Téléchargement refusé. Rien n'a été téléchargé.");
        DEFAULTS.put(ERROR_UNEXPECTED, "Erreur inattendue pendant l'installation d'Elymon. Réessayez ; si le problème persiste, prévenez l'équipe Elythera.");

        DEFAULTS.put(SIZE_BYTES, "%1$s o");
        DEFAULTS.put(SIZE_KB, "%1$s Ko");
        DEFAULTS.put(SIZE_MB, "%1$s Mo");
        DEFAULTS.put(SIZE_GB, "%1$s Go");
    }

    private final Resolver resolver;

    SyncText(Resolver resolver) {
        this.resolver = resolver;
    }

    /** Every key with its French default, in declaration order (read by the tests). */
    public static Map<String, String> defaults() {
        return new LinkedHashMap<String, String>(DEFAULTS);
    }

    /** The text of a key, formatted with the arguments when there are any. */
    String get(String key, Object... args) {
        String pattern = null;
        if (resolver != null) {
            try {
                pattern = resolver.get(key);
            } catch (RuntimeException ignored) {
                pattern = null;
            }
        }
        if (pattern == null) {
            pattern = DEFAULTS.get(key);
        }
        if (pattern == null) {
            return key;
        }
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return String.format(Locale.FRANCE, pattern, args);
        } catch (RuntimeException e) {
            return pattern;
        }
    }

    /** A size the way the player reads it: "350 Mo", "1,2 Go". */
    String size(long bytes) {
        if (bytes < 1024) {
            return get(SIZE_BYTES, String.valueOf(Math.max(0, bytes)));
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return get(SIZE_KB, String.format(Locale.FRANCE, "%.0f", kb));
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return get(SIZE_MB, String.format(Locale.FRANCE, mb < 10 ? "%.1f" : "%.0f", mb));
        }
        return get(SIZE_GB, String.format(Locale.FRANCE, "%.1f", mb / 1024.0));
    }
}
