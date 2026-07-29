package com.codepilot1c.core.tools.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.publication.EdtWebPublicationService.HttpServiceSpec;
import com.codepilot1c.core.edt.publication.EdtWebPublicationService.PublicationExtras;
import com.codepilot1c.core.edt.runtime.EdtToolException;

/**
 * Unit tests for {@link WebPublicationTool#parsePublicationExtras} — the pure param→model-options mapping
 * for the BF-12936 option-1 build (owner GO 2026-07-16): {@code web_publication publish} carrying custom
 * HTTP services / OData / analytics / pool into the generated vrd. The EMF model population is exercised
 * live (battle test on stack-2); this pins the parsing contract headless.
 */
public class WebPublicationExtrasParsingTest {

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> service(String name, String rootUrl, Object enable) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); //$NON-NLS-1$
        if (rootUrl != null) {
            m.put("root_url", rootUrl); //$NON-NLS-1$
        }
        if (enable != null) {
            m.put("enable", enable); //$NON-NLS-1$
        }
        return m;
    }

    @Test
    public void noExtrasParamsReturnsNull() {
        assertNull(WebPublicationTool.parsePublicationExtras(params()));
        assertNull("unrelated params only → no extras", //$NON-NLS-1$
                WebPublicationTool.parsePublicationExtras(params("server", "apache-local", "name", "demo"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void reportedBslAnalyzerServiceParsesWithRootUrl() {
        // The exact BF-12936 migration case: preserve rootUrl=bsl-analyzer.
        PublicationExtras extras = WebPublicationTool.parsePublicationExtras(params(
                "publish_http_by_default", Boolean.TRUE, //$NON-NLS-1$
                "http_services", List.of(service("BSLAnalyzerService", "bsl-analyzer", Boolean.TRUE)))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(extras.isEmpty());
        assertEquals(Boolean.TRUE, extras.publishHttpByDefault());
        assertEquals(1, extras.httpServices().size());
        HttpServiceSpec svc = extras.httpServices().get(0);
        assertEquals("BSLAnalyzerService", svc.name()); //$NON-NLS-1$
        assertEquals("bsl-analyzer", svc.rootUrl()); //$NON-NLS-1$
        assertTrue(svc.enable());
    }

    @Test
    public void httpServiceEnableDefaultsTrueWhenAbsent() {
        PublicationExtras extras = WebPublicationTool.parsePublicationExtras(params(
                "http_services", List.of(service("Svc", "r", null)))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("enable omitted → true", extras.httpServices().get(0).enable()); //$NON-NLS-1$
    }

    @Test
    public void httpServiceExplicitEnableFalseHonored() {
        PublicationExtras extras = WebPublicationTool.parsePublicationExtras(params(
                "http_services", List.of(service("Svc", null, Boolean.FALSE)))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(extras.httpServices().get(0).enable());
        assertNull("no root_url → null", extras.httpServices().get(0).rootUrl()); //$NON-NLS-1$
    }

    @Test
    public void httpServiceMissingNameRejected() {
        try {
            WebPublicationTool.parsePublicationExtras(params(
                    "http_services", List.of(service(null, "r", Boolean.TRUE)))); //$NON-NLS-1$ //$NON-NLS-2$
            fail("an http_services entry without a name must be rejected"); //$NON-NLS-1$
        } catch (EdtToolException expected) {
            // expected — non-empty name required
        }
    }

    @Test
    public void httpServiceNonObjectEntryRejected() {
        try {
            WebPublicationTool.parsePublicationExtras(params("http_services", List.of("BSLAnalyzerService"))); //$NON-NLS-1$ //$NON-NLS-2$
            fail("a scalar http_services entry must be rejected"); //$NON-NLS-1$
        } catch (EdtToolException expected) {
            // expected
        }
    }

    @Test
    public void booleanFlagsAcceptBooleanAndStringLiterals() {
        PublicationExtras extras = WebPublicationTool.parsePublicationExtras(params(
                "enable_standard_odata", Boolean.FALSE, //$NON-NLS-1$
                "enable_system_analytics", "true", //$NON-NLS-1$ //$NON-NLS-2$
                "publish_web_by_default", "false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.FALSE, extras.enableStandardOData());
        assertEquals(Boolean.TRUE, extras.enableSystemAnalytics());
        assertEquals(Boolean.FALSE, extras.publishWebByDefault());
    }

    @Test
    public void poolParsedFromNumberAndString() {
        // Gson decodes JSON numbers as Double — asIntOrNull must handle Number, and numeric strings.
        PublicationExtras extras = WebPublicationTool.parsePublicationExtras(params(
                "pool", params("size", Double.valueOf(500.0d), "max_age", "1200"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(Integer.valueOf(500), extras.pool().size());
        assertEquals(Integer.valueOf(1200), extras.pool().maxAge());
    }

    @Test
    public void emptyPoolObjectYieldsNoPool() {
        // {pool:{}} carries nothing → pool null → and with no other extras, the whole thing is null.
        assertNull(WebPublicationTool.parsePublicationExtras(params("pool", params()))); //$NON-NLS-1$
    }

    // -- probe credentials borrowed from infobase_connection (owner-approved 2026-07-29) -----------

    @Test
    public void probeBorrowsTheConnectionStringLogin() {
        String[] creds = WebPublicationTool.connectionStringCredentials(
                "File=\"C:\\db\\demo\";Usr=\"Admin\";Pwd=\"s3cret\";"); //$NON-NLS-1$
        assertEquals("Admin", creds[0]); //$NON-NLS-1$
        assertEquals("s3cret", creds[1]); //$NON-NLS-1$
    }

    @Test
    public void borrowingIsCaseInsensitiveAndUnquotedFormWorks() {
        String[] creds = WebPublicationTool.connectionStringCredentials(
                "Srvr=host;Ref=base;usr=svc_probe;pwd=p1;"); //$NON-NLS-1$
        assertEquals("svc_probe", creds[0]); //$NON-NLS-1$
        assertEquals("p1", creds[1]); //$NON-NLS-1$
    }

    /**
     * A login with no password is not a usable Basic credential. Probing with half of one would answer
     * 401 and read as a broken publication — the very misdiagnosis this borrowing exists to prevent.
     */
    @Test
    public void aLoginWithoutAPasswordIsNotBorrowed() {
        assertNull(WebPublicationTool.connectionStringCredentials(
                "File=\"C:\\db\\demo\";Usr=\"Admin\";")); //$NON-NLS-1$
    }

    @Test
    public void anEmptyPasswordIsStillACredential() {
        // Pwd="" is a real 1C configuration (a user with a blank password), unlike an absent Pwd.
        String[] creds = WebPublicationTool.connectionStringCredentials(
                "File=\"C:\\db\\demo\";Usr=\"Admin\";Pwd=\"\";"); //$NON-NLS-1$
        assertEquals("Admin", creds[0]); //$NON-NLS-1$
        assertEquals("", creds[1]); //$NON-NLS-1$
    }

    @Test
    public void aConnectionStringWithoutCredentialsBorrowsNothing() {
        assertNull(WebPublicationTool.connectionStringCredentials("File=\"C:\\db\\demo\";")); //$NON-NLS-1$
        assertNull(WebPublicationTool.connectionStringCredentials("")); //$NON-NLS-1$
        assertNull(WebPublicationTool.connectionStringCredentials(null));
    }

    /**
     * The token match must be anchored on a separator, or {@code UsrExtra=} would be read as {@code Usr=}
     * and the probe would authenticate as whatever that unrelated value happens to be.
     */
    @Test
    public void aLongerTokenIsNotMistakenForUsr() {
        assertNull(WebPublicationTool.connectionStringCredentials(
                "File=\"C:\\db\\demo\";UsrExtra=\"nope\";Pwd=\"x\";")); //$NON-NLS-1$
    }
}
