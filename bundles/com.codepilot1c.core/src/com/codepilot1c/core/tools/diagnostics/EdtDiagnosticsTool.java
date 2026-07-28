package com.codepilot1c.core.tools.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.workspace.EdtLaunchAppTool;
import com.codepilot1c.core.tools.workspace.EdtUpdateInfobaseTool;

/**
 * Composite diagnostics/smoke tool that dispatches to domain-specific
 * smoke and recovery tools.
 *
 * <p>Commands:</p>
 * <ul>
 *   <li>{@code metadata_smoke} — smoke regression for metadata creation</li>
 *   <li>{@code trace_export} — trace EDT export pipeline</li>
 *   <li>{@code analyze_error} — analyze structured tool errors</li>
 *   <li>{@code update_infobase} — update EDT project infobase</li>
 *   <li>{@code launch_app} — launch EDT project application</li>
 * </ul>
 *
 * <p>Note: edt_extension_smoke and edt_external_smoke remain as separate
 * tools because they are gated independently by ToolContextGate.</p>
 */
@ToolMeta(name = "edt_diagnostics", category = "diagnostics",
        surfaceCategory = "smoke_runtime_recovery",
        mutating = true, tags = {"workspace", "edt"})
public class EdtDiagnosticsTool extends AbstractTool {

