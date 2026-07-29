package com.codepilot1c.core.edt.dcs;

import java.util.Locale;

/**
 * Pure, runtime-free helpers for the DCS main-schema mutation path.
 *
 * <p>Everything here is static and operates on strings, so it is unit-testable in a plain
 * JVM — the EMF/BM orchestration stays in {@link EdtDcsService}. Two jobs:</p>
 *
 * <ul>
 *   <li><b>Name-slot classification</b> — the idempotency predicate that decides whether a
 *       requested template name is free, reusable as a DCS template, or occupied by a
 *       template of another type. Without it {@code create_schema} appended a second
 *       {@code <templates>} entry with the same name.</li>
 *   <li><b>FQN / path shapes</b> — the <em>expected</em> shape of the schema's external
 *       top-object FQN and of its on-disk artifact. The authority for the FQN at runtime is
 *       {@code ITopObjectFqnGenerator.generateExternalPropertyFqn}; the shape computed here
 *       is used for the {@code [dcs]} diagnostic cross-check and for the honest post-write
 *       file-state probe (a DCS schema is a SEPARATE file, so "the owner exported fine" is
 *       not evidence that the schema landed).</li>
 * </ul>
 */
public final class DcsSchemaSupport {

    /**
     * Template name used when the caller passes none. It is resolved ONLY at the point of use, by
     * {@link DcsCreateMainSchemaRequest#effectiveTemplateName()}:
     * {@code MetadataRequestValidationService.normalizeDcsCreateMainSchemaPayload} deliberately does
     * NOT materialize it into the validated payload, which carries {@code template_name} only when
     * the caller passed one. That absence is what keeps "explicitly asked for
     * {@code MainDataCompositionSchema}" distinguishable from "asked for nothing" downstream — see
     * {@link DcsCreateMainSchemaRequest#hasExplicitTemplateName()}.
     */
    public static final String DEFAULT_TEMPLATE_NAME = "MainDataCompositionSchema"; //$NON-NLS-1$

    /** Segment the EDT FQN grammar uses for a template owned by a top-level object. */
    private static final String TEMPLATE_SEGMENT = "Template"; //$NON-NLS-1$

    /** File name of the serialized schema inside {@code Templates/<name>/}. */
    private static final String SCHEMA_FILE_NAME = "Template.dcs"; //$NON-NLS-1$

    /** Verdict for the requested template-name slot in the owner's {@code templates} list. */
    public enum NameSlotState {
        /** No template carries the requested name — a new one must be created. */
        FREE,
        /** A template with the requested name exists and already is a DCS template. */
        REUSABLE_DCS,
        /** A template with the requested name exists but carries another {@code templateType}. */
        OCCUPIED_OTHER_TYPE
    }

    private DcsSchemaSupport() {
    }

    /**
     * Case-insensitive, whitespace-tolerant template-name equality. 1C metadata names are
     * case-insensitive for collision purposes, so {@code mainDataCompositionSchema} and
     * {@code MainDataCompositionSchema} are the SAME slot — treating them as different is what
     * produced two {@code <templates>} entries with (effectively) one name.
     */
    public static boolean nameMatches(String candidate, String wanted) {
        if (candidate == null || wanted == null) {
            return false;
        }
        return candidate.trim().equalsIgnoreCase(wanted.trim());
    }

    /**
     * Classifies the requested name slot. {@code existingName} is the name of the template found
     * for {@code wantedName} (or {@code null} when the owner has no such template);
     * {@code existingIsDcsType} tells whether that template's {@code templateType} already is
     * {@code DataCompositionSchema}.
     */
    public static NameSlotState classifyNameSlot(String existingName, boolean existingIsDcsType, String wantedName) {
        if (!nameMatches(existingName, wantedName)) {
            return NameSlotState.FREE;
        }
        return existingIsDcsType ? NameSlotState.REUSABLE_DCS : NameSlotState.OCCUPIED_OTHER_TYPE;
    }

