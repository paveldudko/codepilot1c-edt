package com.codepilot1c.core.edt.metadata;

/**
 * Outcome of {@code create_metadata} together with the registration mode that produced it.
 *
 * <p>BF-13405: an object can already be a resolvable BM top object (its {@code .mdo} was
 * imported and its FQN registered) while being absent from the configuration's typed
 * collection. Registering such an orphan is not the same act as creating an object, and the
 * caller must be able to tell the two apart — hence {@link #adopted()}. Kept as a separate
 * record so {@code MetadataOperationResult}, which many tools share, stays untouched.</p>
 *
 * @param result         the shared metadata-operation result
 * @param adopted        {@code true} when an already-attached top object was registered
 *                       instead of a new object being created
 * @param registeredInto the {@code Configuration.mdo} collection that received the entry
 */
public record CreateMetadataOutcome(MetadataOperationResult result, boolean adopted, String registeredInto) {

    public String formatForLlm() {
        StringBuilder text = new StringBuilder(result.formatForLlm());
        text.append("adopted: ").append(adopted).append('\n'); //$NON-NLS-1$
        if (registeredInto != null && !registeredInto.isBlank()) {
            text.append("registered_into: Configuration.").append(registeredInto).append('\n'); //$NON-NLS-1$
        }
        return text.toString();
    }
}
