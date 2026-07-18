package com.codepilot1c.core.edt.publication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;

import org.junit.Test;

/**
 * Plain-JUnit tests for {@link EdtWebPublicationService#vrdLocation(String, String)} — the Gap A
 * fix that keeps the generated Apache {@code Alias} target's trailing separator consistent with the
 * slash-terminated URL alias (EDT stores the re-pointed publication name with a trailing slash).
 */
public class EdtWebPublicationServiceHelpersTest {

    @Test
    public void slashTerminatedNameGetsSeparatorAppendedToLocation() {
        assertEquals("C:\\pub\\agent-current" + File.separator, //$NON-NLS-1$
                EdtWebPublicationService.vrdLocation("C:\\pub\\agent-current", "agent-current/")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void bareNameLeavesLocationUnchanged() {
        // Fresh publish: alias "/agent-current" + remainder keeps its own leading slash — no glue bug.
        assertEquals("C:\\pub\\agent-current", //$NON-NLS-1$
                EdtWebPublicationService.vrdLocation("C:\\pub\\agent-current", "agent-current")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void alreadySeparatedLocationIsNotDoubled() {
        assertEquals("C:\\pub\\agent-current\\", //$NON-NLS-1$
                EdtWebPublicationService.vrdLocation("C:\\pub\\agent-current\\", "agent-current/")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("C:/pub/agent-current/", //$NON-NLS-1$
                EdtWebPublicationService.vrdLocation("C:/pub/agent-current/", "agent-current/")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void backslashTerminatedNameCountsAsSlashTerminated() {
        assertEquals("C:\\pub\\agent-current" + File.separator, //$NON-NLS-1$
                EdtWebPublicationService.vrdLocation("C:\\pub\\agent-current", "agent-current\\")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void nullNameLeavesLocationUnchanged() {
        assertEquals("C:\\pub\\agent-current", //$NON-NLS-1$
                EdtWebPublicationService.vrdLocation("C:\\pub\\agent-current", null)); //$NON-NLS-1$
    }

    @Test
    public void nullOrEmptyLocationReturnedAsIs() {
        assertNull(EdtWebPublicationService.vrdLocation(null, "agent-current/")); //$NON-NLS-1$
        assertEquals("", EdtWebPublicationService.vrdLocation("", "agent-current/")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
