package com.elythera.elymon.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

/**
 * Reading the distribution document: the shape check done before the cache is
 * written or trusted (desktop distroshape.js), and the Elythera blocks of the
 * Elymon entry (availability.js, launcherrequirement.js, maintenance.js).
 */
final class Distribution {
    private Distribution() {}

    private static final Pattern PROFILE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final int MAX_MESSAGE_CHARS = 200;
    private static final int MAX_URL_CHARS = 2048;

    /** The document is not a distribution (captive portal page, truncated upload...). */
    static final class InvalidException extends Exception {
        InvalidException(String reason) {
            super(reason);
        }
    }

    /** Parses and checks the envelope: an object with a non-empty "servers" array. */
    static JsonObject parse(byte[] body) throws InvalidException {
        JsonElement root;
        try {
            root = JsonParser.parseReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new InvalidException("not JSON");
        }
        if (root == null || !root.isJsonObject()) {
            throw new InvalidException("document is not a JSON object");
        }
        JsonObject o = root.getAsJsonObject();
        if (!o.has("servers") || !o.get("servers").isJsonArray()) {
            throw new InvalidException("servers is not an array");
        }
        if (o.getAsJsonArray("servers").size() == 0) {
            throw new InvalidException("servers is empty");
        }
        return o;
    }

    /** The server entry with that id, or null. The first one wins, like HeliosDistribution. */
    static JsonObject findServer(JsonObject root, String id) {
        for (JsonElement e : root.getAsJsonArray("servers")) {
            if (e.isJsonObject()) {
                JsonObject s = e.getAsJsonObject();
                if (id.equals(string(s, "id"))) {
                    return s;
                }
            }
        }
        return null;
    }

    /** Why a profile is unusable (distroshape.js profileRejection, minus the module paths), or null. */
    static String profileRejection(JsonObject server) {
        String id = string(server, "id");
        if (id == null || !PROFILE_ID.matcher(id).matches()) {
            return "invalid profile id";
        }
        if (string(server, "address") == null) {
            return "address is not a string";
        }
        if (string(server, "minecraftVersion") == null) {
            return "minecraftVersion is not a string";
        }
        if (!server.has("modules") || !server.get("modules").isJsonArray()) {
            return "modules is not an array";
        }
        return null;
    }

    /** The player-facing fields of the entry, evaluated at time now. */
    static ServerMeta meta(JsonObject root, JsonObject server, boolean fresh, long now) {
        ServerMeta m = new ServerMeta();
        m.name = string(server, "name");
        m.address = string(server, "address");
        m.packVersion = string(server, "version");
        m.minecraftVersion = string(server, "minecraftVersion");
        m.iconUrl = safeHttpsUrl(string(server, "icon"));
        JsonObject background = object(server, "background");
        if (background != null) {
            m.backgroundImage = safeHttpsUrl(string(background, "image"));
        }

        JsonObject av = object(server, "availability");
        if (av != null) {
            m.availabilityMessage = cleanText(string(av, "message"));
            long start = date(string(av, "start"));
            long end = date(string(av, "end"));
            m.availabilityStart = start;
            m.availabilityEnd = end;
            boolean disabled = av.has("enabled") && av.get("enabled").isJsonPrimitive()
                    && av.get("enabled").getAsJsonPrimitive().isBoolean() && !av.get("enabled").getAsBoolean();
            if (end != 0 && now > end) {
                m.availabilityState = "ended";
            } else if (disabled) {
                m.availabilityState = "maintenance";
            } else if (start != 0 && now < start) {
                m.availabilityState = "upcoming";
            } else {
                m.availabilityState = "open";
            }
            m.available = "open".equals(m.availabilityState);
        }

        JsonObject rootRequires = object(root, "requires");
        JsonObject serverRequires = object(server, "requires");
        JsonObject launcherWinner = floorWinner(rootRequires, serverRequires, "launcher");
        JsonObject androidWinner = floorWinner(rootRequires, serverRequires, "android");
        if (launcherWinner != null) {
            m.requiresLauncher = string(launcherWinner, "launcher").trim();
        }
        if (androidWinner != null) {
            m.requiresAndroid = string(androidWinner, "android").trim();
        }
        JsonObject textSource = launcherWinner != null ? launcherWinner : androidWinner;
        if (textSource != null) {
            m.requiresMessage = cleanText(string(textSource, "message"));
            m.requiresUrl = safeHttpsUrl(string(textSource, "url"));
        }

        JsonObject maintenance = object(root, "maintenance");
        if (maintenance != null) {
            m.maintenanceMessage = cleanText(string(maintenance, "message"));
            m.maintenanceUrl = safeHttpsUrl(string(maintenance, "url"));
            long start = date(string(maintenance, "start"));
            long end = date(string(maintenance, "end"));
            boolean enabled = maintenance.has("enabled") && maintenance.get("enabled").isJsonPrimitive()
                    && maintenance.get("enabled").getAsJsonPrimitive().isBoolean()
                    && maintenance.get("enabled").getAsBoolean();
            boolean over = end != 0 && now > end;
            boolean announced = start != 0 && now < start;
            m.maintenanceActive = fresh && enabled && !over && !announced;
        }
        return m;
    }

