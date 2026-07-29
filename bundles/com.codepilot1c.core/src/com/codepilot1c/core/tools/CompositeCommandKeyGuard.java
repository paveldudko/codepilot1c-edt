/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Closes the hole {@link SchemaKeyGuard} leaves inside a composite tool: a key that the schema DOES
 * declare but that belongs to a DIFFERENT {@code command} of the same tool.
 *
 * <p>A composite tool ({@code dcs_manage}, {@code external_manage}, {@code extension_manage})
 * advertises the UNION of every command's parameters, so {@code SchemaKeyGuard} rightly calls
 * {@code dataset_name} a known key — while {@code command:"upsert_param"} never reads it. The
 * practical outcome is the very defect {@code SchemaKeyGuard} was built to end: the key is dropped by
 * nobody in particular, the operation reports success, and the caller's intent is lost.</p>
 *
 * <p><strong>The contract is extracted, not duplicated.</strong> The composite schemas already record
 * ownership machine-readably: every per-command property's description opens with the owning command
 * in parentheses — {@code "(list_nodes) Case-insensitive name filter"},
 * {@code "(upsert_param/upsert_field) Expression"}, {@code "(create) Platform version"} — and the
 * command universe is the {@code command} property's own {@code enum}. Reading that beats a
 * hand-written table, which would be a second copy free to drift from the descriptions the agent
 * actually reads.</p>
 *
 * <p>Tag resolution, all of it deliberately permissive:</p>
 * <ul>
 *   <li>No leading tag → the key is COMMON and accepted by every command ({@code command},
 *       {@code project}, {@code owner_fqn}, {@code base_project} …). Untagged therefore always means
 *       "never refused".</li>
 *   <li>A tag token matches a command by equality or by prefix, so {@code (create)} covers
 *       {@code create_report} and {@code create_processing} in {@code external_manage} and the single
 *       {@code create} in {@code extension_manage}, and {@code (list)} covers both list commands.</li>
 *   <li>Several tokens split on {@code /} or {@code ,}: {@code (adopt/set_state)} is owned by both.</li>
 *   <li>A token that is prose rather than a command name — it contains a space, as in
 *       {@code (mutating commands)} — or that matches no declared command at all makes the key
 *       COMMON. Tag prose and tag drift can therefore never cause a false refusal.</li>
 * </ul>
 *
 * <p><strong>Fail-open</strong> exactly like {@link SchemaKeyGuard}: an unparsable schema, one with
 * {@code additionalProperties: true}, one without a {@code command} enum, one where no property
 * carries a tag, an absent {@code command} or a {@code command} outside the enum all yield an empty
 * report marked {@code enforceable() == false}. Only a payload whose command is known AND whose key is
 * declared for other commands only is ever reported.</p>
 *
 * <p>Pure plumbing — JSON and strings, no EDT runtime — so it is exhaustively unit-testable.</p>
 */
public final class CompositeCommandKeyGuard {

    private CompositeCommandKeyGuard() {
    }

    /**
     * One declared key that the dispatched command does not read.
     *
     * @param key            the key as the caller spelled it
     * @param owningCommands the commands that DO read it, in schema-enum order (never empty)
     */
    public record ForeignKey(String key, List<String> owningCommands) {
    }

    /**
     * The verdict for one argument map.
     *
     * @param command      the command the keys were judged against, normalized, or {@code null} when
     *                     nothing could be judged
     * @param foreignKeys  declared keys belonging to other commands only, in the caller's key order
     * @param acceptedKeys every key {@code command} itself accepts (its own plus the common ones)
     * @param enforceable  whether the schema and command could be used at all; {@code false} means
     *                     fail-open and {@code foreignKeys} is always empty
     */
    public record Report(String command, List<ForeignKey> foreignKeys, Set<String> acceptedKeys,
            boolean enforceable) {

        public boolean isClean() {
            return foreignKeys.isEmpty();
        }
    }

    private static final Report CLEAN_UNENFORCEABLE = new Report(null, List.of(), Set.of(), false);

    /** Key → the commands that read it; a common key owns every command. */
    private record Contract(Set<String> commands, Map<String, Set<String>> owners) {
    }

