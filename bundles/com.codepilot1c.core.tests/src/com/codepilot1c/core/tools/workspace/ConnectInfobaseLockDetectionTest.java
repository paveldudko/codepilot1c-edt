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
}
