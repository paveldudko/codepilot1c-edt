package com.codepilot1c.core.tools;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.tools.metadata.EdtValidateRequestTool;

public class EdtValidateRequestToolSchemaTest {

    @Test
    public void schemaListsEnsureModuleArtifactOperation() {
        String schema = new EdtValidateRequestTool().getParameterSchema();
        assertTrue(schema.contains("\"ensure_module_artifact\"")); //$NON-NLS-1$
    }

    /**
     * The advertised {@code operation} enum must stay in sync with the operations
     * {@link ValidationOperation} actually accepts — a stale enum silently under-advertises working
     * operations. rights_manage was missing, so a caller had to discover it by trial
     * (codepilot1c-feedback 2026-07-16-rights-manage-reports-success-but-does-not-persist §ask3).
     */
    @Test
    public void schemaAdvertisesEveryValidationOperation() {
        String schema = new EdtValidateRequestTool().getParameterSchema();
        for (ValidationOperation op : ValidationOperation.values()) {
            assertTrue("advertised operation enum is missing \"" + op.getToolName() + "\"", //$NON-NLS-1$ //$NON-NLS-2$
                    schema.contains("\"" + op.getToolName() + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void schemaAdvertisesRightsManage() {
        String schema = new EdtValidateRequestTool().getParameterSchema();
        assertTrue(schema.contains("\"rights_manage\"")); //$NON-NLS-1$
    }
}
