package com.codepilot1c.core.edt.publication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Test;

/**
 * Plain-JUnit tests for {@link EdtWebPublicationService}'s pure helpers:
 * <ul>
 *   <li>{@code vrdLocation} — the Gap A fix that keeps the generated Apache {@code Alias} target's
 *       trailing separator consistent with the slash-terminated URL alias (EDT stores the re-pointed
 *       publication name with a trailing slash);</li>
 *   <li>{@code wsapModule}/{@code wsapModuleFileName} — resolving the wsap module FILE inside the
 *       platform {@code bin} directory, which holds every flavour at once (feedback 2026-08-04:
 *       handing the delegate the directory wrote a {@code LoadModule} that killed httpd on start);</li>
 *   <li>{@code stripSlashes} — the alias-matching form used to find a publication in the conf.</li>
 * </ul>
 */
public class EdtWebPublicationServiceHelpersTest {

    private static final String APACHE_2_0 = "com._1c.g5.v8.dt.platform.services.core.webServerType.Apache.2.0"; //$NON-NLS-1$
    private static final String APACHE_2_2 = "com._1c.g5.v8.dt.platform.services.core.webServerType.Apache.2.2"; //$NON-NLS-1$
    private static final String APACHE_2_4 = "com._1c.g5.v8.dt.platform.services.core.webServerType.Apache.2.4"; //$NON-NLS-1$
    private static final String IIS_10_0 = "com._1c.g5.v8.dt.platform.services.core.webServerType.IIS.10.0"; //$NON-NLS-1$
    private static final String JETTY_9 = "com._1c.g5.v8.dt.platform.services.core.webServerType.Jetty.9"; //$NON-NLS-1$

    private Path tempDir;

    @After
    public void deleteTempDir() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(tempDir)) {
            for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** A stand-in for the platform's {@code bin}: every wsap flavour side by side, as 1C ships it. */
    private Path platformBinWith(String... moduleFileNames) throws IOException {
        tempDir = Files.createTempDirectory("wsap-bin"); //$NON-NLS-1$
        for (String moduleFileName : moduleFileNames) {
            Files.createFile(tempDir.resolve(moduleFileName));
        }
        return tempDir;
    }

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

    // -- wsap module resolution ---------------------------------------------------------------

    @Test
    public void apacheVersionPicksItsOwnModuleOutOfTheSharedBinDirectory() throws IOException {
        // The bug: this directory was handed to the delegate as-is, so Apache got a LoadModule
        // pointing at a folder and exited with code 1 before opening its error log.
        Path bin = platformBinWith("wsapch2.dll", "wsap22.dll", "wsap24.dll"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(bin.resolve("wsap24.dll"), EdtWebPublicationService.wsapModule(bin, APACHE_2_4)); //$NON-NLS-1$
        assertEquals(bin.resolve("wsap22.dll"), EdtWebPublicationService.wsapModule(bin, APACHE_2_2)); //$NON-NLS-1$
        assertEquals(bin.resolve("wsapch2.dll"), EdtWebPublicationService.wsapModule(bin, APACHE_2_0)); //$NON-NLS-1$
    }

    @Test
    public void iisPicksTheIsapiModule() throws IOException {
        Path bin = platformBinWith("wsap24.dll", "wsisapi.dll"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(bin.resolve("wsisapi.dll"), EdtWebPublicationService.wsapModule(bin, IIS_10_0)); //$NON-NLS-1$
    }

    @Test
    public void aLocationThatIsAlreadyTheModuleFileIsKept() throws IOException {
        Path bin = platformBinWith("wsap24.dll"); //$NON-NLS-1$
        Path module = bin.resolve("wsap24.dll"); //$NON-NLS-1$
        assertEquals(module, EdtWebPublicationService.wsapModule(module, APACHE_2_4));
    }

    @Test
    public void sharedObjectIsAcceptedWhenTheDllIsAbsent() throws IOException {
        Path bin = platformBinWith("wsap24.so"); //$NON-NLS-1$
        assertEquals(bin.resolve("wsap24.so"), EdtWebPublicationService.wsapModule(bin, APACHE_2_4)); //$NON-NLS-1$
    }

    @Test
    public void missingModuleResolvesToNullRatherThanTheDirectory() throws IOException {
        // Web-server extension component not installed for this platform: better a structured
        // WEB_EXTENSION_NOT_FOUND from the caller than a conf that stops Apache from starting.
        Path bin = platformBinWith("1cv8c.exe"); //$NON-NLS-1$
        assertNull(EdtWebPublicationService.wsapModule(bin, APACHE_2_4));
    }

    @Test
    public void webServerTypeWithoutAWsapModuleResolvesToNull() throws IOException {
        Path bin = platformBinWith("wsap24.dll"); //$NON-NLS-1$
        assertNull(EdtWebPublicationService.wsapModule(bin, JETTY_9));
        assertNull(EdtWebPublicationService.wsapModule(bin, null));
        assertNull(EdtWebPublicationService.wsapModule(null, APACHE_2_4));
    }

    @Test
    public void moduleFileNamesMatchEdtsOwnTable() {
        // Mirrors IRuntimeComponentFileNames in com._1c.g5.v8.dt.platform.services.core.win32.
        assertEquals("wsapch2.dll", EdtWebPublicationService.wsapModuleFileName(APACHE_2_0)); //$NON-NLS-1$
        assertEquals("wsap22.dll", EdtWebPublicationService.wsapModuleFileName(APACHE_2_2)); //$NON-NLS-1$
        assertEquals("wsap24.dll", EdtWebPublicationService.wsapModuleFileName(APACHE_2_4)); //$NON-NLS-1$
        assertEquals("wsisapi.dll", EdtWebPublicationService.wsapModuleFileName(IIS_10_0)); //$NON-NLS-1$
        assertNull(EdtWebPublicationService.wsapModuleFileName(JETTY_9));
        assertNull(EdtWebPublicationService.wsapModuleFileName(null));
    }

    // -- alias matching -----------------------------------------------------------------------

    @Test
    public void aliasMatchingIgnoresLeadingAndTrailingSlashes() {
        // The conf carries Alias "/agent-current", EDT's model may store "agent-current/", the tool's
        // schema documents the bare alias — all three must resolve to the same publication.
        assertEquals("agent-current", EdtWebPublicationService.stripSlashes("/agent-current")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("agent-current", EdtWebPublicationService.stripSlashes("agent-current/")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("agent-current", EdtWebPublicationService.stripSlashes("/agent-current/")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("agent-current", EdtWebPublicationService.stripSlashes("  \\agent-current\\  ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("agent-current", EdtWebPublicationService.stripSlashes("agent-current")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", EdtWebPublicationService.stripSlashes(null)); //$NON-NLS-1$
    }
}
