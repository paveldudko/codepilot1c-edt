# MCP-bridge lite — план распила (experiment) — v2

**Ветка:** `pd/mcp-bridge-lite` (от `pd/bsl-tuning` @ `9724b14`).
**Цель:** лёгкий плагин = только MCP-host (мост) + EDT-tools. Удаляем чат, агент, LLM-провайдеры, исходящий MCP-client, ~24 jar, `web/remote`.
**Способ:** жёсткое удаление, **под компилятор**.
**Статус:** v3 — свёрнуты ОБА ревью (Fable + Codex, независимо сошлись; вердикт обоих по v1 = NEEDS-REWORK). Все находки учтены.
**Роль оркестратора:** этап → сабагент; я гоняю gate-сборку, не пускаю дальше, пока не зелено; коммит на этап.

---

## Что изменилось против v1 (по ревью Fable)

1. **`evaluation/trace` — ЖИВОЙ код моста, НЕ удаляется.** `McpHostRequestRouter` (`TraceEventType`, live 365/386/410), `McpHostHttpTransport` (`AgentTraceSession.startMcpSession` 291, события 302/324), `McpHostSession` (поле `AgentTraceSession`), `ToolRegistry`/`ToolExecutionService` (trace-параметры). → `evaluation/trace` = **TRIM**: удалить `TracingLlmProvider` (тянет `provider`/`model.Llm*`), обрезать `AgentTraceSession.startAgentRun(AgentConfig, ILlmProvider,…)` (import `agent.AgentConfig`); `startMcpSession` + `TraceEventType` остаются. Остальной `evaluation/*` (eval/benchmark) — DELETE.
2. **`core.tests` — в ДЕФОЛТНОМ реакторе** (`bundles/pom.xml:19-21`; `ui.tests` — нет, под профилем). Его исходники компилируются на КАЖДОМ гейте (`-DskipTests` = не запускать, но компилировать). → **Отдельного Stage-«тесты» нет.** Каждый этап удаляет тесты на то, что он сносит, в том же коммите.
3. **`ToolSurfaceContext` trim НЕ в Stage 1** — его API (`AgentProfile`/`AgentProfileRegistry`/`LlmProviderConfig`, `defaultProfile()`/`getProviderConfig()`/builder `.profile()`) зовут `AgentRunner:678`, `ProviderContextResolver:33-34`, тесты. Пока чат жив — обрезать нельзя. → tools/-развязка целиком в Stage 3.
4. **`internal/VibeCorePlugin` (активатор, KEEP) — крупный seam.** Imports `backend/provider.config/provider/remote/mcp(client)/state`; live в `start()`: `MemoryService.initialize()`, LLM/backend init (126-144), `McpServerManager…startEnabledServers()` (149), `IRemoteWorkbenchBridge` tracker (99). → **TRIM в Stage 3.**
5. **`VibeUiPlugin` + `ui/remote/RemoteWorkbenchBridge` — Stage 2 seam.** `VibeUiPlugin` тянет `core.remote.IRemoteWorkbenchBridge`, `ui.theme.ThemeManager`, `ui.remote.RemoteWorkbenchBridge`; `ui/remote/RemoteWorkbenchBridge` тянет `core.remote.*` + `ui.editor.CodeApplicationService`. → **Stage 2: удалить `ui/remote/*`, обрезать `VibeUiPlugin`** (theme init/dispose 77/114, bridge registration 84/144-154).
6. **`McpHostServer` регистрирует `StateResourceProvider`** (13/72), который тянет `state.VibeState/VibeStateService` (DELETE). → удалить `StateResourceProvider` + регистрацию (теряем MCP-ресурс `state`; `workspace`/`diagnostics` ресурсы на `EdtWorkspaceStateService` остаются). **В Stage 1** (это host-код).
7. **`settings/PromptTemplateService`** тянет `agent.prompts.WorkspacePromptSourceResolver` → **DELETE** (вместе с `PromptCatalog`). Хосту из `settings/` нужны: `SecureStorageUtil`, `WorkspaceScope`, `VibePreferenceConstants` (их зовут `McpHostConfigStore:14-16`, `McpHostOAuthService:24-25`). **Класса `SecureTokenStore` НЕТ** — опись v1 ошибалась.
8. **`util/AttachmentTextExtractor`** тянет pdfbox; единственный потребитель — `ChatView` (DELETE). → удалить файл вместе с jar'ами pdfbox/opennlp.
9. **`git/GitService`** тянет `session` (409-417, 451-454, current-session fallback в try/catch) → **TRIM** (малый).
10. **Preference-страницы — позитивно:** удалить ВСЁ в `ui/preferences/` **кроме** `McpHostPreferencePage`, `McpHostProfileDialog`, `VibePreferencePage`, `QaProjectPropertyPage`. (`McpServersPreferencePage`/`McpServerEditDialog` = UI исходящего client'а — тоже DELETE.)

**Минорное (не блокирует, но вшить):**
- `build.properties`: core `bin.includes` содержит `web/` (только `web/remote/`) и `skills/` — оба осиротеют → убрать из `bin.includes`. ui `bin.includes` содержит `web/` → убрать.
- ui `MANIFEST.MF` Import-Package `org.eclipse.tm.terminal.*` (45-47) — обслуживает только `OpenTerminalHandler`. **Решение: `OpenTerminalHandler` + terminal-import ОСТАВЛЯЕМ** (не чат, утилита; сужать скоуп эксперимента не будем; вынести в owner-решение позже).
- `DumpEdtDiagnosticsHandler` (тянет только KEEP `core.logging`) = **KEEP**. Не удалять при «снести все handlers кроме RestartMcp».
- Export-Package чистить с обеих сторон (core экспортит agent/backend/provider/streaming/state/session/context/remote — `MANIFEST.MF:105-145`; ui — chat/diff/editor/markdown/views).

**Дельты Codex (свёрнуты поверх Fable):**
- **`tools/surface/ToolSurfaceAugmentor` — TRIM.** В `withDefaultContributors()` (35-38) конструирует `QwenToolSurfaceRewriteContributor`, `QwenToolSurfaceContributor`, `DynamicToolSurfaceContributor`. Удаляя `QwenToolSurfaceRewriteContributor` — убрать его из фабрики; `QwenToolSurfaceContributor`/`DynamicToolSurfaceContributor` тянут `isBackendSelectedInUi()` (backend=DELETE) → тоже DELETE + вычистить из `withDefaultContributors()`.
- **`settings/VibePreferenceInitializer` — TRIM.** Обрезать LLM/chat/prompt-дефолты; host-дефолты оставить (см. memory: он мешает host-дефолты).
- **`core.tests/pom.xml:37-38`** зависит от `pdfbox-app` → убрать зависимость; удалить `AttachmentTextExtractorTest`.
- **core `OSGI-INF/l10n/bundle.properties`** — ключи `7,19` (llmProvider/promptProvider) + провайдерские `9-16` удалить вместе с extension-points.

**Что v1 угадал верно (подтверждено Fable):** расщепление `model/*` (`ToolCall`/`ToolDefinition` тянут только `java.util.Objects`, Llm-классы нигде вне DELETE); seam 4 (`AgentProfileRegistry` в router — мёртвый); seam 3 (PromptTemplateProvider); host≠client OAuth; `permissions/*` SHARED; extension-points (`toolProvider` живёт в `ToolRegistry:59`, `llmProvider`/`promptProvider` больше никто не контрибутит → `.exsd` удаляются без висяков); feature/site (2 бандла, оба остаются, p2 не ломается); jar-скоупинг (кроме pdfbox); `core/diff`+`core/edit` нужны KEEP-tools (`EditFileTool`/`GrepTool`) — не трогать; UI→core через Require-Bundle (удаление core-пакетов для UI — только compile-level).

---

## KEEP / DELETE / TRIM — итог

**KEEP (as is):** core `mcp/host/*` (кроме `RemoteWebController`, `StateResourceProvider`), `mcp/model/*`, `tools/*` (кроме agent-tools), `edt/*`, `diagnostics/*`, `git/*` (trim), `http/*`, `permissions/*`, `diff/*`, `edit/*`, `state/EdtWorkspaceStateService`, `settings/{SecureStorageUtil,WorkspaceScope,VibePreferenceConstants,…}`, `logging/*`, `internal/*` (trim); `model.{ToolDefinition,ToolCall}`; UI `preferences/{McpHostPreferencePage,McpHostProfileDialog,VibePreferencePage,QaProjectPropertyPage}`, `handlers/{RestartMcpHandler,DumpEdtDiagnosticsHandler,OpenTerminalHandler}`, `tools/{GetDiagnosticsTool,GetDiagnosticsDetailsTool}`, `diagnostics/*`, `startup/McpHostStartup`, `mcp/McpProfilesChangeMonitor`, `internal/VibeUiPlugin` (trim).

**TRIM (оставить файл, вырезать DELETE-ссылки):** `internal/VibeCorePlugin`, `mcp/host/prompt/PromptTemplateProvider`, `mcp/host/McpHostRequestRouter` (мёртвый import), `mcp/host/transport/McpHostHttpTransport` (/remote), `tools/ToolRegistry`, `tools/surface/ToolSurfaceContext`, `evaluation/trace/AgentTraceSession`, `git/GitService`; UI `internal/VibeUiPlugin`.

**DELETE:** core `agent/*`, `provider/*`, `session/*`, `memory/*`, `skills/*`, `backend/*`, `remote/*`, `context/*`, `feedback/*`, `streaming/*`, `evaluation/*` (кроме `trace`), chat-state (`state/Vibe*`, `state/EdtStateBeacon`), исходящий client (`mcp/` root client + `mcp/client` + `mcp/config` + `mcp/transport` + client-`mcp/auth`), `mcp/host/transport/RemoteWebController`, `mcp/host/resource/StateResourceProvider`, `settings/{PromptCatalog,PromptTemplateService}`, `util/AttachmentTextExtractor`, agent-tools (`tools/{TaskTool,DelegateToAgentTool,SkillTool,ProviderContextResolver}`, `tools/memory/RememberFactTool`, `tools/surface/QwenToolSurfaceRewriteContributor`), model-LLM (`model/{LlmMessage,LlmResponse,LlmRequest,LlmStreamChunk,LlmAttachment,LlmConversationSanitizer,LlmContentPart}`); UI chat (`views/*` кроме нужных, `chat/*`, `markdown/*`, `theme/*`, `editor/*`, `diff/*`, `statusbar/*`, `menu/*`, `remote/*`, chat-handlers + code-action handlers, `dialogs/{RegistrationDialog,LoginDialog,ToolConfirmationDialog}`, `preferences/*` кроме 4 KEEP); web (`ui/web/mermaid.min.js`, `core/web/remote/*`); jar'ы (ui: flexmark×16/autolink/annotations; core: langchain4j×2/langgraph4j×3/async-generator/opennlp/pdfbox); тесты на всё удалённое.

**Правило неуверенности:** если удаление ломает import в KEEP/TRIM-файле и непонятно trim/delete — **оставить + доложить оркестратору**, не гадать.

---

## Верификация (этот бокс)
- Компиляция (гейт): `mvn -Plocal-target -DskipTests clean verify` — компилирует и main, и `core.tests`.
- Один тест: `mvn -Plocal-target -Dtest=<Class> -DfailIfNoTests=false clean verify` (полный реактор).
- Deployable p2: `mvn -Plocal-target -DskipTests clean verify` → `repositories/com.codepilot1c.update/target/repository/`.
- Полный `verify` с тестами штатно падает на env — гейтим на компиляцию + точечные host-тесты (`ProfileEndpointTest`, `SharedProfileStoreTest`, `McpHostManagerPortOverrideTest`, `McpHostRequestRouterTraceTest`).

---

## Этапы (тесты вшиты в каждый)

### Stage 0 — Baseline (оркестратор)
`mvn -Plocal-target -DskipTests clean verify` до правок = зелено. Точка отката.

### Stage 1 — Развязать host↔engine (core), чат ещё компилируется
- `McpHostHttpTransport`: убрать `/remote*` (поле `remoteWebController`, аргумент ctor, 3 контекста, import `remote.AgentSessionController`). **Оставить** `AgentTraceSession.startMcpSession` (KEEP trace).
- Удалить `mcp/host/transport/RemoteWebController.java` + `core.tests/.../transport/RemoteWebControllerTest.java` (тот же коммит).
- `McpHostRequestRouter`: удалить мёртвый import `agent.profiles.AgentProfileRegistry` (оставить `evaluation.trace.TraceEventType`).
- `mcp/host/prompt/PromptTemplateProvider`: убрать `agent.prompts.*` (статика/пусто или снять capability `prompts/*`); поправить его тест.
- `mcp/host/resource/StateResourceProvider`: удалить + снять регистрацию в `McpHostServer` (и тесты, если есть).
- **Exit:** grep `import com.codepilot1c.core.(agent|provider|remote|session|memory|backend|context|feedback|skills)\.` по `mcp/host` = пусто; `-DskipTests verify` зелено. Commit.

### Stage 2 — Удалить чат-UI (`com.codepilot1c.ui`)
- Удалить UI chat (DELETE-список) **+ `ui/remote/*` + `ui/editor/*`**.
- TRIM `internal/VibeUiPlugin`: убрать theme init/dispose, `registerRemoteWorkbenchBridge`, соответствующие imports. Оставить регистрацию UI-tools (`GetDiagnostics*`) + запуск `McpProfilesChangeMonitor`.
- `preferences/`: удалить всё, кроме 4 KEEP.
- Почистить `ui/plugin.xml` (views/handlers/menus/prefs/statusbar чата), `OSGI-INF/l10n`, `MANIFEST.MF` (Export chat-пакетов; `Bundle-ClassPath` flexmark/autolink/annotations; terminal-import — **оставить**), удалить jar'ы из `ui/lib`, `ui/web/mermaid.min.js`, обновить `ui/build.properties` (убрать `web/`).
- Удалить UI-тесты на чат (если есть; `ui.tests` вне дефолт-реактора).
- **Exit:** `-DskipTests verify` зелено. Commit.

### Stage 3 — Удалить чат/агент/провайдеры/client (`com.codepilot1c.core`) + развязать tools + активатор
- Удалить core DELETE-набор (agent/provider/session/memory/skills/backend/remote/context/feedback/streaming/evaluation-кроме-trace/chat-state/исходящий-client).
- `evaluation/trace`: TRIM — удалить `TracingLlmProvider`, обрезать `AgentTraceSession.startAgentRun` (agent/provider), оставить `startMcpSession`/`TraceEventType`.
- `model/*`: оставить `ToolDefinition`/`ToolCall`, удалить Llm-классы.
- tools/: удалить agent-tools; TRIM `ToolRegistry` (снять регистрации `task/delegate_to_agent/skill` + поле `providerContextResolver` + import `agent.profiles.AgentProfile`), TRIM `ToolSurfaceContext` (убрать `AgentProfile`/`LlmProviderConfig` из API). TRIM `tools/surface/ToolSurfaceAugmentor` (убрать из `withDefaultContributors()` конструкторы Qwen/Dynamic-контрибьюторов) + удалить `QwenToolSurfaceRewriteContributor`/`QwenToolSurfaceContributor`/`DynamicToolSurfaceContributor` (тянут `isBackendSelectedInUi()`). Проверить `ToolExecutionService` (import `model.ToolCall` — KEEP; agent-ссылки — trim).
- TRIM `internal/VibeCorePlugin`: убрать `MemoryService`/LLM/backend/`McpServerManager`/`IRemoteWorkbenchBridge`/state init; оставить `McpHostManager.startIfEnabled` + logging.
- TRIM `git/GitService` (убрать session-fallback). TRIM `settings/VibePreferenceInitializer` (LLM/chat/prompt-дефолты, host-дефолты оставить). Удалить `settings/{PromptCatalog,PromptTemplateService}`, `util/AttachmentTextExtractor`.
- `core/plugin.xml`: снять `llmProvider`+`promptProvider` (+ built-in providers + иконки claude/openai/ollama + `.exsd` `schema/{llmProvider,promptProvider}.exsd`); оставить `toolProvider`. `OSGI-INF/l10n/bundle.properties` — удалить ключи llmProvider/promptProvider + провайдерские.
- `MANIFEST.MF`: почистить Export/Import; `Bundle-ClassPath` — убрать langchain4j×2/langgraph4j×3/async-generator/opennlp/pdfbox, удалить jar'ы из `core/lib`. Удалить `core/web/remote/*`; `core/build.properties` — убрать `web/` и `skills/`.
- Удалить ~28 core-тестов на удалённые пакеты + `AttachmentTextExtractorTest`; `core.tests/pom.xml:37-38` — убрать зависимость `pdfbox-app`. (Тот же этап — реактор их компилирует.)
- **Exit:** `-DskipTests verify` зелено + p2-сайт собирается + оставленные host-тесты зелёные. Commit.

### Stage 4 — Финал (оркестратор + сабагент)
CHANGELOG `[Unreleased]`; deployable build → p2; sanity-grep на висячие ссылки (`agent`/`provider`/`Llm`/`remote`); проверить `build.properties`/feature/site. Опц. live-smoke на реальном EDT (owner-side). Commit.

---

## Порядок и почему
Stage 1 (host-развязка) → Stage 2 (UI-чат) → Stage 3 (core-чат + tools + активатор) → Stage 4 (финал). После Stage 1 `mcp/host` не ссылается на движок. Stage 2 чистит UI до того, как Stage 3 удалит core-экспорты, которые UI-чат импортировал. Тесты вшиты в этап (реактор `core.tests` компилируется на каждом гейте). Коммит-на-этап = гранулярный откат.
