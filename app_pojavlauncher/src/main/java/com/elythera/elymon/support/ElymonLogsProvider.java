package com.elythera.elymon.support;

import androidx.core.content.FileProvider;

/**
 * Hands the support zip to the app the player picks in the share sheet.
 *
 * Its own class, so the manifest entry cannot merge with a FileProvider some library
 * declares. Authority "${applicationId}.elymon.logs"; not exported; the paths
 * (res/xml/elymon_logs_paths.xml) are limited to cache/elymon-logs/, so nothing else of
 * the app can be reached through it, and each share grants read access to one zip only.
 */
public final class ElymonLogsProvider extends FileProvider {
}