    /**
     * Top-level FQN of the owner, e.g. {@code Report.Sales} from {@code Report.Sales.Template.X}.
     * The owner kind is canonicalized, so a Russian alias still yields the BM/English form.
     */
    public static String topLevelFqn(String ownerFqn) {
        String kind = canonicalOwnerKind(ownerKind(ownerFqn));
        String name = ownerName(ownerFqn);
        if (kind == null || name == null || name.isBlank()) {
            return null;
        }
        return kind + "." + name; //$NON-NLS-1$
    }

    /**
     * FQN of the {@code Template} object itself, e.g. {@code Report.Sales.Template.MainSchema}.
     * This is the value serialized into {@code <mainDataCompositionSchema>} in the owner's
     * {@code .mdo} (verified against a real EDT project on disk).
     */
    public static String templateFqn(String ownerFqn, String templateName) {
        String ownerTop = topLevelFqn(ownerFqn);
        if (ownerTop == null || templateName == null || templateName.isBlank()) {
            return null;
        }
        return ownerTop + "." + TEMPLATE_SEGMENT + "." + templateName.trim(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Expected external-property FQN of the schema top-object, e.g.
     * {@code Report.Sales.Template.MainSchema.Template}.
     *
     * <p>Shape confirmed by decompiling {@code MdTopObjectFqnGeneratorDelegate}: the delegate
     * appends {@code capitalize(reference.getName())} to the owner's qualified name, and the
     * reference here is {@code BasicTemplate.template}. Used ONLY as a diagnostic cross-check
     * against the generator's answer — the generator remains the authority, because it is what
     * {@code attachTopObject} and the exporter agree on.</p>
     */
    public static String expectedExternalSchemaFqn(String ownerFqn, String templateName) {
        String templateFqn = templateFqn(ownerFqn, templateName);
        return templateFqn == null ? null : templateFqn + "." + TEMPLATE_SEGMENT; //$NON-NLS-1$
    }

    /**
     * Workspace-relative path of the serialized schema, e.g.
     * {@code src/Reports/Sales/Templates/MainSchema/Template.dcs}. Returns {@code null} for owner
     * kinds that have no {@code src} tree in the project (external report / external data
     * processor), where the probe is not meaningful.
     */
    public static String schemaFileRelativePath(String ownerFqn, String templateName) {
        String folder = ownerFolder(ownerKind(ownerFqn));
        String name = ownerName(ownerFqn);
        if (folder == null || name == null || name.isBlank() || templateName == null || templateName.isBlank()) {
            return null;
        }
        return "src/" + folder + "/" + name + "/Templates/" + templateName.trim() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "/" + SCHEMA_FILE_NAME; //$NON-NLS-1$
    }

    /**
     * Maps an owner kind token (English or Russian, singular) to its {@code src} folder.
     * {@code null} for kinds without an in-project {@code src} layout or unknown tokens.
     */
    public static String ownerFolder(String ownerKind) {
        return switch (normalize(ownerKind)) {
            case "report", "отчет", "отчёт" -> "Reports"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "dataprocessor", "обработка" -> "DataProcessors"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> null;
        };
    }

    /** Canonical (BM/English) owner kind for an English or Russian alias; {@code null} if unknown. */
    public static String canonicalOwnerKind(String ownerKind) {
        return switch (normalize(ownerKind)) {
            case "report", "отчет", "отчёт" -> "Report"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "dataprocessor", "обработка" -> "DataProcessor"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "externalreport" -> "ExternalReport"; //$NON-NLS-1$ //$NON-NLS-2$
            case "externaldataprocessor" -> "ExternalDataProcessor"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> null;
        };
    }

    private static String ownerKind(String ownerFqn) {
        String[] parts = split(ownerFqn);
        return parts.length > 0 ? parts[0] : null;
    }

    private static String ownerName(String ownerFqn) {
        String[] parts = split(ownerFqn);
        return parts.length > 1 ? parts[1] : null;
    }

    private static String[] split(String fqn) {
        return fqn == null ? new String[0] : fqn.trim().split("\\."); //$NON-NLS-1$
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }
}
