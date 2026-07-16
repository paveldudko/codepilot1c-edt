package com.codepilot1c.core.edt.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Request to mutate a Role's object-level rights grants.
 *
 * <p>A Role's rights live in the rights model reachable through the BM as
 * {@code Role.getRights()} → {@code RoleDescription}; each grant targets an
 * {@code MdObject} (or sub-object) and one named {@code Right} (Read, Update,
 * Delete, View, Edit, DeletionMark, …) and sets it to one of
 * {@code set} / {@code unset} / {@code provided}.</p>
 */
public record RightsManageRequest(
        String projectName,
        String roleFqn,
        List<RightGrant> grants
) {

    /** Canonical right-value tokens accepted in the {@code value} field. */
    public static final String VALUE_SET = "set"; //$NON-NLS-1$
    public static final String VALUE_UNSET = "unset"; //$NON-NLS-1$
    public static final String VALUE_PROVIDED = "provided"; //$NON-NLS-1$
    /**
     * Fully remove the explicit right entry (and prune the now-empty {@code <object>} block), rather
     * than writing {@code <value>false</value>} ({@link #VALUE_UNSET}) or a valueless inherited entry
     * ({@link #VALUE_PROVIDED}), both of which leave the object's rights block on disk. Needed to strip
     * a stray/invalid grant such as a rights-less-type block that stalls DB restructure (BF-12936).
     */
    public static final String VALUE_REMOVE = "remove"; //$NON-NLS-1$

    /** A single object-level right grant. {@code value} is one of set/unset/provided. */
    public record RightGrant(String objectFqn, String right, String value) {
    }

    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (roleFqn == null || roleFqn.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "role is required", false); //$NON-NLS-1$
        }
        if (grants == null || grants.isEmpty()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "grants are required", false); //$NON-NLS-1$
        }
    }

    /**
     * Parse the {@code grants} payload into normalized {@link RightGrant} records.
     * Pure (no EMF/BM access) so the parsing contract is unit-testable. Accepts a
     * list of {@code {object_fqn, right, value}} maps (or a single such map).
     *
     * <ul>
     *   <li>object key: {@code object_fqn}/{@code object}/{@code fqn}/{@code target_fqn};</li>
     *   <li>right key: {@code right}/{@code right_name}/{@code name};</li>
     *   <li>value key: {@code value}/{@code state}/{@code access} — accepts
     *       set/true/grant/allow, unset/false/revoke/deny, provided/inherit/default;
     *       defaults to {@code set} when omitted.</li>
     * </ul>
     */
    public static List<RightGrant> parseGrants(Object raw, String field) {
        if (raw == null) {
            return List.of();
        }
        List<?> source;
        if (raw instanceof List<?> list) {
            source = list;
        } else if (raw instanceof Map<?, ?>) {
            source = List.of(raw);
        } else {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "'" + field + "' must be a list of {object_fqn, right, value} objects", false); //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<RightGrant> result = new ArrayList<>(source.size());
        for (Object item : source) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "Each '" + field + "' entry must be a {object_fqn, right, value} object", false); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String objectFqn = trimmed(firstValue(entry, "object_fqn", "object", "fqn", "target_fqn")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            if (objectFqn == null || objectFqn.isBlank()) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "Each '" + field + "' entry requires a non-empty 'object_fqn'", false); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String right = trimmed(firstValue(entry, "right", "right_name", "name")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (right == null || right.isBlank()) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "Each '" + field + "' entry requires a non-empty 'right' for " + objectFqn, false); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String value = normalizeValue(firstValue(entry, "value", "state", "access"), objectFqn, right); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            result.add(new RightGrant(objectFqn, right, value));
        }
        return result;
    }

    private static String normalizeValue(Object raw, String objectFqn, String right) {
        if (raw == null) {
            return VALUE_SET;
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "", "set", "true", "grant", "allow", "1" -> VALUE_SET; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            case "unset", "false", "revoke", "deny", "0" -> VALUE_UNSET; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            case "provided", "inherit", "inherited", "default" -> VALUE_PROVIDED; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "remove", "clear", "delete", "drop" -> VALUE_REMOVE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            default -> throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "Invalid right value '" + raw + "' for " + right + " on " + objectFqn //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            + " (expected set/unset/provided/remove)", false); //$NON-NLS-1$
        };
    }

    private static Object firstValue(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String str && str.equalsIgnoreCase(key)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static String trimmed(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
