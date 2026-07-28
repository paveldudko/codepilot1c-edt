package com.codepilot1c.core.edt.metadata;

import java.util.Map;

/**
 * Request for top-level metadata creation.
 *
 * @param adoptExisting when {@code true}, an object that is already an attached BM top
 *                      object but is missing from the configuration's typed collection is
 *                      registered instead of refused (BF-13405). Defaults to {@code false}:
 *                      the orphan-registration state is rare enough that silently taking
 *                      over a pre-existing object would hide a real problem.
 */
public record CreateMetadataRequest(
        String projectName,
        MetadataKind kind,
        String name,
        String synonym,
        String comment,
        Map<String, Object> properties,
        Boolean adoptExisting
) {
    /** Historical shape: creation only, never adopts. */
    public CreateMetadataRequest(
            String projectName,
            MetadataKind kind,
            String name,
            String synonym,
            String comment,
            Map<String, Object> properties
    ) {
        this(projectName, kind, name, synonym, comment, properties, Boolean.FALSE);
    }

    public boolean shouldAdoptExisting() {
        return Boolean.TRUE.equals(adoptExisting);
    }

    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (kind == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "kind is required", false); //$NON-NLS-1$
        }
        if (!MetadataNameValidator.isValidName(name)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "Invalid metadata name: " + name, false); //$NON-NLS-1$
        }
    }
}
