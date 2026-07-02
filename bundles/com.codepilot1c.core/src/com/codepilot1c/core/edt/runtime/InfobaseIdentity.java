/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.runtime;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Canonical infobase connection identity — the ONE way this plugin decides "same infobase".
 *
 * <p>EDT normalizes stored connection strings (slash direction, trailing separator, drive-letter
 * case), so raw {@code equals()} on connection strings misses rows EDT itself considers identical
 * (live-observed: a connect retry hit NAME_COLLISION on the very row it had just added, stack
 * polygon 2026-07-02). Extracted from {@code EdtInfobaseConnectService} so the association tool
 * and the lease guard share the exact same matching rules.</p>
 */
public final class InfobaseIdentity {

    private static final Pattern FILE_TOKEN = Pattern.compile(
            "File\\s*=\\s*\"([^\"]*)\"|File\\s*=\\s*'([^']*)'", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private InfobaseIdentity() {
        // static utility
    }

    /** Raw (trimmed) connection string of a reference, or {@code null} when unavailable. */
    public static String identityOf(InfobaseReference reference) {
        try {
            if (reference == null || reference.getConnectionString() == null) {
                return null;
            }
            String value = reference.getConnectionString().asConnectionString();
            return value == null || value.isBlank() ? null : value.trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * True when two infobase connection strings denote the SAME physical identity, tolerant of
     * cosmetic differences EDT does not treat as meaningful: a trailing path separator, slash
     * direction and drive-letter/path case on the {@code File="..."} token. Without this, a re-bind
     * whose resolved path lacked the trailing backslash present on the registered entry was wrongly
     * flagged NAME_COLLISION even under force=true (live finding 2026-06-11).
     */
    public static boolean matches(String a, String b) {
        String ca = canonical(a);
        String cb = canonical(b);
        return ca != null && ca.equals(cb);
    }

    /** Canonical form of a connection string: file IBs by normalized path, others case/space-folded. */
    public static String canonical(String connectionString) {
        if (connectionString == null) {
            return null;
        }
        Matcher m = FILE_TOKEN.matcher(connectionString);
        if (m.find()) {
            String path = m.group(1) != null ? m.group(1) : m.group(2);
            if (path != null) {
                String norm = path.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
                while (norm.endsWith("/")) { //$NON-NLS-1$
                    norm = norm.substring(0, norm.length() - 1);
                }
                return "file:" + norm; //$NON-NLS-1$
            }
        }
        // server/standalone: fold case and strip all whitespace so Srvr/Ref order/spacing is ignored
        return connectionString.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
