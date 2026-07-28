package com.codepilot1c.core.edt.dcs;

/**
 * Result of main DCS schema create/bind mutation.
 *
 * <p>The last block reports the REAL post-write state: a data composition schema is a separate
 * top-object with its own {@code Templates/&lt;name&gt;/Template.dcs} file, so a committed BM
 * transaction is no evidence that anything reached the working tree. {@code schemaFilePresent} /
 * {@code stateMessage} exist so the tool can no longer report success without an artifact.</p>
 *
 * @param schemaFqn FQN of the {@code Template} object, e.g. {@code Report.Sales.Template.MainSchema}
 * @param schemaExternalFqn FQN of the schema top-object itself (the {@code Template} suffix form),
 *        i.e. what the exporter had to be pointed at
 * @param schemaFile workspace-relative path of the serialized schema, empty when not applicable
 * @param schemaFilePresent whether that file exists on disk after the export
 * @param stateMessage human-readable advisory about the on-disk state, empty when not applicable
 */
public record DcsCreateMainSchemaResult(
        String projectName,
        String ownerFqn,
        String ownerKind,
        String templateName,
        boolean schemaCreated,
        boolean templateCreated,
        boolean mainBindingUpdated,
        String schemaSource,
        String schemaFqn,
        String schemaExternalFqn,
        String schemaFile,
        boolean schemaFilePresent,
        String stateMessage
) {
}