    /**
     * Inspects {@code actualKeys} against the branch of {@code schemaJson} that {@code command}
     * dispatches to.
     *
     * @param schemaJson the composite tool's advertised parameter schema
     * @param command    the caller's {@code command} value; normalized here, so raw input is fine
     * @param actualKeys the top-level keys the caller passed
     */
    public static Report inspect(String schemaJson, String command, Collection<?> actualKeys) {
        Contract contract = contract(schemaJson);
        if (contract == null) {
            return CLEAN_UNENFORCEABLE;
        }
        String normalized = normalizeCommand(command);
        if (normalized == null || !contract.commands().contains(normalized)) {
            // No command, or one the tool itself will reject: its own "Unknown command" error is the
            // honest answer there, and guessing a branch would only add noise.
            return CLEAN_UNENFORCEABLE;
        }

        Set<String> accepted = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> owner : contract.owners().entrySet()) {
            if (owner.getValue().contains(normalized)) {
                accepted.add(owner.getKey());
            }
        }

        List<ForeignKey> foreign = new ArrayList<>();
        if (actualKeys != null) {
            for (Object rawKey : actualKeys) {
                if (rawKey == null) {
                    continue;
                }
                String key = String.valueOf(rawKey);
                Set<String> owners = contract.owners().get(key);
                if (owners == null || owners.contains(normalized)) {
                    // Not declared at all is SchemaKeyGuard's business, not this guard's.
                    continue;
                }
                foreign.add(new ForeignKey(key, List.copyOf(owners)));
            }
        }
        return new Report(normalized, List.copyOf(foreign), accepted, true);
    }

    /** The command values the schema advertises, in enum order; empty when it advertises none. */
    public static Set<String> declaredCommands(String schemaJson) {
        Contract contract = contract(schemaJson);
        return contract == null ? Set.of() : contract.commands();
    }

    /**
     * The extracted contract: every declared top-level key mapped to the commands that read it. A
     * common key maps to every command. Empty when the schema is not a tagged composite schema.
     */
    public static Map<String, Set<String>> commandsByKey(String schemaJson) {
        Contract contract = contract(schemaJson);
        return contract == null ? Map.of() : contract.owners();
    }

    /** The keys one command accepts — its own plus the common ones. Empty when not enforceable. */
    public static Set<String> acceptedKeys(String schemaJson, String command) {
        return inspect(schemaJson, command, List.of()).acceptedKeys();
    }

    // --- extraction ----------------------------------------------------------

    private static Contract contract(String schemaJson) {
        JsonObject properties = SchemaKeyGuard.declaredProperties(schemaJson);
        if (properties == null) {
            return null;
        }
        Set<String> commands = commandEnum(properties);
        if (commands.isEmpty()) {
            return null;
        }
        Map<String, Set<String>> owners = new LinkedHashMap<>();
        boolean anyRestricted = false;
        for (String name : properties.keySet()) {
            Set<String> owning = owningCommands(description(properties, name), commands);
            if (owning.size() < commands.size()) {
                anyRestricted = true;
            }
            owners.put(name, owning);
        }
        if (!anyRestricted) {
            // Not a tagged composite schema: every key is common, so there is nothing this guard can
            // say. Reported as unenforceable rather than as a clean verdict, so callers can tell the
            // difference between "checked, fine" and "not checked".
            return null;
        }
        return new Contract(commands, owners);
    }

    private static Set<String> commandEnum(JsonObject properties) {
        JsonElement commandElement = properties.get("command"); //$NON-NLS-1$
        if (commandElement == null || !commandElement.isJsonObject()) {
            return Set.of();
        }
        JsonElement values = commandElement.getAsJsonObject().get("enum"); //$NON-NLS-1$
        if (values == null || !values.isJsonArray()) {
            return Set.of();
        }
        Set<String> commands = new LinkedHashSet<>();
        for (JsonElement value : values.getAsJsonArray()) {
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String command = normalizeCommand(value.getAsString());
                if (command != null) {
                    commands.add(command);
                }
            }
        }
        return commands;
    }

    /**
     * The commands a property's description assigns it to. Returns the whole command set — meaning
     * "common, accepted everywhere" — for an untagged description, a prose tag, or a tag that matches
     * no declared command.
     */
    static Set<String> owningCommands(String description, Set<String> commands) {
        String tag = leadingTag(description);
        if (tag == null) {
            return commands;
        }
        Set<String> owners = new LinkedHashSet<>();
        for (String rawToken : tag.split("[/,]")) { //$NON-NLS-1$
            String token = normalizeCommand(rawToken);
            if (token == null) {
                continue;
            }
            if (token.indexOf(' ') >= 0) {
                // Prose, not a command name — "(mutating commands)". Applies to whatever the tool
                // decides, so the key stays common.
                return commands;
            }
            Set<String> matched = matchingCommands(token, commands);
            if (matched.isEmpty()) {
                // The tag names something that is not a command ("(optional)"), or it drifted from the
                // enum. Either way, refusing on it would be guessing.
                return commands;
            }
            owners.addAll(matched);
        }
        if (owners.isEmpty()) {
            return commands;
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String command : commands) {
            if (owners.contains(command)) {
                ordered.add(command);
            }
        }
        return ordered;
    }

    /**
     * Commands a tag token names: the command itself, or every command it prefixes, so {@code create}
     * reaches {@code create_report} and {@code create_processing} as well as a bare {@code create}.
     */
    private static Set<String> matchingCommands(String token, Set<String> commands) {
        Set<String> matched = new LinkedHashSet<>();
        for (String command : commands) {
            if (command.equals(token) || command.startsWith(token + "_")) { //$NON-NLS-1$
                matched.add(command);
            }
        }
        return matched;
    }

    /** The contents of a description's leading {@code (...)}, or {@code null} when there is none. */
    static String leadingTag(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '(') {
            return null;
        }
        int close = trimmed.indexOf(')');
        if (close < 0) {
            return null;
        }
        String inner = trimmed.substring(1, close).trim();
        return inner.isEmpty() ? null : inner;
    }

    private static String description(JsonObject properties, String name) {
        JsonElement spec = properties.get(name);
        if (spec == null || !spec.isJsonObject()) {
            return null;
        }
        JsonElement description = spec.getAsJsonObject().get("description"); //$NON-NLS-1$
        if (description == null || !description.isJsonPrimitive()
                || !description.getAsJsonPrimitive().isString()) {
            return null;
        }
        return description.getAsString();
    }

    /** Lowercases and trims, matching how {@code ValidationOperation} normalizes a command. */
    static String normalizeCommand(String command) {
        if (command == null) {
            return null;
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    // --- messages ------------------------------------------------------------

    /**
     * The refusal {@code edt_validate_request} returns instead of a token. Names the key, the command
     * that owns it, and what the requested command really accepts, because a bare "unknown parameter"
     * leaves the caller no way to fix the call.
     */
    public static String refusalMessage(String toolName, String command, List<ForeignKey> foreignKeys,
            Collection<String> acceptedKeys) {
        StringBuilder sb = new StringBuilder();
        sb.append("payload contains "); //$NON-NLS-1$
        sb.append(foreignKeys.size() == 1
                ? "a key that belongs to another " //$NON-NLS-1$
                : foreignKeys.size() + " keys that belong to other "); //$NON-NLS-1$
        sb.append(toolName).append(" command, not to '").append(command).append("': "); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(describe(foreignKeys));
        sb.append(". No validation token was issued: ").append(toolName) //$NON-NLS-1$
                .append(" reads only the parameters of the command it dispatches, so the value would") //$NON-NLS-1$
                .append(" have been dropped while the operation reported success."); //$NON-NLS-1$
        SchemaKeyGuard.appendAccepted(sb, "Keys accepted by '" + command + "'", acceptedKeys); //$NON-NLS-1$ //$NON-NLS-2$
        return sb.toString();
    }

    /**
     * The advisory line appended to a tool result — the read-command half, where the call already ran
     * and failing it retroactively would help nobody. Never fails a call.
     */
    public static String advisoryLine(String toolName, String command, List<ForeignKey> foreignKeys,
            Collection<String> acceptedKeys) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nNote: ").append(toolName).append(" ignored "); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(foreignKeys.size() == 1
                ? "a parameter that belongs to another command" //$NON-NLS-1$
                : foreignKeys.size() + " parameters that belong to other commands"); //$NON-NLS-1$
        sb.append(", not to '").append(command).append("': "); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(describe(foreignKeys));
        sb.append(". It had no effect on this call."); //$NON-NLS-1$
        SchemaKeyGuard.appendAccepted(sb, "Parameters accepted by '" + command + "'", acceptedKeys); //$NON-NLS-1$ //$NON-NLS-2$
        return sb.toString();
    }

    private static String describe(List<ForeignKey> foreignKeys) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < foreignKeys.size(); i++) {
            if (i > 0) {
                sb.append(", "); //$NON-NLS-1$
            }
            ForeignKey foreign = foreignKeys.get(i);
            sb.append('\'').append(foreign.key()).append("' (belongs to "); //$NON-NLS-1$
            List<String> owners = foreign.owningCommands();
            for (int j = 0; j < owners.size(); j++) {
                if (j > 0) {
                    sb.append(" or "); //$NON-NLS-1$
                }
                sb.append('\'').append(owners.get(j)).append('\'');
            }
            sb.append(')');
        }
        return sb.toString();
    }
}
