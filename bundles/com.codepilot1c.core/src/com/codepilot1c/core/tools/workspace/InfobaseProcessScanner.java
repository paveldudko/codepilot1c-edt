package com.codepilot1c.core.tools.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
 * <p>The pure classification / path-matching helpers are package-private and unit-tested;
 * the {@link ProcessHandle}-based scan and kill are environment dependent, best-effort and
 * never throw.</p>
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
     */
    static String fileIbPath(String connectionString) {
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
        List<LockingProcess> out = new ArrayList<>();
        try {
            List<ProcessHandle> all = ProcessHandle.allProcesses().collect(Collectors.toList());
            for (ProcessHandle ph : all) {
                ProcessHandle.Info info = ph.info();
                Optional<String> command = info.command();
                if (command.isEmpty()) {
                    continue;
                }
                String commandLine = info.commandLine().orElse(command.get());
                String cmdLower = commandLine.toLowerCase(Locale.ROOT);
                String exeLower = command.get().toLowerCase(Locale.ROOT);
                if (!isOneCOrWebProcess(exeLower) && !isOneCOrWebProcess(cmdLower)) {
                    continue;
                }
                LockKind kind = classify(cmdLower);
                boolean target = normIb != null && matchesIb(cmdLower, normIb);
                out.add(new LockingProcess(ph.pid(), command.get(), commandLine, kind, target));
            }
        } catch (RuntimeException e) {
            // process scan is best-effort; don't let it mask the original error
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
}
