package com.codepilot1c.core.tools.workspace;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Best-effort OS process scanner that finds and classifies the 1C / web-server
 * processes capable of holding an infobase lock, and can terminate the phantom
 * {@code /AgentMode} Designer agents that EDT auto-respawns on a primary infobase.
 *
 * <p>Feedback {@code 2026-06-10-bf11104-update-infobase-ib-locked-phantom-respawn.md}
 * and {@code 2026-06-09-phase6-infra-tooling.md}: every bind makes EDT spawn a phantom
 * {@code 1cv8 DESIGNER /AgentMode} on the primary infobase and respawn it within seconds,
 * so a headless {@code update_infobase} never wins the exclusive lock; and the raw
 * {@code locking_processes} listing was noisy because it included every 1C/Apache process
 * rather than only the holders of the target infobase.</p>
 *
 * <p>It also terminates the thin clients ({@code 1cv8c}) that {@code yaxunit_run} leaks on a test
 * infobase — see {@link #killLeakedTestClients(String)}; both kill paths require an infobase match,
 * so a neighbouring stand's process is never a candidate.</p>
 *
 * <p>The pure classification / path-matching helpers are package-private (except
 * {@link #fileIbPath(String)}, which callers outside this package need as the binding key) and
 * unit-tested; the {@link ProcessHandle}-based scan and kill are environment dependent, best-effort
 * and never throw.</p>
 */
public final class InfobaseProcessScanner {

    private InfobaseProcessScanner() {
    }

    /** What kind of lock-holder a process is, inferred from its command line. */
    public enum LockKind {
        /** {@code 1cv8 DESIGNER … /AgentMode} — the phantom EDT respawns on the primary IB. */
        DESIGNER_AGENT,
        /** Interactive {@code 1cv8 DESIGNER} (a real Configurator window — never auto-killed). */
        DESIGNER,
        /** {@code 1cv8 ENTERPRISE} thick client or {@code 1cv8c} thin client. */
        CLIENT,
        /** Apache {@code httpd}/{@code wsap} web-publication worker. */
        WEBSERVER,
        /** Some other {@code 1cv8*} process. */
        OTHER_1C
    }

    /** A single suspected lock-holding process. */
    public record LockingProcess(long pid, String command, String commandLine,
            LockKind kind, boolean targetIb) {
    }

    // --- pure, unit-testable helpers --------------------------------------------------------

    private static final Pattern FILE_TOKEN = Pattern.compile(
            "File\\s*=\\s*\"([^\"]*)\"|File\\s*=\\s*'([^']*)'", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE);

    /**
     * Extracts the file-infobase path from an EDT connection string
     * ({@code File="C:\db\demo";}). Returns {@code null} for server/standalone
     * connection strings or when no {@code File} token is present.
     *
     * <p>Public because tools outside this package (e.g. {@code yaxunit_run}) need the same
     * infobase-binding key before they may terminate anything.</p>
     */
    public static String fileIbPath(String connectionString) {
        if (connectionString == null) {
            return null;
        }
        Matcher m = FILE_TOKEN.matcher(connectionString);
        if (m.find()) {
            String value = m.group(1) != null ? m.group(1) : m.group(2);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /** Lowercases, unifies separators and strips a trailing separator for contains-matching. */
    static String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        String p = path.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        while (p.endsWith("/")) { //$NON-NLS-1$
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? null : p;
    }

    /** Classifies a process by its (already lowercased) command line. */
    static LockKind classify(String commandLineLower) {
        if (commandLineLower == null) {
            return LockKind.OTHER_1C;
        }
        String c = commandLineLower;
        if (c.contains("httpd") || c.contains("wsap") || c.contains("apache")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return LockKind.WEBSERVER;
        }
        boolean agentMode = c.contains("agentmode"); //$NON-NLS-1$
        if (c.contains("designer") || c.contains("конфигуратор")) { //$NON-NLS-1$ //$NON-NLS-2$
            return agentMode ? LockKind.DESIGNER_AGENT : LockKind.DESIGNER;
        }
        if (c.contains("1cv8c") || c.contains("enterprise") || c.contains("предприятие")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return LockKind.CLIENT;
        }
        return LockKind.OTHER_1C;
    }

    /**
     * True when the (lowercased) command line references the given normalized infobase path.
     * The match must end on a boundary (end-of-string, quote, whitespace, {@code ;} or a path
     * separator denoting a sub-path) so that an infobase named {@code ib} does not spuriously
     * match a different infobase {@code ib2} sharing the same parent folder.
     */
    static boolean matchesIb(String commandLineLower, String normalizedIbPath) {
        if (commandLineLower == null || normalizedIbPath == null) {
            return false;
        }
        String hay = commandLineLower.replace('\\', '/');
        int idx = hay.indexOf(normalizedIbPath);
        while (idx >= 0) {
            int after = idx + normalizedIbPath.length();
            char next = after < hay.length() ? hay.charAt(after) : '\0';
            if (next == '\0' || next == '"' || next == '\'' || next == ';' //$NON-NLS-1$
                    || next == '/' || Character.isWhitespace(next)) {
                return true;
            }
            idx = hay.indexOf(normalizedIbPath, idx + 1);
        }
        return false;
    }

    /** True when the (lowercased) command line or exe path is the thin client ({@code 1cv8c}). */
    static boolean isThinClient(String commandLower) {
        return commandLower != null && commandLower.contains("1cv8c"); //$NON-NLS-1$
    }

    /**
     * True when the (lowercased) command line is one of OUR YAxUnit runs: the thin client is started
     * with the {@code RunUnitTests=<config>} startup parameter, which an interactive session never
     * carries. This is what separates "a client this plugin leaked" from "the owner's open session".
     */
    static boolean isUnitTestRunner(String commandLineLower) {
        return commandLineLower != null && commandLineLower.contains("rununittests"); //$NON-NLS-1$
    }

    /**
     * True when a thin client may be auto-terminated: it runs YAxUnit AND references the target
     * infobase. Both conditions are mandatory — on a multi-stand machine a bare {@code 1cv8c} match
     * is somebody else's client, and killing it on a guess is the worse failure.
     */
    static boolean isLeakedTestClient(String commandLineLower, String normalizedIbPath) {
        return isThinClient(commandLineLower) && isUnitTestRunner(commandLineLower)
                && matchesIb(commandLineLower, normalizedIbPath);
    }

    /**
     * True when the process's command line is actually readable, i.e. carries arguments beyond the
     * executable path. On Windows both {@code ProcessHandle.info().commandLine()} and the WMI
     * overlay can come back empty/exe-only, and then no infobase attribution is possible at all —
     * callers must report that loudly instead of guessing.
     */
    static boolean hasReadableCommandLine(String command, String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return false;
        }
        String cmd = command == null ? "" : command.trim(); //$NON-NLS-1$
        return cmd.isEmpty() || !commandLine.trim().equalsIgnoreCase(cmd);
    }

    // --- environment-dependent scan (best-effort) -------------------------------------------

    private static boolean isOneCOrWebProcess(String commandLower) {
        return commandLower.contains("1cv8") //$NON-NLS-1$
                || commandLower.contains("httpd") //$NON-NLS-1$
                || commandLower.contains("wsap") //$NON-NLS-1$
                || commandLower.contains("apache"); //$NON-NLS-1$
    }

    /**
     * Scans OS processes for 1C/Apache lock-holders, classifying each and flagging whether its
     * command line references {@code ibPath}. When {@code ibPath} is {@code null} (server infobase
     * or path unknown) the {@code targetIb} flag is always {@code false}. Best-effort; never throws.
     */
    public static List<LockingProcess> scan(String ibPath) {
        String normIb = normalizePath(ibPath);
        // On Windows, ProcessHandle.info().commandLine() is frequently EMPTY (the JVM does not read
        // the target PEB), so /AgentMode and the IB path are invisible and a phantom is misclassified
        // as OTHER_1C and never killed. WMI exposes the command line for same-user processes, so we
        // overlay it. Live finding 2026-06-11 (sandbox): kill_agent_mode missed an EDT-child phantom
        // because commandLine() came back empty.
        Map<Long, String> wmiCmd = windowsCommandLines();
        List<LockingProcess> out = new ArrayList<>();
        try {
            List<ProcessHandle> all = ProcessHandle.allProcesses().collect(Collectors.toList());
            for (ProcessHandle ph : all) {
                ProcessHandle.Info info = ph.info();
                String exe = info.command().orElse(null);
                String wmi = wmiCmd.get(ph.pid());
                String commandLine = wmi != null ? wmi : info.commandLine().orElse(exe);
                if (commandLine == null && exe == null) {
                    continue; // nothing readable about this process
                }
                String cmdLower = commandLine == null ? "" : commandLine.toLowerCase(Locale.ROOT); //$NON-NLS-1$
                String exeLower = exe == null ? "" : exe.toLowerCase(Locale.ROOT); //$NON-NLS-1$
                if (!isOneCOrWebProcess(exeLower) && !isOneCOrWebProcess(cmdLower)) {
                    continue;
                }
                LockKind kind = classify(cmdLower.isEmpty() ? exeLower : cmdLower);
                boolean target = normIb != null && matchesIb(cmdLower, normIb);
                out.add(new LockingProcess(ph.pid(), exe, commandLine, kind, target));
            }
        } catch (RuntimeException e) {
            // process scan is best-effort; don't let it mask the original error
        }
        return out;
    }

    /** {@code true} on Windows. */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Returns a {@code pid -> command line} map for 1C/Apache processes via WMI (Windows only).
     * Empty on non-Windows or on any failure — callers fall back to {@link ProcessHandle}. Needed
     * because {@code ProcessHandle.info().commandLine()} is unreliable on Windows.
     */
    private static Map<Long, String> windowsCommandLines() {
        Map<Long, String> out = new HashMap<>();
        if (!isWindows()) {
            return out;
        }
        try {
            // Pass the script via -EncodedCommand (base64 UTF-16LE): ProcessBuilder mangles embedded
            // double-quotes in a -Command argument on Windows, which silently broke the query and
            // returned nothing (live finding 2026-06-11 — kill_agent_mode kept missing the phantom).
            String psCmd = "Get-CimInstance Win32_Process | Where-Object { $_.Name -match '1cv8|httpd|wsap|apache' }" //$NON-NLS-1$
                    + " | ForEach-Object { ($_.ProcessId.ToString() + '|||' + $_.CommandLine) }"; //$NON-NLS-1$
            String encoded = Base64.getEncoder().encodeToString(psCmd.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "-EncodedCommand", encoded); //$NON-NLS-1$
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int sep = line.indexOf("|||"); //$NON-NLS-1$
                    if (sep > 0) {
                        try {
                            long pid = Long.parseLong(line.substring(0, sep).trim());
                            String cmd = line.substring(sep + 3);
                            if (!cmd.isBlank()) {
                                out.put(Long.valueOf(pid), cmd);
                            }
                        } catch (NumberFormatException ignore) {
                            // skip malformed line
                        }
                    }
                }
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // best-effort; fall back to ProcessHandle command lines
        }
        return out;
    }

    /** {@code true} when at least one running web-server (Apache wsap/httpd) process was found. */
    public static boolean anyWebserverRunning() {
        for (LockingProcess p : scan(null)) {
            if (p.kind() == LockKind.WEBSERVER) {
                return true;
            }
        }
        return false;
    }

    /**
     * Terminates phantom {@code /AgentMode} Designer processes bound to {@code ibPath}. Only
     * {@link LockKind#DESIGNER_AGENT} processes whose command line references the infobase are
     * killed — interactive Designers and clients are left untouched. Returns the PIDs asked to
     * terminate. When {@code ibPath} is {@code null} nothing is killed (a phantom cannot be
     * safely targeted without a path). Best-effort; never throws.
     */
    public static List<Long> killPhantomDesigners(String ibPath) {
        String normIb = normalizePath(ibPath);
        if (normIb == null) {
            return List.of();
        }
        List<Long> killed = new ArrayList<>();
        for (LockingProcess p : scan(ibPath)) {
            if (p.kind() == LockKind.DESIGNER_AGENT && p.targetIb()) {
                Optional<ProcessHandle> handle = ProcessHandle.of(p.pid());
                if (handle.isPresent() && handle.get().destroy()) {
                    killed.add(p.pid());
                }
            }
        }
        return killed;
    }

    /**
     * Outcome of {@link #killLeakedTestClients(String)}: the PIDs terminated, the thin clients left
     * running, and — a subset of {@code spared} — those whose command line could not be read at all,
     * so no infobase attribution was possible for them.
     */
    public record TestClientCleanup(List<Long> killed, List<Long> spared, List<Long> unreadable) {
    }

    /**
     * Terminates thin clients ({@code 1cv8c}) that a previous YAxUnit run leaked on {@code ibPath}.
     *
     * <p>A leaked client keeps the infobase's named pipe, and the next run then burns its whole
     * timeout with no report. Killing the process tree does not help: the real {@code 1cv8c} is not a
     * descendant of the process the plugin spawns (the launcher reparents it —
     * {@code descendants=0} on every heartbeat), so the orphan has to be found by command line and
     * killed by PID.</p>
     *
     * <p>Safety: only clients matching {@link #isLeakedTestClient} are killed — the command line must
     * both reference this infobase and carry our {@code RunUnitTests=} parameter. Everything else
     * (another stand's client, an interactive session, a client whose command line is unreadable
     * because WMI is unavailable) is returned in {@code spared}/{@code unreadable} for the caller to
     * report, never terminated. Best-effort; never throws.</p>
     */
    public static TestClientCleanup killLeakedTestClients(String ibPath) {
        String normIb = normalizePath(ibPath);
        List<Long> killed = new ArrayList<>();
        List<Long> spared = new ArrayList<>();
        List<Long> unreadable = new ArrayList<>();
        for (LockingProcess p : scan(ibPath)) {
            String cmdLower = p.commandLine() == null ? null : p.commandLine().toLowerCase(Locale.ROOT);
            String exeLower = p.command() == null ? null : p.command().toLowerCase(Locale.ROOT);
            if (!isThinClient(cmdLower) && !isThinClient(exeLower)) {
                continue; // designers, thick clients and web servers are not this probe's business
            }
            if (normIb != null && isLeakedTestClient(cmdLower, normIb)) {
                Optional<ProcessHandle> handle = ProcessHandle.of(p.pid());
                if (handle.isPresent() && handle.get().destroy()) {
                    killed.add(p.pid());
                    continue;
                }
            }
            spared.add(p.pid());
            if (!hasReadableCommandLine(p.command(), p.commandLine())) {
                unreadable.add(p.pid());
            }
        }
        return new TestClientCleanup(List.copyOf(killed), List.copyOf(spared), List.copyOf(unreadable));
    }
}
