package com.codepilot1c.core.edt.metadata;

import java.util.List;

/**
 * Request for compound EventSubscription creation.
 */
public record CreateEventSubscriptionRequest(
        String projectName,
        String name,
        String synonym,
        String comment,
        List<String> sourceTypes,
        String event,
        String handler
) {
    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (!MetadataNameValidator.isValidName(name)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "Invalid metadata name: " + name, false); //$NON-NLS-1$
        }
        if (handler == null || handler.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "handler is required (format: CommonModuleName.ProcedureName)", false); //$NON-NLS-1$
        }
    }
}