    private static final Set<String> ALL_COMMANDS = Set.of(
            "metadata_smoke", "trace_export", "analyze_error", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "update_infobase", "launch_app"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "Diagnostics command. metadata_smoke = self-test of the EDT metadata API (creates and deletes temporary objects in the project); it does NOT verify the target infobase/database — use get_infobase_sync_state / update_infobase for that. trace_export for export issues, analyze_error for a concrete error payload, update_infobase or launch_app for runtime workflow.",
                  "enum": ["metadata_smoke", "trace_export", "analyze_error", "update_infobase", "launch_app"]
                },
                "project": {
                  "type": "string",
                  "description": "EDT project name. REQUIRED for command=metadata_smoke or trace_export. Also accepted as an alias for project_name in update_infobase / launch_app."
                },
                "project_name": {
                  "type": "string",
                  "description": "EDT project name. REQUIRED for command=update_infobase or launch_app. Also accepted as an alias for project in metadata_smoke / trace_export."
                },
                "tool_result": {
                  "type": "object",
                  "description": "Structured tool result/error payload. REQUIRED for command=analyze_error."
                },
                "mode": {
                  "type": "string",
                  "enum": ["thin", "thick", "designer"],
                  "description": "Только для launch_app: клиент для запуска — thin (1cv8c), thick (1cv8, по умолчанию), designer (Конфигуратор)."
                },
                "runtime_version": {
                  "type": "string",
                  "description": "Для launch_app и update_infobase: версия платформы 1С — линия ('8.3.27', новейший установленный билд линии) или точный билд ('8.3.27.2074'). Без неё auto-резолв берёт НОВЕЙШУЮ установленную платформу, включая пре-релизные билды — проверяйте runtime_used в dry_run. Для update_infobase значение пинится persistent в настройках EDT (project+infobase)."
                },
                "user": {
                  "type": "string",
                  "description": "Только для launch_app: логин сессии ИБ (override настроек EDT; требует password)."
                },
                "password": {
                  "type": "string",
                  "description": "Только для launch_app: пароль к user (не возвращается в результате)."
                },
                "dry_run": {
                  "type": "boolean",
                  "description": "Только для launch_app: собрать команду без запуска процесса (вернуть status=dry_run, без spawn). ДОЛЖЕН быть объявлен здесь — MCP-клиенты отбрасывают необъявленные параметры, и без этого dry_run молча игнорировался → реальный запуск."
                },
                "wait_for_exit": {
                  "type": "boolean",
                  "description": "Только для launch_app: дождаться выхода процесса (в паре с timeout_s)."
                },
                "timeout_s": {
                  "type": "integer",
                  "description": "Только для launch_app: таймаут ожидания в секундах при wait_for_exit."
                },
                "additional_parameters": {
                  "type": "string",
                  "description": "Только для launch_app: дополнительные параметры командной строки 1С-клиента."
                },
                "keep_connected": {
                  "type": "boolean",
                  "description": "Только для update_infobase: не отключаться после обновления (по умолчанию true)."
                },
                "async": {
                  "type": "boolean",
                  "description": "Только для update_infobase: запустить обновление асинхронно."
                },
                "kill_agent_mode": {
                  "type": "boolean",
                  "description": "Только для update_infobase, узкий случай: убить phantom-Designer'ы (1cv8 DESIGNER /AgentMode) этой ИБ. NB: чаще эксклюзив держит веб-сервер (Apache wsap), а не phantom — тогда не поможет, останавливайте Apache (allow_webserver_running). Алиас auto_kill_phantoms. Объявлен здесь для pass-through через диспетчер."
                },
                "allow_webserver_running": {
                  "type": "boolean",
                  "description": "Только для update_infobase: по умолчанию апдейт файловой ИБ отказывается работать при запущенном веб-сервере (fail-fast вместо зависания на удержанной ИБ). true — попробовать всё равно (динамический BSL-апдейт / другая ИБ в публикации). Объявлен здесь для pass-through через диспетчер."
                },
                "skip_if_current": {
                  "type": "boolean",
                  "description": "Только для update_infobase: пропустить обновление, если ИБ уже равна конфигурации проекта (getEqualityState==EQUAL) — избегает лишней Designer-сессии/lease/webserver-guard. По умолчанию false. Объявлен здесь для pass-through через диспетчер."
                }
              },
              "required": ["command"],
              "additionalProperties": true
            }
            """; //$NON-NLS-1$

    private final ITool metadataSmoke;
    private final ITool traceExport;
    private final ITool analyzeError;
    private final ITool updateInfobase;
    private final ITool launchApp;

    public EdtDiagnosticsTool() {
        this(new EdtMetadataSmokeTool(),
             new EdtTraceExportTool(),
             new AnalyzeToolErrorTool(),
             new EdtUpdateInfobaseTool(),
             new EdtLaunchAppTool());
    }

    EdtDiagnosticsTool(ITool metadataSmoke, ITool traceExport,
                       ITool analyzeError, ITool updateInfobase, ITool launchApp) {
        this.metadataSmoke = metadataSmoke;
        this.traceExport = traceExport;
        this.analyzeError = analyzeError;
        this.updateInfobase = updateInfobase;
        this.launchApp = launchApp;
    }

    @Override
    public String getDescription() {
        return "Runs EDT diagnostics and runtime commands: smoke, trace export, error analysis, infobase update, and app launch. " //$NON-NLS-1$
                + "Per-command required fields: " //$NON-NLS-1$
                + EdtDiagnosticsCommandContract.describeRequirements()
                + ". project and project_name are interchangeable aliases."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        Map<String, Object> p = params.getRaw();
        String command = p.get("command") != null ? String.valueOf(p.get("command")) : null; //$NON-NLS-1$
        if (command == null || !ALL_COMMANDS.contains(command)) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("Unknown command: " + command + //$NON-NLS-1$
                            ". Use one of: " + String.join(", ", ALL_COMMANDS))); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Apply project/project_name aliasing so each delegate sees the field-name it
        // expects, regardless of which one the caller supplied.
        Map<String, Object> aliased = EdtDiagnosticsCommandContract.applyProjectFieldAliases(p);

        // Pre-flight per-command required-field check.  This used to fail inside
        // the delegate with a confusing "[INVALID_ARGUMENT] project is required"
        // because the parent schema never advertised the field; now we surface it
        // up-front with a message that names the right field for the chosen command.
        String missing = EdtDiagnosticsCommandContract.findFirstMissingRequired(command, aliased);
        if (missing != null) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure(EdtDiagnosticsCommandContract.missingRequiredFieldMessage(command, missing)));
        }

        ITool delegate = switch (command) {
            case "metadata_smoke" -> metadataSmoke; //$NON-NLS-1$
            case "trace_export" -> traceExport; //$NON-NLS-1$
            case "analyze_error" -> analyzeError; //$NON-NLS-1$
            case "update_infobase" -> updateInfobase; //$NON-NLS-1$
            case "launch_app" -> launchApp; //$NON-NLS-1$
            default -> null;
        };

        if (delegate == null) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("Unknown command: " + command)); //$NON-NLS-1$
        }

        // Forward parameters (with project alias applied) to the delegate tool
        return delegate.execute(aliased);
    }
}
