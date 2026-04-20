/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds {@code Процедура ... КонецПроцедуры} / {@code Procedure ... EndProcedure}
 * blocks in a BSL fragment and returns the procedure names.
 *
 * <p>Kept dependency-free so it can be unit-tested with plain JUnit.</p>
 */
public final class BslProcedureMatcher {

    /** Matches a Procedure/EndProcedure block in both RU and EN BSL syntax. Group 1 is the name. */
    public static final Pattern PROCEDURE_PATTERN = Pattern.compile(
            "(?s)(?:Процедура|Procedure)\\s+([\\p{L}0-9_]+).*?(?:КонецПроцедуры|EndProcedure)"); //$NON-NLS-1$

    private BslProcedureMatcher() {
    }

    /**
     * Extracts procedure names from the given BSL fragment.
     *
     * @param source BSL source (may be null or empty)
     * @return list of procedure names in source order (empty if none)
     */
    public static List<String> procedureNames(String source) {
        List<String> names = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return names;
        }
        Matcher m = PROCEDURE_PATTERN.matcher(source);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }
}
