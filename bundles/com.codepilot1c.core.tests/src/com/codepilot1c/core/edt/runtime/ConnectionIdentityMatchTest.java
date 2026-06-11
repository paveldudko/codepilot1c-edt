package com.codepilot1c.core.edt.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link EdtInfobaseConnectService#connectionIdentitiesMatch} — the normalized
 * connection-string comparison that fixed the NAME_COLLISION false-positive on re-bind (a trailing
 * path separator / slash direction / drive-letter case on the {@code File="..."} token must NOT make
 * the same infobase read as a different one). Live finding 2026-06-11.
 */
public class ConnectionIdentityMatchTest {

    @Test
    public void fileIb_trailingBackslashIsIgnored() {
        // The exact case that wrongly threw NAME_COLLISION under force=true.
        assertTrue(EdtInfobaseConnectService.connectionIdentitiesMatch(
                "File=\"c:\\1C\\Dudko\\db\\File_am_sandbox\";",
                "File=\"c:\\1C\\Dudko\\db\\File_am_sandbox\\\";"));
    }

    @Test
    public void fileIb_slashDirectionAndCaseIgnored() {
        assertTrue(EdtInfobaseConnectService.connectionIdentitiesMatch(
                "File=\"C:/1C/Dudko/DB/IB\";",
                "File=\"c:\\1c\\dudko\\db\\ib\\\";"));
    }

    @Test
    public void fileIb_differentPathsDoNotMatch() {
        assertFalse(EdtInfobaseConnectService.connectionIdentitiesMatch(
                "File=\"c:\\db\\ib\";",
                "File=\"c:\\db\\ib2\";"));
    }

    @Test
    public void serverIb_spacingAndCaseFolded() {
        assertTrue(EdtInfobaseConnectService.connectionIdentitiesMatch(
                "Srvr=\"host:1541\";Ref=\"demo\";",
                "srvr=\"host:1541\"; ref=\"demo\";"));
    }

    @Test
    public void serverVsFile_doNotMatch() {
        assertFalse(EdtInfobaseConnectService.connectionIdentitiesMatch(
                "Srvr=\"host\";Ref=\"demo\";",
                "File=\"c:\\db\\demo\";"));
    }

    @Test
    public void nullsDoNotMatch() {
        assertFalse(EdtInfobaseConnectService.connectionIdentitiesMatch(null, "File=\"c:\\db\";"));
        assertFalse(EdtInfobaseConnectService.connectionIdentitiesMatch("File=\"c:\\db\";", null));
    }

    @Test
    public void canonicalConnection_fileTokenNormalized() {
        org.junit.Assert.assertEquals("file:c:/db/ib",
                EdtInfobaseConnectService.canonicalConnection("File=\"C:\\DB\\IB\\\";"));
    }
}
