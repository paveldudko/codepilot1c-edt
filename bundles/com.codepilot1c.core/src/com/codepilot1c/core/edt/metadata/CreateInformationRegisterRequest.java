package com.codepilot1c.core.edt.metadata;

import java.util.List;
import java.util.Map;

/**
 * Request for compound InformationRegister creation with dimensions and resources.
 */
public record CreateInformationRegisterRequest(
        String projectName,
        String name,
        String synonym,
        String comment,
        String periodicity,
        List<Map<String, Object>> dimensions,
        List<Map<String, Object>> resources
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
        if (dimensions != null) {
            for (Map<String, Object> dim : dimensions) {
                Object dimName = dim == null ? null : dim.get("name"); //$NON-NLS-1$
                if (!MetadataNameValidator.isValidName(dimName == null ? null : String.valueOf(dimName))) {
                    throw new MetadataOperationException(
                            MetadataOperationCode.INVALID_METADATA_NAME,
                            "Invalid dimension name: " + dimName, false); //$NON-NLS-1$
                }
            }
        }
        if (resources != null) {
            for (Map<String, Object> res : resources) {
                Object resName = res == null ? null : res.get("name"); //$NON-NLS-1$
                if (!MetadataNameValidator.isValidName(resName == null ? null : String.valueOf(resName))) {
                    throw new MetadataOperationException(
                            MetadataOperationCode.INVALID_METADATA_NAME,
                            "Invalid resource name: " + resName, false); //$NON-NLS-1$
                }
            }
        }
    }
}
