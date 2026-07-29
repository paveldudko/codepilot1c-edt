package com.codepilot1c.core.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationRequest;
import com.codepilot1c.core.edt.validation.ValidationResult;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Behavioural tests for the {@code edt_validate_request} payload-key guard (L2).
 *
 * <p>The defect, proven live on a running sandbox EDT on 2026-07-29: an MCP caller passed
 * {@code type} at the TOP LEVEL of {@code add_metadata_child} instead of inside {@code properties}.
 * {@code edt_validate_request} answered {@code {"valid":true, … "normalizedPayload":{…no type at
 * all…}}} and issued a token; {@code add_metadata_child} then ran with empty {@code properties}, took
 * its "no type requested" branch, wrote {@code <types>String</types><length>150</length>} to the
 * Catalog .mdo — and reported full success. A mistyped parameter therefore produced wrong metadata on
 * disk PLUS a success report. The identical call with {@code properties:{type:["String","Boolean"]}}
 * writes both types correctly, so the whole defect was the silent swallow.</p>
 *
 * <p>These tests exercise the tool, not its source: the assertions are on the returned
 * {@link ToolResult} and on whether the validation service was reached at all. The stub service fails
 * the test if it is called for a refused request, because issuing a token is exactly what must not
 * happen. The "still accepted" cases are the important half — a false refusal on a legitimate call
 * would be worse than the bug.</p>
 */
public class EdtValidateRequestUnknownPayloadKeyTest {

    // --- the proven live case ------------------------------------------------

