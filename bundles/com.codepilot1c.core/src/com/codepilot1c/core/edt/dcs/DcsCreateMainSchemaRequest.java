package com.codepilot1c.core.edt.dcs;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Request to create and bind main DCS schema for owner object.
 *
 * <p>{@code forceReplace} means REPLACE, not "add": a template already carrying the requested name
 * is reused and its schema content reset, so the owner never ends up with two {@code <templates>}
 * entries under one name.</p>
 *
 * <p>{@code templateName} stays {@code null} when the caller passed nothing — the
 * {@code MainDataCompositionSchema} default is resolved HERE, at the point of use, and is not
 * materialized into the validated payload upstream. That is what keeps "the caller explicitly asked
 * for {@code MainDataCompositionSchema}" distinguishable from "the caller asked for nothing"; see
 * {@link #hasExplicitTemplateName()}.</p>
 */
public record DcsCreateMainSchemaRequest(
        String projectName,
        String ownerFqn,
        String templateName,
        Boolean forceReplace
) {

    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "project is required",
                    false); //$NON-NLS-1$
        }
        if (ownerFqn == null || ownerFqn.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "owner_fqn is required",
                    false); //$NON-NLS-1$
        }
    }

    public String normalizedProjectName() {
        return projectName == null ? null : projectName.trim();
    }

    public String normalizedOwnerFqn() {
        return ownerFqn == null ? null : ownerFqn.trim();
    }

    /**
     * The name that will actually be applied: the caller's name trimmed, or the default when the
     * caller passed none. Deliberately the ONLY place the default is resolved, so validate-time and
     * apply-time can never disagree about it.
     */
    public String effectiveTemplateName() {
        if (templateName == null || templateName.isBlank()) {
            return DcsSchemaSupport.DEFAULT_TEMPLATE_NAME;
        }
        return templateName.trim();
    }

    /**
     * Whether {@link #effectiveTemplateName()} came from the caller rather than from the default.
     *
     * <p>Needed because the two cases are NOT interchangeable for diagnostics and for
     * {@code force_replace}: with {@code force_replace=true} and no template of the requested name,
     * the mutation rebinds the DCS template the owner already has — under ANOTHER name. Ignoring a
     * name the caller typed deserves a warning; ignoring a name nobody asked for is routine. Once
     * the default is materialized into the payload this question is unanswerable, which is why the
     * validated payload carries {@code template_name} only when it was explicit.</p>
     */
    public boolean hasExplicitTemplateName() {
        return templateName != null && !templateName.isBlank();
    }

    public boolean shouldForceReplace() {
        return Boolean.TRUE.equals(forceReplace);
    }
}
