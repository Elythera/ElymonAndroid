package com.elythera.elymon.sync;

import com.elythera.elymon.ElymonConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One run of {@link ElymonSync#run}. Listener methods are only ever called
 * from the thread that called run(); workers publish their progress in atomic
 * counters that this thread reports while it waits for them.
 */
final class SyncSession {
    static final String DISTRIBUTION_CACHE = "distribution.json";
    static final String DISTRIBUTION_ETAG = "distribution.etag";
    static final String MANIFEST_CACHE_DIR = "manifests";
    static final String OPTIONS_FILE = "options.txt";
    static final String DEFAULT_OPTIONS = "config/defaultoptions/options.txt";
    private static final long FREE_SPACE_MARGIN = 64L * 1024 * 1024;
    private static final long INDEX_SAVE_INTERVAL_MS = 5000;
    private static final long POLL_MS = 100;

    private final SyncOptions o;
    private final SyncListener listener;
    private final SyncText text;
    private final SyncResult result = new SyncResult();

    /** Set when workers must stop: the player cancelled or a worker failed. */
    private final AtomicBoolean stop = new AtomicBoolean();
    private boolean userCancelled;
    private final AtomicLong networkBytes = new AtomicLong();
    private final AtomicInteger downloads = new AtomicInteger();
    private final AtomicInteger copies = new AtomicInteger();

    private File instanceDir;
    private File librariesDir;
    private File versionsDir;
    private File workDir;
    private AndroidPolicy policy;
    private SyncIndex index;
    private int threads;

    /** What prepare() found: enough for the planning tests. */
    static final class Prepared {
        JsonObject distribution;
        JsonObject server;
        boolean fromCache;
        ServerMeta meta;
        Planner.Plan plan;
        String versionId;
        Planner.Item versionItem;
        File manifestCache;
    }

    private enum State { OK, OVERLAY, FETCH }

    /** A planned file and what verification found. */
    private static final class Check {
        final Planner.Item item;
        final File target;
        State state;
        /** MD5 of what is on disk now (OK, OVERLAY), then of what was placed. */
        String md5;

        Check(Planner.Item item, File target) {
            this.item = item;
            this.target = target;
        }
    }

    private enum Action { COPY, EMPTY, DOWNLOAD }

    /** Targets that share the same published bytes. */
    private static final class Group {
        final List<Check> targets = new ArrayList<Check>();
        Action action;
        File source;
    }

    SyncSession(SyncOptions options, SyncListener listener, SyncText text) {
        this.o = options;
        this.listener = listener;
        this.text = text;
    }

    // ------------------------------------------------------------------ run

    SyncResult run() throws SyncException {
        init();
        try {
            Prepared p = prepare();
            result.meta = p.meta;
            result.distributionFromCache = p.fromCache;
            result.versionId = p.versionId;
            for (String w : p.plan.warnings) {
                result.warnings.add(w);
            }
            // The parsed distribution (about 20 MB of JSON tree for 6 MB of text)
            // is not needed past planning: let it go before the long download.
            p.distribution = null;
            p.server = null;

            List<Planner.Item> items = new ArrayList<Planner.Item>(p.plan.items);
            items.add(p.versionItem);
            result.filesPlanned = items.size();
            result.modsPlanned = p.plan.modsPlanned;

            List<Check> checks = verify(items);

            Map<String, File> sources = new HashMap<String, File>();
            if (p.versionItem.md5 != null) {
                sources.put(p.versionItem.md5, p.manifestCache);
            }
            for (Check c : checks) {
                if (c.state != State.FETCH && c.item.md5 != null && c.item.md5.equals(c.md5)) {
                    sources.put(c.md5, c.target);
                }
            }
            List<Group> groups = group(checks, sources);

            ManagedManifest managed = ManagedManifest.load(instanceDir, result.warnings);
            claimBeforeDownload(checks, managed);

            fetch(groups);
            applyOverlays(checks);
            claimAfterDownload(checks, managed);
            seedOptions();
            prune(p, checks, managed);

            Set<String> keys = new HashSet<String>();
            for (Planner.Item item : items) {
                keys.add(item.indexKey());
            }
            index.retainOnly(keys);

            result.bytesDownloaded = networkBytes.get();
            result.filesDownloaded = downloads.get();
            result.filesCopied = copies.get();
            listener.onStage(text.get(SyncText.STAGE_DONE));
            return result;
        } finally {
            saveIndexQuietly();
        }
    }

    private void init() throws SyncException {
        if (o == null || o.gameHome == null || o.minecraftDir == null || o.workDir == null
                || o.distributionUrl == null || o.serverId == null) {
            throw new SyncException(text.get(SyncText.ERROR_OPTIONS), null);
        }
        try {
            policy = AndroidPolicy.parse(o.policyJson);
        } catch (RuntimeException e) {
            throw new SyncException(text.get(SyncText.ERROR_POLICY), e);
        }
        instanceDir = new File(o.gameHome, ElymonConfig.INSTANCE_REL.replace('/', File.separatorChar));
        librariesDir = new File(o.minecraftDir, "libraries");
        versionsDir = new File(o.minecraftDir, "versions");
        workDir = o.workDir;
        if (!workDir.isDirectory() && !workDir.mkdirs() && !workDir.isDirectory()) {
            throw new SyncException(text.get(SyncText.ERROR_WRITE, workDir.getName()), null);
        }
        threads = Math.max(1, Math.min(16, o.threads));
        index = SyncIndex.load(new File(workDir, SyncIndex.FILE_NAME));
    }

    // -------------------------------------------------------------- prepare

    /** Distribution, plan and version JSON; nothing is placed yet. */
    Prepared prepare() throws SyncException {
        if (policy == null) {
            init();
        }
        Prepared p = new Prepared();
        stage(text.get(SyncText.STAGE_DISTRIBUTION));
        fetchDistribution(p);
        stage(text.get(SyncText.STAGE_PREPARE));
        resolveVersionManifest(p);
        return p;
    }

    private void fetchDistribution(Prepared p) throws SyncException {
        File cache = new File(workDir, DISTRIBUTION_CACHE);
        File etagFile = new File(workDir, DISTRIBUTION_ETAG);
        String etag = cache.isFile() ? readSmallText(etagFile) : null;
        byte[] fresh = null;
        String freshEtag = null;
        JsonObject root = null;
        Exception networkError = null;

        int failures = 0;
        while (failures < Http.attempts) {
            checkCancelled();
            try {
                Http.Document d = Http.fetchDocument(o.distributionUrl, o.userAgent, etag);
                if (d.code == 304) {
                    if (etag == null) {
                        throw new IOException("304 without a conditional request");
                    }
                    try {
                        root = Distribution.parse(FileOps.readFile(cache));
                        break;
                    } catch (IOException | Distribution.InvalidException e) {
                        // The cache no longer matches its ETag: ask for the whole document.
                        etag = null;
                        etagFile.delete();
                        continue;
                    }
                }
                root = Distribution.parse(d.body);
                fresh = d.body;
                freshEtag = d.etag;
                break;
            } catch (IOException | Distribution.InvalidException e) {
                networkError = e;
            }
            failures++;
            if (failures < Http.attempts) {
                try {
                    Http.backoff(failures, null);
                } catch (FileOps.CancelledIO e) {
                    throw cancelled();
                }
            }
        }

        if (root == null) {
            try {
                root = Distribution.parse(FileOps.readFile(cache));
                p.fromCache = true;
            } catch (IOException | Distribution.InvalidException e) {
                throw new SyncException(text.get(SyncText.ERROR_UNREACHABLE), networkError != null ? networkError : e);
            }
        }

        JsonObject server = Distribution.findServer(root, o.serverId);
        if (server == null) {
            throw new SyncException(text.get(SyncText.ERROR_SERVER_MISSING), null);
        }
        String rejection = Distribution.profileRejection(server);
        if (rejection != null) {
            throw new SyncException(text.get(SyncText.ERROR_INVALID), new IOException(rejection));
        }
        try {
            p.plan = Planner.plan(server, policy);
        } catch (Planner.UnsafeException e) {
            // The desktop drops such a profile before anything is cached or downloaded.
            throw new SyncException(text.get(SyncText.ERROR_UNSAFE_PATH, e.what), e);
        } catch (Planner.InvalidModuleException e) {
            throw new SyncException(text.get(SyncText.ERROR_INVALID), e);
        }

        if (fresh != null) {
            try {
                FileOps.writeAtomic(cache, fresh);
                if (freshEtag != null) {
                    FileOps.writeAtomic(etagFile, freshEtag.getBytes(StandardCharsets.UTF_8));
                } else {
                    etagFile.delete();
                }
            } catch (IOException e) {
                result.warnings.add("distribution cache not written: " + e.getMessage());
            }
        }
        p.distribution = root;
        p.server = server;
        p.meta = Distribution.meta(root, server, !p.fromCache, System.currentTimeMillis());
    }

    private void resolveVersionManifest(Prepared p) throws SyncException {
        Planner.Manifest m = p.plan.manifest;
        if (m == null) {
            throw new SyncException(text.get(SyncText.ERROR_NO_VERSION), null);
        }
        File dir = new File(workDir, MANIFEST_CACHE_DIR);
        File cached = new File(dir, (m.md5 != null ? m.md5 : "untracked") + ".json");
        boolean valid;
        try {
            valid = cached.isFile() && (m.md5 == null || m.md5.equals(FileOps.md5(cached, stop)));
        } catch (FileOps.CancelledIO e) {
            throw cancelled();
        }
        if (!valid) {
            File part = new File(cached.getPath() + ".part");
            downloadWithRetries(m.url, part, m.md5, m.size, "versions/" + m.moduleId + ".json");
            move(part, cached, "versions/" + m.moduleId + ".json");
            downloads.incrementAndGet();
        }
        String id;
        try {
            JsonElement json = JsonParser.parseString(new String(FileOps.readFile(cached), StandardCharsets.UTF_8));
            id = Distribution.string(json.getAsJsonObject(), "id");
        } catch (IOException | RuntimeException e) {
            cached.delete();
            throw new SyncException(text.get(SyncText.ERROR_INVALID), e);
        }
        if (id == null || !PathGuard.isPlainName(id)) {
            throw new SyncException(text.get(SyncText.ERROR_UNSAFE_PATH, String.valueOf(m.moduleId)), null);
        }
        p.versionId = id;
        p.manifestCache = cached;
        p.versionItem = new Planner.Item(Planner.Root.VERSIONS, id + "/" + id + ".json", m.url, m.md5, m.size,
                "VersionManifest", m.moduleId);
    }

    // --------------------------------------------------------------- verify

    private File target(Planner.Item item) {
        switch (item.root) {
            case VERSIONS:
                return PathGuard.resolve(versionsDir, item.rel);
            case LIBRARIES:
                return PathGuard.resolve(librariesDir, item.rel);
            default:
                return PathGuard.resolve(instanceDir, item.rel);
        }
    }

    private static String display(Planner.Item item) {
        switch (item.root) {
            case VERSIONS:
                return "versions/" + item.rel;
            case LIBRARIES:
                return "libraries/" + item.rel;
            default:
                return item.rel;
        }
    }

    private List<Check> verify(List<Planner.Item> items) throws SyncException {
        final String label = text.get(SyncText.STAGE_VERIFY);
        stage(label);
        final List<Check> checks = new ArrayList<Check>(items.size());
        long total = 0;
        for (Planner.Item item : items) {
            checks.add(new Check(item, target(item)));
            total += Math.max(0, item.size);
        }
        final AtomicLong done = new AtomicLong();
        final AtomicReference<String> current = new AtomicReference<String>("");
        final long totalBytes = total;
        parallel(checks.size(), new Work() {
            @Override
            public void run(int i) throws SyncException {
                Check c = checks.get(i);
                current.set(c.item.rel);
                verifyOne(c);
                done.addAndGet(Math.max(0, c.item.size));
            }
        }, new Runnable() {
            @Override
            public void run() {
                listener.onProgress(Math.min(done.get(), totalBytes), totalBytes, current.get());
            }
        });
        return checks;
    }

    private void verifyOne(Check c) throws SyncException {
        Planner.Item item = c.item;
        File t = c.target;
        if (!t.isFile()) {
            c.state = State.FETCH;
            return;
        }
        if (item.protectedPath || item.md5 == null) {
            // The player's file, or an untracked one: only written when missing.
            c.state = State.OK;
            return;
        }
        String key = item.indexKey();
        long size = t.length();
        long mtime = t.lastModified();
        String md5 = index.trusted(key, size, mtime);
        if (md5 == null) {
            try {
                md5 = FileOps.md5(t, stop);
            } catch (FileOps.CancelledIO e) {
                throw cancelled();
            }
            if (md5 == null) {
                c.state = State.FETCH;
                return;
            }
            index.observe(key, size, mtime, md5);
        }
        c.md5 = md5;
        if (item.overlay == null) {
            c.state = md5.equals(item.md5) ? State.OK : State.FETCH;
            return;
        }
        SyncIndex.Entry e = index.get(key);
        if (e != null && item.md5.equals(e.distMd5) && item.overlay.id.equals(e.overlayId) && md5.equals(e.md5)) {
            c.state = State.OK;
        } else if (md5.equals(item.md5)) {
            c.state = State.OVERLAY;
        } else {
            c.state = State.FETCH;
        }
    }

    // ---------------------------------------------------------------- fetch

    private List<Group> group(List<Check> checks, Map<String, File> sources) {
        Map<String, Group> byMd5 = new LinkedHashMap<String, Group>();
        List<Group> groups = new ArrayList<Group>();
        for (Check c : checks) {
            if (c.state != State.FETCH) {
                continue;
            }
            Group g = c.item.md5 == null ? null : byMd5.get(c.item.md5);
            if (g == null) {
                g = new Group();
                groups.add(g);
                if (c.item.md5 != null) {
                    byMd5.put(c.item.md5, g);
                }
            }
            g.targets.add(c);
        }
        for (Group g : groups) {
            Planner.Item first = g.targets.get(0).item;
            File source = first.md5 == null ? null : sources.get(first.md5);
            if (source != null) {
                g.action = Action.COPY;
                g.source = source;
            } else if (FileOps.EMPTY_MD5.equals(first.md5) && first.size <= 0) {
                g.action = Action.EMPTY;
            } else {
                g.action = Action.DOWNLOAD;
                // Resume the largest partial download of this content, if any.
                int best = 0;
                long bestLength = -1;
                for (int i = 0; i < g.targets.size(); i++) {
                    long length = part(g.targets.get(i).target).length();
                    if (length > bestLength) {
                        best = i;
                        bestLength = length;
                    }
                }
                if (best != 0) {
                    Check moved = g.targets.remove(best);
                    g.targets.add(0, moved);
                }
            }
        }
        return groups;
    }

    private static File part(File target) {
        return new File(target.getPath() + ".part");
    }

    private void fetch(final List<Group> groups) throws SyncException {
        if (groups.isEmpty()) {
            return;
        }
        long toDownload = 0;
        long toWrite = 0;
        int files = 0;
        for (Group g : groups) {
            files += g.targets.size();
            for (int i = 0; i < g.targets.size(); i++) {
                Check c = g.targets.get(i);
                long size = Math.max(0, c.item.size);
                if (g.action == Action.DOWNLOAD && i == 0) {
                    long partial = Math.min(size, part(c.target).length());
                    toDownload += size - partial;
                    toWrite += size - partial;
                } else {
                    toWrite += size;
                }
            }
        }

        long free = usableSpace(o.gameHome);
        if (free > 0 && free < toWrite + FREE_SPACE_MARGIN) {
            throw new SyncException(text.get(SyncText.ERROR_SPACE, text.size(toWrite + FREE_SPACE_MARGIN),
                    text.size(free)), null);
        }
        checkCancelled();
        if (toDownload > o.confirmThresholdBytes && !listener.confirmDownload(toDownload)) {
            throw new SyncException(text.get(SyncText.ERROR_DECLINED), null, true);
        }

        final int totalFiles = files;
        final long totalBytes = toDownload;
        final AtomicInteger placed = new AtomicInteger();
        final AtomicReference<String> current = new AtomicReference<String>("");
        final long startBytes = networkBytes.get();
        final int[] shown = {-1};
        final long[] lastSave = {System.currentTimeMillis()};
        stage(text.get(SyncText.STAGE_DOWNLOAD, 0, totalFiles));
        shown[0] = 0;
        parallel(groups.size(), new Work() {
            @Override
            public void run(int i) throws SyncException {
                fetchGroup(groups.get(i), placed, current);
            }
        }, new Runnable() {
            @Override
            public void run() {
                int now = placed.get();
                if (now != shown[0]) {
                    shown[0] = now;
                    listener.onStage(text.get(SyncText.STAGE_DOWNLOAD, now, totalFiles));
                }
                long bytes = networkBytes.get() - startBytes;
                listener.onProgress(Math.min(bytes, totalBytes), totalBytes, current.get());
                if (System.currentTimeMillis() - lastSave[0] > INDEX_SAVE_INTERVAL_MS) {
                    lastSave[0] = System.currentTimeMillis();
                    saveIndexQuietly();
                }
            }
        });
    }

    private void fetchGroup(Group g, AtomicInteger placed, AtomicReference<String> current) throws SyncException {
        checkStop();
        Check first = g.targets.get(0);
        current.set(first.item.rel);
        int start = 0;
        File source = g.source;
        if (g.action == Action.DOWNLOAD) {
            File part = part(first.target);
            downloadWithRetries(first.item.url, part, first.item.md5, first.item.size, display(first.item));
            move(part, first.target, display(first.item));
            first.md5 = first.item.md5;
            index.record(first.item.indexKey(), first.target, md5OrHash(first), null, null);
            downloads.incrementAndGet();
            placed.incrementAndGet();
            source = first.target;
            start = 1;
        }
        for (int i = start; i < g.targets.size(); i++) {
            checkStop();
            Check c = g.targets.get(i);
            current.set(c.item.rel);
            if (g.action == Action.EMPTY) {
                writeEmpty(c);
            } else {
                copyInto(source, c);
            }
            copies.incrementAndGet();
            placed.incrementAndGet();
        }
    }

    /** MD5 of a freshly downloaded file: the published one, or a hash for an untracked file. */
    private String md5OrHash(Check c) throws SyncException {
        if (c.item.md5 != null) {
            return c.item.md5;
        }
        try {
            String md5 = FileOps.md5(c.target, stop);
            c.md5 = md5;
            return md5 == null ? FileOps.EMPTY_MD5 : md5;
        } catch (FileOps.CancelledIO e) {
            throw cancelled();
        }
    }

    private void writeEmpty(Check c) throws SyncException {
        File part = part(c.target);
        try {
            FileOps.ensureParent(part);
            new java.io.FileOutputStream(part).close();
            FileOps.move(part, c.target);
        } catch (IOException e) {
            throw new SyncException(text.get(SyncText.ERROR_WRITE, display(c.item)), e);
        }
        c.md5 = FileOps.EMPTY_MD5;
        index.record(c.item.indexKey(), c.target, FileOps.EMPTY_MD5, null, null);
    }

    private void copyInto(File source, Check c) throws SyncException {
        File part = part(c.target);
        String md5;
        try {
            md5 = FileOps.copy(source, part, stop);
        } catch (FileOps.CancelledIO e) {
            part.delete();
            throw cancelled();
        } catch (IOException e) {
            part.delete();
            throw new SyncException(text.get(SyncText.ERROR_WRITE, display(c.item)), e);
        }
        if (c.item.md5 != null && !c.item.md5.equals(md5)) {
            // The local copy changed under us: fetch this one from the network.
            part.delete();
            downloadWithRetries(c.item.url, part, c.item.md5, c.item.size, display(c.item));
            md5 = c.item.md5;
            downloads.incrementAndGet();
        }
        move(part, c.target, display(c.item));
        c.md5 = md5;
        index.record(c.item.indexKey(), c.target, md5, null, null);
    }

    private long downloadWithRetries(String url, File part, String md5, long size, String display)
            throws SyncException {
        IOException last = null;
        int attempt = 0;
        while (attempt < Http.attempts) {
            attempt++;
            checkStop();
            try {
                return Http.downloadToPart(url, o.userAgent, part, md5, size, stop, networkBytes);
            } catch (FileOps.CancelledIO e) {
                throw cancelled();
            } catch (Http.StatusException e) {
                last = e;
                if (!e.retryable()) {
                    break;
                }
            } catch (IOException e) {
                last = e;
            }
            if (attempt < Http.attempts) {
                try {
                    Http.backoff(attempt, stop);
                } catch (FileOps.CancelledIO e) {
                    throw cancelled();
                }
            }
        }
        throw new SyncException(text.get(SyncText.ERROR_DOWNLOAD, display), last);
    }

    private void move(File from, File to, String display) throws SyncException {
        try {
            FileOps.move(from, to);
        } catch (IOException e) {
            throw new SyncException(text.get(SyncText.ERROR_WRITE, display), e);
        }
    }

    // ------------------------------------------------------------- overlays

    private void applyOverlays(List<Check> checks) throws SyncException {
        List<Check> todo = new ArrayList<Check>();
        for (Check c : checks) {
            if (c.item.overlay != null && (c.state == State.OVERLAY || c.state == State.FETCH)) {
                todo.add(c);
            }
        }
        if (todo.isEmpty()) {
            return;
        }
        checkCancelled();
        stage(text.get(SyncText.STAGE_OVERLAYS));
        for (Check c : todo) {
            try {
                byte[] original = FileOps.readFile(c.target);
                if (!c.item.md5.equals(FileOps.md5(original))) {
                    throw new IOException("unexpected content before overlay");
                }
                byte[] edited = c.item.overlay.apply(original);
                FileOps.writeAtomic(c.target, edited);
                c.md5 = FileOps.md5(edited);
                index.record(c.item.indexKey(), c.target, c.md5, c.item.md5, c.item.overlay.id);
                result.overlaysApplied++;
            } catch (IOException e) {
                throw new SyncException(text.get(SyncText.ERROR_WRITE, display(c.item)), e);
            }
        }
    }

    // ------------------------------------------------------------ ownership

    private void claimBeforeDownload(List<Check> checks, ManagedManifest managed) throws SyncException {
        for (Check c : checks) {
            if (c.item.root != Planner.Root.INSTANCE || c.item.protectedPath || c.item.md5 == null) {
                continue;
            }
            managed.claim(c.item.rel, c.state == State.OK ? c.md5 : c.item.md5);
        }
        saveManaged(managed);
    }

    private void claimAfterDownload(List<Check> checks, ManagedManifest managed) throws SyncException {
        for (Check c : checks) {
            if (c.item.root != Planner.Root.INSTANCE || c.item.protectedPath) {
                continue;
            }
            if (c.item.md5 == null && c.state != State.FETCH) {
                // An untracked file already there: nothing proves it is ours.
                continue;
            }
            if (c.md5 != null) {
                managed.claim(c.item.rel, c.md5);
            }
        }
        saveManaged(managed);
    }

    private void saveManaged(ManagedManifest managed) throws SyncException {
        try {
            managed.save(instanceDir);
        } catch (IOException e) {
            throw new SyncException(text.get(SyncText.ERROR_WRITE, ManagedManifest.FILE_NAME), e);
        }
    }

    // -------------------------------------------------------------- options

    /**
     * Amethyst creates an empty options.txt before the DefaultOptions mod can
     * write the pack's, so the pack defaults never apply: create it here from
     * config/defaultoptions/options.txt plus the Android values, but only when
     * it is missing or empty. A player's options are never rewritten.
     */
    private void seedOptions() throws SyncException {
        File options = new File(instanceDir, OPTIONS_FILE);
        if (options.exists() && !isBlankFile(options)) {
            return;
        }
        if (options.isDirectory()) {
            return;
        }
        File defaults = PathGuard.resolve(instanceDir, DEFAULT_OPTIONS);
        String base = "";
        try {
            if (defaults.isFile()) {
                base = new String(FileOps.readFile(defaults), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            base = "";
        }
        String seeded = Overlay.colonKeySet(base, policy.seedOptions);
        if (seeded.trim().isEmpty()) {
            return;
        }
        stage(text.get(SyncText.STAGE_OPTIONS));
        try {
            FileOps.writeAtomic(options, seeded.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SyncException(text.get(SyncText.ERROR_WRITE, OPTIONS_FILE), e);
        }
        result.optionsSeeded = true;
    }

    private static boolean isBlankFile(File file) {
        if (!file.isFile()) {
            return false;
        }
        if (file.length() == 0) {
            return true;
        }
        if (file.length() > 4096) {
            return false;
        }
        try {
            return new String(FileOps.readFile(file), StandardCharsets.UTF_8).trim().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- prune

    /**
     * Deletes what this engine deposited earlier and the distribution no longer
     * places, when its bytes are still the deposited ones. Never while the game
     * runs, never a protected path, never outside the instance (versions/ and
     * libraries/ are left alone). From a cached distribution only mods/ is
     * pruned, as the desktop's mods deployment does: an update that stopped
     * halfway can leave the old and the new jar of one mod side by side, and FML
     * refuses to start on a duplicate mod id, so that cleanup cannot wait for
     * the network to come back.
     */
    private void prune(Prepared p, List<Check> checks, ManagedManifest managed) throws SyncException {
        boolean modsOnly = p.fromCache;
        if (modsOnly) {
            result.warnings.add("distribution read from the cache: only mods/ is pruned");
        }
        if (!o.allowPrune) {
            result.warnings.add("nothing pruned: not allowed by the caller");
            return;
        }
        Set<String> planned = new HashSet<String>();
        for (Check c : checks) {
            if (c.item.root == Planner.Root.INSTANCE) {
                planned.add(PathGuard.fold(c.item.rel));
            }
        }
        List<String> candidates = new ArrayList<String>();
        for (String path : managed.paths()) {
            if (modsOnly && !PathGuard.fold(path).startsWith("mods/")) {
                continue;
            }
            if (!planned.contains(PathGuard.fold(path))) {
                candidates.add(path);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        checkCancelled();
        stage(text.get(SyncText.STAGE_PRUNE));
        for (String path : candidates) {
            ManagedManifest.Entry entry = managed.get(path);
            if (PathGuard.isNeverTouched(path)) {
                managed.release(path);
                continue;
            }
            File f = PathGuard.resolve(instanceDir, path);
            boolean link = FileOps.isSymlink(f);
            if (!link && !f.exists()) {
                managed.release(path);
                continue;
            }
            if (link || !f.isFile() || !PathGuard.staysInside(instanceDir, f)) {
                managed.release(path);
                result.warnings.add("released " + path + ": not a regular file inside the instance, left in place");
                continue;
            }
            String md5;
            try {
                md5 = FileOps.md5(f, stop);
            } catch (FileOps.CancelledIO e) {
                throw cancelled();
            }
            if (md5 == null || entry == null || !entry.hashes.contains(md5)) {
                managed.release(path);
                result.warnings.add("released " + path + ": modified since it was placed, left in place");
                continue;
            }
            if (f.delete()) {
                managed.release(path);
                result.filesPruned++;
                removeEmptyParents(path);
            } else {
                result.warnings.add("could not delete " + path + "; retried next time");
            }
        }
        saveManaged(managed);
    }

    /** Removes the directories a deletion left empty, never a top-level one. */
    private void removeEmptyParents(String rel) {
        String[] segments = rel.split("/");
        for (int i = segments.length - 1; i >= 2; i--) {
            StringBuilder b = new StringBuilder();
            for (int j = 0; j < i; j++) {
                if (j > 0) {
                    b.append('/');
                }
                b.append(segments[j]);
            }
            File dir = PathGuard.resolve(instanceDir, b.toString());
            String[] children = dir.list();
            if (FileOps.isSymlink(dir) || !dir.isDirectory() || children == null || children.length > 0 || !dir.delete()) {
                return;
            }
        }
    }

    // ------------------------------------------------------------- plumbing

    interface Work {
        void run(int i) throws SyncException;
    }

    /**
     * Runs work(0..count-1) on the worker threads while this thread reports
     * progress and polls for cancellation. The first failure stops the others.
     */
    private void parallel(final int count, final Work work, Runnable report) throws SyncException {
        if (count == 0) {
            return;
        }
        final AtomicInteger cursor = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        int n = Math.min(threads, count);
        Thread[] workers = new Thread[n];
        for (int t = 0; t < n; t++) {
            workers[t] = new Thread(new Runnable() {
                @Override
                public void run() {
                    int i;
                    while (!stop.get() && (i = cursor.getAndIncrement()) < count) {
                        try {
                            work.run(i);
                        } catch (SyncException e) {
                            if (!e.cancelled) {
                                failure.compareAndSet(null, e);
                            }
                            stop.set(true);
                        } catch (Throwable e) {
                            failure.compareAndSet(null, e);
                            stop.set(true);
                        }
                    }
                }
            }, "ElymonSync-" + t);
            workers[t].setDaemon(true);
            workers[t].start();
        }
        boolean interrupted = false;
        boolean completed = false;
        try {
            for (Thread worker : workers) {
                while (worker.isAlive()) {
                    if (interrupted) {
                        // The interrupt stays pending until the workers are gone: no busy join loop.
                        joinQuietly(worker);
                        continue;
                    }
                    try {
                        worker.join(POLL_MS);
                    } catch (InterruptedException e) {
                        interrupted = true;
                        userCancelled = true;
                        stop.set(true);
                        continue;
                    }
                    report.run();
                    if (!stop.get() && listener.isCancelled()) {
                        userCancelled = true;
                        stop.set(true);
                    }
                }
            }
            report.run();
            completed = true;
        } finally {
            if (!completed) {
                // A listener threw: never leave workers writing after run() returns.
                stop.set(true);
                for (Thread worker : workers) {
                    joinQuietly(worker);
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        Throwable f = failure.get();
        if (f instanceof SyncException) {
            throw (SyncException) f;
        }
        if (f != null) {
            throw new SyncException(text.get(SyncText.ERROR_UNEXPECTED), f);
        }
        if (userCancelled || stop.get()) {
            throw cancelled();
        }
    }

    /** Waits for a worker that was told to stop, ignoring interrupts (it stops within one read). */
    private static void joinQuietly(Thread worker) {
        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void stage(String label) {
        listener.onStage(label);
    }

    /** Main thread only: asks the listener. */
    private void checkCancelled() throws SyncException {
        if (userCancelled || listener.isCancelled()) {
            userCancelled = true;
            stop.set(true);
            throw cancelled();
        }
    }

    /** Worker threads: only looks at the flag. */
    private void checkStop() throws SyncException {
        if (stop.get()) {
            throw cancelled();
        }
    }

    private SyncException cancelled() {
        return new SyncException(text.get(SyncText.ERROR_CANCELLED), null, true);
    }

    private void saveIndexQuietly() {
        if (index == null || !index.isDirty()) {
            return;
        }
        try {
            index.save(new File(workDir, SyncIndex.FILE_NAME));
        } catch (IOException e) {
            result.warnings.add("sync index not saved: " + e.getMessage());
        }
    }

    private static String readSmallText(File file) {
        if (!file.isFile() || file.length() > 1024) {
            return null;
        }
        try {
            String s = new String(FileOps.readFile(file), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        } catch (IOException e) {
            return null;
        }
    }

    private static long usableSpace(File file) {
        File dir = file;
        while (dir != null && !dir.exists()) {
            dir = dir.getParentFile();
        }
        return dir == null ? 0 : dir.getUsableSpace();
    }
}
