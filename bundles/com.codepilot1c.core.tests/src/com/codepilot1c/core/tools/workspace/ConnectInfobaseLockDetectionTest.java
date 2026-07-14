package com.codepilot1c.core.tools.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the pure exception-chain inspection that lets {@code connect_infobase} tell a
 * Designer lock-disconnect apart from a credential-prompt timeout (returns EDT_INFOBASE_LOCKED
 * instead of EDT_AUTH_REQUIRED). Feedback 2026-06-24 (BF-12562).
 */
public class ConnectInfobaseLockDetectionTest {

    @Test
    public void isInfobaseLockMessage_matchesCannotLockPhrase() {
        assertTrue(ConnectInfobaseTool.isInfobaseLockMessage(
                "Cannot lock the infobase because it is open in Designer.")); //$NON-NLS-1$
    }

    @Test
    public void isInfobaseLockMessage_matchesSshDisconnectCode() {
        assertTrue(ConnectInfobaseTool.isInfobaseLockMessage(
                "SSH_MSG_DISCONNECT: -33554432 Cannot lock the infobase because it is open in Designer.")); //$NON-NLS-1$
    }

    @Test
    public void isInfobaseLockMessage_isCaseInsensitive() {
        assertTrue(ConnectInfobaseTool.isInfobaseLockMessage("INFOBASE IS OPEN IN DESIGNER")); //$NON-NLS-1$
    }

    @Test
    public void isInfobaseLockMessage_rejectsCredentialMessage() {
        assertFalse(ConnectInfobaseTool.isInfobaseLockMessage(
                "Authentication failure while establishing SSH session with Designer agent")); //$NON-NLS-1$
        assertFalse(ConnectInfobaseTool.isInfobaseLockMessage("waiting on a credential prompt")); //$NON-NLS-1$
        assertFalse(ConnectInfobaseTool.isInfobaseLockMessage(null));
    }

    @Test
    public void findInfobaseLockMessage_walksTheCauseChain() {
        Throwable root = new RuntimeException(
                "SSH_MSG_DISCONNECT: -33554432 Cannot lock the infobase because it is open in Designer."); //$NON-NLS-1$
        Throwable mid = new IllegalStateException("Designer agent broke connection.", root); //$NON-NLS-1$
        Throwable top = new RuntimeException("Authentication error while connecting to designer agent.", mid); //$NON-NLS-1$

        String found = ConnectInfobaseTool.findInfobaseLockMessage(top);
        assertTrue(found != null && found.toLowerCase(java.util.Locale.ROOT).contains("cannot lock the infobase")); //$NON-NLS-1$
    }

    @Test
    public void findInfobaseLockMessage_returnsNullWhenNoLockInChain() {
        Throwable root = new RuntimeException("Connection refused"); //$NON-NLS-1$
        Throwable top = new IllegalStateException("EDT service unavailable", root); //$NON-NLS-1$
        assertNull(ConnectInfobaseTool.findInfobaseLockMessage(top));
    }

    @Test
    public void findInfobaseLockMessage_survivesCauseCycle() {
        // A self-referential cause must not loop forever (guard caps the walk).
        RuntimeException a = new RuntimeException("nothing here"); //$NON-NLS-1$
        assertNull(ConnectInfobaseTool.findInfobaseLockMessage(a));
    }

    @Test
    public void enum_edtInfobaseLockedIsDistinctFromAuthRequired() {
        assertEquals("EDT_INFOBASE_LOCKED", //$NON-NLS-1$
                com.codepilot1c.core.edt.runtime.EdtToolErrorCode.EDT_INFOBASE_LOCKED.name());
        assertFalse(com.codepilot1c.core.edt.runtime.EdtToolErrorCode.EDT_INFOBASE_LOCKED
                == com.codepilot1c.core.edt.runtime.EdtToolErrorCode.EDT_AUTH_REQUIRED);
    }

    // --- Designer-agent SSH auth-failure classifier (BF-12839, 2026-07-14) -----------------------

    @Test
    public void isDesignerAuthFailureMessage_matchesJSchAuthFail() {
        assertTrue(ConnectInfobaseTool.isDesignerAuthFailureMessage(
                "com.jcraft.jsch.JSchException: Auth fail")); //$NON-NLS-1$
        assertTrue(ConnectInfobaseTool.isDesignerAuthFailureMessage(
                "Authentication failure while establishing SSH session with Designer agent")); //$NON-NLS-1$
    }

    @Test
    public void isDesignerAuthFailureMessage_rejectsLockAndPlainMessages() {
        // A lock disconnect is NOT an auth failure — the two classifiers must not overlap (in
        // production the lock check runs first anyway).
        assertFalse(ConnectInfobaseTool.isDesignerAuthFailureMessage(
                "SSH_MSG_DISCONNECT: -33554432 Cannot lock the infobase because it is open in Designer.")); //$NON-NLS-1$
        assertFalse(ConnectInfobaseTool.isDesignerAuthFailureMessage("waiting on a credential prompt")); //$NON-NLS-1$
        assertFalse(ConnectInfobaseTool.isDesignerAuthFailureMessage(null));
    }

    @Test
    public void findDesignerAuthFailureMessage_walksCauseChainToJSchRoot() {
        // Top frame is a generic bind message (no auth signature) so the classifier must walk down
        // to the JSch root to find the failure.
        Throwable root = new RuntimeException("com.jcraft.jsch.JSchException: Auth fail"); //$NON-NLS-1$
        Throwable top = new IllegalStateException("Bind failed for infobase File_am_X", root); //$NON-NLS-1$
        String found = ConnectInfobaseTool.findDesignerAuthFailureMessage(top);
        assertTrue(found != null && found.toLowerCase(java.util.Locale.ROOT).contains("auth fail")); //$NON-NLS-1$
    }

    @Test
    public void findDesignerAuthFailureMessage_matchesTopLevelDesignerAgentAuthError() {
        // The real EDT chain surfaces "Authentication error while connecting to designer agent" at
        // the top — that IS an auth failure and must classify as one (returned as-is).
        Throwable top = new RuntimeException("Authentication error while connecting to designer agent."); //$NON-NLS-1$
        assertTrue(ConnectInfobaseTool.findDesignerAuthFailureMessage(top) != null);
    }

    @Test
    public void findDesignerAuthFailureMessage_returnsNullWhenNoAuthInChain() {
        Throwable root = new RuntimeException("Connection refused"); //$NON-NLS-1$
        Throwable top = new IllegalStateException("EDT service unavailable", root); //$NON-NLS-1$
        assertNull(ConnectInfobaseTool.findDesignerAuthFailureMessage(top));
    }

    @Test
    public void enum_designerAgentAuthFailedIsDistinct() {
        assertEquals("EDT_DESIGNER_AGENT_AUTH_FAILED", //$NON-NLS-1$
                com.codepilot1c.core.edt.runtime.EdtToolErrorCode.EDT_DESIGNER_AGENT_AUTH_FAILED.name());
        assertFalse(com.codepilot1c.core.edt.runtime.EdtToolErrorCode.EDT_DESIGNER_AGENT_AUTH_FAILED
                == com.codepilot1c.core.edt.runtime.EdtToolErrorCode.EDT_AUTH_REQUIRED);
    }
}