    /** The block whose floor is higher; the profile (second) wins a tie; unreadable floors are ignored. */
    private static JsonObject floorWinner(JsonObject rootBlock, JsonObject serverBlock, String field) {
        JsonObject winner = null;
        String winnerValue = null;
        JsonObject[] candidates = {rootBlock, serverBlock};
        for (JsonObject block : candidates) {
            if (block == null) {
                continue;
            }
            String value = string(block, field);
            if (value == null || !VersionOrder.isComparable(value)) {
                continue;
            }
            if (winner == null || VersionOrder.compare(value, winnerValue) >= 0) {
                winner = block;
                winnerValue = value;
            }
        }
        return winner;
    }

    static String string(JsonObject o, String name) {
        if (o == null || !o.has(name)) {
            return null;
        }
        JsonElement e = o.get(name);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            return null;
        }
        return e.getAsString();
    }

    static JsonObject object(JsonObject o, String name) {
        if (o == null || !o.has(name)) {
            return null;
        }
        JsonElement e = o.get(name);
        return e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** ISO-8601 date to epoch milliseconds, 0 when absent or unreadable (like JS Date.parse). */
    static long date(String value) {
        if (value == null) {
            return 0;
        }
        String v = value.trim();
        try {
            return OffsetDateTime.parse(v).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            // Next format.
        }
        try {
            return Instant.parse(v).toEpochMilli();
        } catch (RuntimeException ignored) {
            // Next format.
        }
        try {
            return LocalDateTime.parse(v).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            // Next format.
        }
        try {
            return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /** Desktop safeurl.js: https only, no whitespace or credentials. */
    static String safeHttpsUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty() || v.length() > MAX_URL_CHARS) {
            return null;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isWhitespace(c) || c < 0x20 || (c >= 0x7f && c <= 0x9f)) {
                return null;
            }
        }
        if (!v.regionMatches(true, 0, "https://", 0, 8)) {
            return null;
        }
        String rest = v.substring(8);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        if (authority.isEmpty() || authority.indexOf('@') >= 0) {
            return null;
        }
        return v;
    }

    /** Desktop remotetext.js: one-line-per-paragraph text without control characters, capped. */
    static String cleanText(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean control = (c <= 0x1f && c != '\n') || (c >= 0x7f && c <= 0x9f)
                    || c == '‎' || c == '‏' || (c >= '‪' && c <= '‮')
                    || (c >= '⁦' && c <= '⁩');
            b.append(control ? ' ' : c);
        }
        String clean = b.toString().replaceAll("[ \\t]+", " ").replaceAll(" *\n *", "\n").trim();
        if (clean.isEmpty()) {
            return null;
        }
        return clean.length() > MAX_MESSAGE_CHARS ? clean.substring(0, MAX_MESSAGE_CHARS - 1) + "…" : clean;
    }
}