    @Test
    public void aTopLevelTypeOnAddMetadataChildIsRefusedAndNamesPropertiesAsItsHome() {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "add_metadata_child", payload( //$NON-NLS-1$
                "project", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "parent_fqn", "Catalog.Goods", //$NON-NLS-1$ //$NON-NLS-2$
                "child_kind", "Attribute", //$NON-NLS-1$ //$NON-NLS-2$
                "name", "Owner", //$NON-NLS-1$ //$NON-NLS-2$
                "type", List.of("String", "Boolean"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse("a payload whose 'type' would be dropped must not be validated", //$NON-NLS-1$
                result.isSuccess());
        JsonObject error = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals("KNOWLEDGE_REQUIRED", error.get("error").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        String message = error.get("message").getAsString(); //$NON-NLS-1$
        assertTrue(message, message.contains("'type'")); //$NON-NLS-1$
        assertTrue("the refusal must say where 'type' belongs: " + message, //$NON-NLS-1$
                message.contains("did you mean 'properties.type'?")); //$NON-NLS-1$
        assertTrue(message, message.contains("No validation token was issued")); //$NON-NLS-1$
        assertTrue("the refusal must list what IS accepted: " + message, //$NON-NLS-1$
                message.contains("parent_fqn") && message.contains("properties")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aRefusedRequestNeverReachesTheValidationServiceSoNoTokenExists() {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "add_metadata_child", payload( //$NON-NLS-1$
                "project", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "parent_fqn", "Catalog.Goods", //$NON-NLS-1$ //$NON-NLS-2$
                "child_kind", "Attribute", //$NON-NLS-1$ //$NON-NLS-2$
                "name", "Owner", //$NON-NLS-1$ //$NON-NLS-2$
                "type", "String")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        assertNull("the guard must short-circuit before a token is issued", service.lastRequest); //$NON-NLS-1$
        assertNull(result.getContent());
    }

    @Test
    public void theSameCallWithTypeInsidePropertiesIsValidatedNormally() {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "add_metadata_child", payload( //$NON-NLS-1$
                "project", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "parent_fqn", "Catalog.Goods", //$NON-NLS-1$ //$NON-NLS-2$
                "child_kind", "Attribute", //$NON-NLS-1$ //$NON-NLS-2$
                "name", "Owner", //$NON-NLS-1$ //$NON-NLS-2$
                "properties", Map.of("type", List.of("String", "Boolean")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue(errorOf(result), result.isSuccess());
        assertEquals("Sandbox", service.lastRequest.projectName()); //$NON-NLS-1$
    }

    // --- no false refusals on legitimate payloads ---------------------------

    @Test
    public void everyDeclaredTopLevelParameterOfAddMetadataChildIsAccepted() {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "add_metadata_child", payload( //$NON-NLS-1$
                "project", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "parent_fqn", "Catalog.Goods", //$NON-NLS-1$ //$NON-NLS-2$
                "child_kind", "Form", //$NON-NLS-1$ //$NON-NLS-2$
                "name", "ItemForm", //$NON-NLS-1$ //$NON-NLS-2$
                "synonym", "Форма", //$NON-NLS-1$ //$NON-NLS-2$
                "comment", "c", //$NON-NLS-1$ //$NON-NLS-2$
                "form_usage", "OBJECT", //$NON-NLS-1$ //$NON-NLS-2$
                "managed", Boolean.TRUE, //$NON-NLS-1$
                "set_as_default", Boolean.TRUE, //$NON-NLS-1$
                "wait_ms", Integer.valueOf(5000), //$NON-NLS-1$
                "template_type", "spreadsheet", //$NON-NLS-1$ //$NON-NLS-2$
                "properties", Map.of())); //$NON-NLS-1$

        assertTrue(errorOf(result), result.isSuccess());
    }

    @Test
    public void aCompositeDcsManagePayloadWithItsCommandIsAccepted() {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "dcs_manage", payload( //$NON-NLS-1$
                "command", "create_schema", //$NON-NLS-1$ //$NON-NLS-2$
                "project", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "owner_fqn", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$
                "template_name", "MainDataCompositionSchema", //$NON-NLS-1$ //$NON-NLS-2$
                "force_replace", Boolean.FALSE)); //$NON-NLS-1$

        assertTrue(errorOf(result), result.isSuccess());
    }

    @Test
    public void aResolvedPerCommandOperationNameStillAcceptsTheCompositeSchemaKeys() {
        // edt_validate_request accepts BOTH 'dcs_manage' + payload.command and the resolved
        // 'dcs_create_main_schema'. Either way the payload lands at dcs_manage, so its schema governs.
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "dcs_create_main_schema", payload( //$NON-NLS-1$
                "project", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "owner_fqn", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$
                "template_name", "MainDataCompositionSchema")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(errorOf(result), result.isSuccess());
    }

    @Test
    public void anExtensionManagePayloadIsAccepted() {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "extension_manage", payload( //$NON-NLS-1$
                "command", "adopt", //$NON-NLS-1$ //$NON-NLS-2$
                "project", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "base_project", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "extension_project", "Ext", //$NON-NLS-1$ //$NON-NLS-2$
                "source_object_fqn", "Catalog.Goods", //$NON-NLS-1$ //$NON-NLS-2$
                "update_if_exists", Boolean.TRUE)); //$NON-NLS-1$

        assertTrue(errorOf(result), result.isSuccess());
    }

    @Test
    public void anExternalManagePayloadIsAccepted() {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "external_manage", payload( //$NON-NLS-1$
                "command", "create_report", //$NON-NLS-1$ //$NON-NLS-2$
                "project", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "external_project", "ExtReports", //$NON-NLS-1$ //$NON-NLS-2$
                "name", "SalesReport", //$NON-NLS-1$ //$NON-NLS-2$
                "project_path", "/tmp/x", //$NON-NLS-1$ //$NON-NLS-2$
                "version", "1.0", //$NON-NLS-1$ //$NON-NLS-2$
                "synonym", "s", //$NON-NLS-1$ //$NON-NLS-2$
                "comment", "c")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(errorOf(result), result.isSuccess());
    }

    @Test
    public void theCamelCaseAliasesEnsureModuleArtifactReallyReadsAreAccepted() {
        // MetadataRequestValidationService.normalizeEnsureModuleArtifactPayload reads objectFqn /
        // moduleKind / createIfMissing, so those keys are NOT silently discarded and must not be
        // refused even though ensure_module_artifact's schema advertises only the snake_case forms.
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "ensure_module_artifact", payload( //$NON-NLS-1$
                "project", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "objectFqn", "Catalog.Goods", //$NON-NLS-1$ //$NON-NLS-2$
                "moduleKind", "object", //$NON-NLS-1$ //$NON-NLS-2$
                "createIfMissing", Boolean.TRUE, //$NON-NLS-1$
                "initialContent", "")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(errorOf(result), result.isSuccess());
    }

    @Test
    public void theRoleAliasesRightsManageReallyReadsAreAccepted() {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "rights_manage", payload( //$NON-NLS-1$
                "project", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "role_fqn", "Role.FullAccess", //$NON-NLS-1$ //$NON-NLS-2$
                "grants", List.of())); //$NON-NLS-1$

        assertTrue(errorOf(result), result.isSuccess());
    }

    @Test
    public void theAdoptExistingAliasCreateMetadataReallyReadsIsAccepted() {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "create_metadata", payload( //$NON-NLS-1$
                "project", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "kind", "Catalog", //$NON-NLS-1$ //$NON-NLS-2$
                "name", "Goods", //$NON-NLS-1$ //$NON-NLS-2$
                "adoptExisting", Boolean.TRUE)); //$NON-NLS-1$

        assertTrue(errorOf(result), result.isSuccess());
    }

    // --- other spellings of the same footgun --------------------------------

    @Test
    public void aMisspelledKeyIsRefusedWithATypoSuggestion() {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "update_metadata", payload( //$NON-NLS-1$
                "project", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "target_fqn", "Catalog.Goods.Attribute.Owner", //$NON-NLS-1$ //$NON-NLS-2$
                "chages", Map.of("type", "String"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse(result.isSuccess());
        String message = JsonParser.parseString(result.getErrorMessage())
                .getAsJsonObject().get("message").getAsString(); //$NON-NLS-1$
        assertTrue(message, message.contains("'chages'")); //$NON-NLS-1$
        assertTrue(message, message.contains("did you mean 'changes'?")); //$NON-NLS-1$
    }

    @Test
    public void everyUnknownKeyIsNamedNotJustTheFirst() {
        RecordingValidationService service = new RecordingValidationService();
        ToolResult result = validate(service, "delete_metadata", payload( //$NON-NLS-1$
                "project", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "target_fqn", "Catalog.Goods", //$NON-NLS-1$ //$NON-NLS-2$
                "cascade", Boolean.TRUE, //$NON-NLS-1$
                "dry_run", Boolean.TRUE)); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        String message = JsonParser.parseString(result.getErrorMessage())
                .getAsJsonObject().get("message").getAsString(); //$NON-NLS-1$
        assertTrue(message, message.contains("2 unknown top-level keys")); //$NON-NLS-1$
        assertTrue(message, message.contains("'cascade'")); //$NON-NLS-1$
        assertTrue(message, message.contains("'dry_run'")); //$NON-NLS-1$
    }

    // --- helpers ------------------------------------------------------------

    private ToolResult validate(MetadataRequestValidationService service, String operation,
            Map<String, Object> payload) {
        return new EdtValidateRequestTool(service).execute(Map.of(
                "project", "Sandbox", //$NON-NLS-1$ //$NON-NLS-2$
                "operation", operation, //$NON-NLS-1$
                "payload", payload)).join(); //$NON-NLS-1$
    }

    private static Map<String, Object> payload(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }

    private static String errorOf(ToolResult result) {
        return result.isSuccess() ? "" : String.valueOf(result.getErrorMessage()); //$NON-NLS-1$
    }

    /**
     * Stands in for the real service: records the request instead of touching EDT. If the guard works,
     * a refused request never gets here at all.
     */
    private static final class RecordingValidationService extends MetadataRequestValidationService {
        private ValidationRequest lastRequest;

        @Override
        public ValidationResult validateAndIssueToken(ValidationRequest request) {
            lastRequest = request;
            return new ValidationResult(
                    true,
                    request.projectName(),
                    request.operation().getToolName(),
                    List.of("ok"), //$NON-NLS-1$
                    request.payload(),
                    "token-1", //$NON-NLS-1$
                    123L);
        }
    }
}
