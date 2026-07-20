package com.codepilot1c.core.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.AddMetadataChildRequest;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataChildKind;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataOperationResult;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.tools.ToolResult;

/**
 * Regression tests for {@code add_metadata_child} Command-property plumbing (BF-12936).
 *
 * <p>Background: authoring a register-owned {@code Command} with a
 * {@code commandParameterType} and {@code group} was impossible through the plugin.
 * The create path applied only name/synonym and <em>silently dropped</em> every other
 * supplied Command property (it early-returned for non-{@code BasicFeature} children),
 * so the written {@code .mdo} {@code <commands>} block carried only name+synonym with no
 * error raised. That forced a rule-violating hand-edit of the {@code .mdo}.</p>
 *
 * <p>These tests pin the tool-level plumbing: the {@code commandParameterType} / {@code group}
 * / {@code representation} properties survive validation normalization and the validation
 * token, and reach {@link EdtMetadataService#addMetadataChild} in the request's
 * {@code properties()} map. The BM-level application of those properties onto the created
 * command is exercised by live validation against a real EDT workspace.</p>
 */
public class AddMetadataChildToolCommandPropertiesTest {

    @Test
    public void schemaDocumentsCommandParameterTypeAndGroup() {
        AddMetadataChildTool tool = new AddMetadataChildTool();
        String schema = tool.getParameterSchema();
        assertNotNull(schema);
        assertTrue("schema must mention commandParameterType for Command authoring:\n" + schema, //$NON-NLS-1$
                schema.contains("commandParameterType")); //$NON-NLS-1$
        assertTrue("schema must mention group for Command authoring:\n" + schema, //$NON-NLS-1$
                schema.contains("group")); //$NON-NLS-1$
    }

    @Test
    public void commandPropertiesReachMetadataServiceRequest() {
        StubMetadataService metadataService = new StubMetadataService();
        StubValidationService validationService = new StubValidationService();
        AddMetadataChildTool tool = new AddMetadataChildTool(metadataService, validationService);

        List<String> paramTypes = new ArrayList<>();
        paramTypes.add("DocumentRef.Invoice"); //$NON-NLS-1$
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("commandParameterType", paramTypes); //$NON-NLS-1$
        properties.put("group", "FormCommandBarImportant"); //$NON-NLS-1$ //$NON-NLS-2$
        properties.put("representation", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("project", "AM"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("parent_fqn", "InformationRegister.FinanceVerification"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("child_kind", "Command"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "VerifyFinance"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("properties", properties); //$NON-NLS-1$
        params.put("validation_token", "token-1"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = tool.execute(params).join();

        assertTrue("tool execute must succeed for Command:\n" + result.getContent(), //$NON-NLS-1$
                result.isSuccess());
        assertNotNull("metadata service must receive the request", metadataService.lastRequest); //$NON-NLS-1$
        assertEquals(MetadataChildKind.COMMAND, metadataService.lastRequest.childKind());

        Map<String, Object> received = metadataService.lastRequest.properties();
        assertNotNull("command properties must survive normalization + token", received); //$NON-NLS-1$
        assertEquals("commandParameterType must reach the service verbatim", //$NON-NLS-1$
                paramTypes, received.get("commandParameterType")); //$NON-NLS-1$
        assertEquals("group must reach the service verbatim", //$NON-NLS-1$
                "FormCommandBarImportant", received.get("group")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("representation must reach the service verbatim", //$NON-NLS-1$
                "Auto", received.get("representation")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static final class StubMetadataService extends EdtMetadataService {
        private AddMetadataChildRequest lastRequest;

        @Override
        public MetadataOperationResult addMetadataChild(AddMetadataChildRequest request) {
            lastRequest = request;
            String fqn = request.parentFqn() + "." + request.childKind().getDisplayName() + "." + request.name(); //$NON-NLS-1$ //$NON-NLS-2$
            return new MetadataOperationResult(
                    true,
                    request.projectName(),
                    request.childKind().name(),
                    request.name(),
                    fqn,
                    "Metadata child object created successfully"); //$NON-NLS-1$
        }
    }

    private static final class StubValidationService extends MetadataRequestValidationService {
        private Map<String, Object> normalizedPayload;

        @Override
        public Map<String, Object> consumeToken(String token, ValidationOperation operation, String projectName) {
            if (!"token-1".equals(token)) { //$NON-NLS-1$
                throw new MetadataOperationException(
                        MetadataOperationCode.KNOWLEDGE_REQUIRED,
                        "unexpected token", false); //$NON-NLS-1$
            }
            return normalizedPayload;
        }

        @Override
        public Map<String, Object> normalizeAddChildPayload(
                String project,
                String parentFqn,
                String childKindValue,
                String name,
                String synonym,
                String comment,
                Map<String, Object> properties) {
            Map<String, Object> payload = super.normalizeAddChildPayload(
                    project, parentFqn, childKindValue, name, synonym, comment, properties);
            normalizedPayload = payload;
            return payload;
        }
    }
}
