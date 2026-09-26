package com.elythera.elymon.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns the modules of the Elymon entry into the files to place, the way the
 * desktop does, adapted by the Android policy. Pure: no I/O.
 *
 * Destinations (helios-core DistributionFactory.resolveLocalPath, and the
 * Elythera launcher's NeoForge deployment in processbuilder.js):
 * <ul>
 * <li>VersionManifest: versions/&lt;json id&gt;/&lt;json id&gt;.json, resolved later
 * because the id is inside the file;</li>
 * <li>ForgeHosted, Forge, Library, Fabric, LiteLoader:
 * libraries/&lt;artifact.path or Maven path&gt;;</li>
 * <li>ForgeMod: &lt;instance&gt;/mods/&lt;Maven file name&gt; (never the URL name);</li>
 * <li>File: &lt;instance&gt;/&lt;artifact.path&gt;.</li>
 * </ul>
 * A module whose "required" is {value:false, def:false} is left out with its
 * sub-modules, like a disabled optional mod on the desktop. Any path that
 * would leave its root refuses the whole profile.
 */
final class Planner {
    private Planner() {}

    static final int MAX_DEPTH = 8;
    private static final Pattern MD5 = Pattern.compile("^[0-9a-f]{32}$");

    enum Root { VERSIONS, LIBRARIES, INSTANCE }

    /** A file the distribution places. */
    static final class Item {
        final Root root;
        final String rel;
        final String url;
        /** Lower-case MD5, or null for an untracked file (written only when missing). */
        final String md5;
        /** Published size, -1 when unknown. */
        final long size;
        final String moduleType;
        final String moduleId;
        Overlay overlay;
        /** An instance path that belongs to the player: written only when missing, never claimed. */
        boolean protectedPath;

        Item(Root root, String rel, String url, String md5, long size, String moduleType, String moduleId) {
            this.root = root;
            this.rel = rel;
            this.url = url;
            this.md5 = md5;
            this.size = size;
            this.moduleType = moduleType;
            this.moduleId = moduleId;
        }

        /** Key of this file in sync-index.json. */
        String indexKey() {
            return root.name().toLowerCase(Locale.ROOT) + "/" + rel;
        }
    }

    /** The VersionManifest module, placed once its JSON is read. */
    static final class Manifest {
        final String url;
        final String md5;
        final long size;
        final String moduleId;

        Manifest(String url, String md5, long size, String moduleId) {
            this.url = url;
            this.md5 = md5;
            this.size = size;
            this.moduleId = moduleId;
        }
    }

    /** A module that refuses the whole profile. */
    static final class UnsafeException extends Exception {
        final String what;

        UnsafeException(String what) {
            super("unsafe path in module " + what);
            this.what = what;
        }
    }

    /** A module that cannot be understood (no URL, no Maven id...). */
    static final class InvalidModuleException extends Exception {
        InvalidModuleException(String reason) {
            super(reason);
        }
    }

    static final class Plan {
        final List<Item> items = new ArrayList<Item>();
        Manifest manifest;
        int modsPlanned;
        int modsExcludedByPolicy;
        int optionalExcluded;
        int filesExcludedByPolicy;
        final List<String> warnings = new ArrayList<String>();
    }

    /** Maven coordinates as helios-core's MavenUtil reads them. */
    static final class Maven {
        final String group;
        final String artifact;
        final String version;
        final String classifier;
        final String extension;

        Maven(String group, String artifact, String version, String classifier, String extension) {
            this.group = group;
            this.artifact = artifact;
            this.version = version;
            this.classifier = classifier;
            this.extension = extension;
        }

        /** group/with/slashes/artifact/version/artifact-version[-classifier].ext */
        String path() {
            return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + fileName();
        }

        String fileName() {
            return artifact + "-" + version + (classifier != null ? "-" + classifier : "") + "." + extension;
        }

        /** "group:artifact:version[:classifier][@ext]"; null when there is no version. */
        static Maven parse(String id) {
            if (id == null) {
                return null;
            }
            String main = id;
            String extension = "jar";
            int at = id.indexOf('@');
            if (at >= 0) {
                main = id.substring(0, at);
                String ext = id.substring(at + 1);
                int colon = ext.indexOf(':');
                if (colon >= 0) {
                    ext = ext.substring(0, colon);
                }
                if (!ext.isEmpty()) {
                    extension = ext;
                }
            }
            String[] parts = main.split(":", -1);
            if (parts.length < 3) {
                return null;
            }
            for (int i = 0; i < Math.min(parts.length, 4); i++) {
                if (parts[i].isEmpty()) {
                    return null;
                }
            }
            return new Maven(parts[0], parts[1], parts[2], parts.length > 3 ? parts[3] : null, extension);
        }
    }

    static Plan plan(JsonObject server, AndroidPolicy policy) throws UnsafeException, InvalidModuleException {
        Plan plan = new Plan();
        // Keyed by root + folded path: Android/data ignores case.
        Map<String, Item> byKey = new LinkedHashMap<String, Item>();
        walk(server.getAsJsonArray("modules"), 0, policy, plan, byKey);
        plan.items.addAll(byKey.values());
        for (Item item : plan.items) {
            if (item.root == Root.INSTANCE && "ForgeMod".equals(item.moduleType)) {
                plan.modsPlanned++;
            }
        }
        for (Overlay overlay : policy.overlays.values()) {
            Item target = byKey.get(Root.INSTANCE + "/" + PathGuard.fold(overlay.path));
            if (target == null || !"File".equals(target.moduleType) || target.protectedPath || target.md5 == null) {
                plan.warnings.add("overlay on " + overlay.path + " ignored: not a tracked file of the distribution");
            } else {
                target.overlay = overlay;
            }
        }
        return plan;
    }

    private static void walk(JsonArray modules, int depth, AndroidPolicy policy, Plan plan, Map<String, Item> byKey)
            throws UnsafeException, InvalidModuleException {
        if (modules == null) {
            return;
        }
        if (depth > MAX_DEPTH) {
            throw new InvalidModuleException("modules are nested too deeply");
        }
        for (JsonElement element : modules) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject module = element.getAsJsonObject();
            String id = Distribution.string(module, "id");
            String type = Distribution.string(module, "type");
            JsonObject artifact = Distribution.object(module, "artifact");
            String rawPath = artifact == null ? null : rawArtifactPath(artifact, id);
            if (rawPath != null && PathGuard.isEscaping(rawPath)) {
                throw new UnsafeException(String.valueOf(id));
            }
            JsonArray subModules = module.has("subModules") && module.get("subModules").isJsonArray()
                    ? module.getAsJsonArray("subModules") : null;

            if (isDisabledOptional(module)) {
                plan.optionalExcluded++;
                // Still refuse a profile hiding an escaping path in a disabled branch.
                checkPaths(subModules, depth + 1);
                continue;
            }
            if ("ForgeMod".equals(type)) {
                Maven maven = Maven.parse(id);
                if (maven != null && policy.isModExcluded(maven.artifact)) {
                    plan.modsExcludedByPolicy++;
                    checkPaths(subModules, depth + 1);
                    continue;
                }
            }
            place(module, id, type, artifact, rawPath, policy, plan, byKey);
            walk(subModules, depth + 1, policy, plan, byKey);
        }
    }

    /** Only the path guard, for the branches that are not placed. */
    private static void checkPaths(JsonArray modules, int depth) throws UnsafeException, InvalidModuleException {
        if (modules == null) {
            return;
        }
        if (depth > MAX_DEPTH) {
            throw new InvalidModuleException("modules are nested too deeply");
        }
        for (JsonElement element : modules) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject module = element.getAsJsonObject();
            JsonObject artifact = Distribution.object(module, "artifact");
            String id = Distribution.string(module, "id");
            String raw = artifact == null ? null : rawArtifactPath(artifact, id);
            if (raw != null && PathGuard.isEscaping(raw)) {
                throw new UnsafeException(String.valueOf(id));
            }
            if (module.has("subModules") && module.get("subModules").isJsonArray()) {
                checkPaths(module.getAsJsonArray("subModules"), depth + 1);
            }
        }
    }

    /** artifact.path when present; a non-string path counts as escaping (distroshape.js). */
    private static String rawArtifactPath(JsonObject artifact, String id) throws UnsafeException {
        if (!artifact.has("path") || artifact.get("path").isJsonNull()) {
            return null;
        }
        String path = Distribution.string(artifact, "path");
        if (path == null) {
            throw new UnsafeException(String.valueOf(id));
        }
        return path;
    }

    /** helios resolveRequired + isModEnabled: excluded only for {value:false, def:false}. */
    static boolean isDisabledOptional(JsonObject module) {
        JsonObject required = Distribution.object(module, "required");
        if (required == null) {
            return false;
        }
        boolean value = bool(required, "value", true);
        boolean def = bool(required, "def", true);
        return !value && !def;
    }

    private static boolean bool(JsonObject o, String name, boolean fallback) {
        if (!o.has(name) || !o.get(name).isJsonPrimitive() || !o.get(name).getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return o.get(name).getAsBoolean();
    }

    private static void place(JsonObject module, String id, String type, JsonObject artifact, String rawPath,
                              AndroidPolicy policy, Plan plan, Map<String, Item> byKey)
            throws UnsafeException, InvalidModuleException {
        if (type == null) {
            plan.warnings.add("module " + id + " without a type ignored");
            return;
        }
        if (artifact == null) {
            throw new InvalidModuleException("module " + id + " has no artifact");
        }
        String url = Distribution.string(artifact, "url");
        String md5 = Distribution.string(artifact, "MD5");
        if (md5 != null) {
            md5 = md5.trim().toLowerCase(Locale.ROOT);
            if (!MD5.matcher(md5).matches()) {
                throw new InvalidModuleException("module " + id + " has an invalid MD5");
            }
        }
        long size = -1;
        if (artifact.has("size") && artifact.get("size").isJsonPrimitive()
                && artifact.get("size").getAsJsonPrimitive().isNumber()) {
            size = artifact.get("size").getAsLong();
            if (size < 0) {
                size = -1;
            }
        }
        if (url == null || url.trim().isEmpty()) {
            throw new InvalidModuleException("module " + id + " has no URL");
        }

        if ("VersionManifest".equals(type)) {
            if (plan.manifest == null) {
                plan.manifest = new Manifest(url, md5, size, id);
            } else {
                plan.warnings.add("second VersionManifest " + id + " ignored");
            }
            return;
        }

        Root root;
        String rel;
        if ("ForgeHosted".equals(type) || "Forge".equals(type) || "Library".equals(type)
                || "Fabric".equals(type) || "LiteLoader".equals(type)) {
            root = Root.LIBRARIES;
            rel = rawPath != null ? rawPath : mavenPath(id);
        } else if ("ForgeMod".equals(type)) {
            root = Root.INSTANCE;
            String name;
            if (rawPath != null && !rawPath.isEmpty()) {
                // helios keeps artifact.path; the deployment uses its file name.
                String normalized = PathGuard.normalize(rawPath);
                if (normalized == null) {
                    throw new UnsafeException(String.valueOf(id));
                }
                name = normalized.substring(normalized.lastIndexOf('/') + 1);
            } else {
                Maven maven = Maven.parse(id);
                if (maven == null) {
                    throw new InvalidModuleException("module " + id + " has no Maven identifier");
                }
                name = maven.fileName();
            }
            if (!PathGuard.isPlainName(name)) {
                throw new UnsafeException(String.valueOf(id));
            }
            rel = "mods/" + name;
        } else if ("File".equals(type)) {
            root = Root.INSTANCE;
            rel = rawPath != null ? rawPath : mavenPath(id);
        } else {
            plan.warnings.add("module " + id + " of type " + type + " ignored");
            return;
        }

        String normalized = PathGuard.normalize(rel);
        if (normalized == null) {
            throw new UnsafeException(String.valueOf(id));
        }
        if (root == Root.INSTANCE && "File".equals(type) && policy.isFileExcluded(normalized)) {
            plan.filesExcludedByPolicy++;
            return;
        }
        Item item = new Item(root, normalized, url, md5, size, type, id);
        if (root == Root.INSTANCE) {
            if (PathGuard.isBookkeeping(normalized)) {
                plan.warnings.add(normalized + " ignored: it would overwrite launcher bookkeeping");
                return;
            }
            item.protectedPath = PathGuard.isNeverTouched(normalized);
        }
        String key = root + "/" + PathGuard.fold(normalized);
        Item existing = byKey.get(key);
        if (existing == null) {
            byKey.put(key, item);
        } else if ("File".equals(type) && "ForgeMod".equals(existing.moduleType)) {
            // Two sources, one folder: a name delivered as a File keeps the path (processbuilder.js).
            byKey.put(key, item);
            plan.warnings.add(normalized + " is delivered both as a mod and as a file: the file wins");
        } else {
            plan.warnings.add(normalized + " is delivered twice: the first module wins");
        }
    }

    private static String mavenPath(String id) throws UnsafeException, InvalidModuleException {
        Maven maven = Maven.parse(id);
        if (maven == null) {
            throw new InvalidModuleException("module " + id + " has neither a path nor a Maven identifier");
        }
        String path = maven.path();
        if (PathGuard.isEscaping(path)) {
            throw new UnsafeException(String.valueOf(id));
        }
        return path;
    }
}
