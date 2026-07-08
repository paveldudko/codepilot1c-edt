package com.codepilot1c.core.tools.qa;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

/**
 * Composite mutating QA generation tool that dispatches to
 * domain-specific QA config/feature generation tools.
 *
 * <p>Commands:</p>
 * <ul>
 *   <li>{@code init_config} — create initial qa-config.json</li>
 *   <li>{@code migrate_config} — migrate/normalize qa-config.json</li>
 *   <li>{@code compile_feature} — compile structured QA plan into feature file</li>
 * </ul>
 *
 * <p>Replaces individual qa_init_config, qa_migrate_config, qa_compile_feature tools.</p>
 */
@ToolMeta(name = "qa_generate", category = "file",
        surfaceCategory = "qa",
        mutating = true,
        tags = {"workspace"})
public class QaGenerateTool extends AbstractTool {

    private static final Set<String> ALL_COMMANDS = Set.of(
            "init_config", "migrate_config", "compile_feature"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "QA generation command: init_config, migrate_config, or compile_feature",
                  "enum": ["init_config", "migrate_config", "compile_feature"]
                },
                "config_path": {"type": "string", "description": "Путь к qa-config.json (все команды)."},
                "project_name": {"type": "string", "description": "Имя EDT-проекта (init_config/migrate_config/compile_feature)."},
                "epf_path": {"type": "string", "description": "init_config: путь к Vanessa .epf."},
                "params_template": {"type": "string", "description": "init_config: шаблон VAParams."},
                "force": {"type": "boolean", "description": "init_config: перезаписать существующий config."},
                "dry_run": {"type": "boolean", "description": "migrate_config: предпросмотр без записи."},
                "create_backup": {"type": "boolean", "description": "migrate_config: бэкап перед миграцией (по умолчанию true)."},
                "plan": {"type": "object", "description": "compile_feature: план сценария (QaScenarioPlan: scenarioTitle, recipeId, steps, tags, …)."},
                "auto_create_config": {"type": "boolean", "description": "compile_feature: авто-создание config (по умолчанию true)."},
                "overwrite": {"type": "boolean", "description": "compile_feature: перезаписать feature-файл."},
                "feature_title": {"type": "string", "description": "compile_feature: заголовок фичи."},
                "feature_file": {"type": "string", "description": "compile_feature: имя/путь feature-файла."},
                "language": {"type": "string", "description": "compile_feature: язык (ru/en)."}
              },
              "required": ["command"],
              "additionalProperties": true
            }
            """; //$NON-NLS-1$

    private final ITool initConfig;
    private final ITool migrateConfig;
    private final ITool compileFeature;

    public QaGenerateTool() {
        this(new QaInitConfigTool(),
             new QaMigrateConfigTool(),
             new QaCompileFeatureTool());
    }

    QaGenerateTool(ITool initConfig, ITool migrateConfig, ITool compileFeature) {
        this.initConfig = initConfig;
        this.migrateConfig = migrateConfig;
        this.compileFeature = compileFeature;
    }

    @Override
    public String getDescription() {
        return "Generates QA artifacts: creates or migrates the qa-config and assembles a feature file from a structured scenario plan."; //$NON-NLS-1$
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

        ITool delegate = switch (command) {
            case "init_config" -> initConfig; //$NON-NLS-1$
            case "migrate_config" -> migrateConfig; //$NON-NLS-1$
            case "compile_feature" -> compileFeature; //$NON-NLS-1$
            default -> null;
        };

        if (delegate == null) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("Unknown command: " + command)); //$NON-NLS-1$
        }

        return delegate.execute(p);
    }
}
