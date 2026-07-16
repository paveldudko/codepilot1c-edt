# Changelog

All notable changes to the codepilot1c-edt plugin are recorded here.

The format is loosely based on [Keep a Changelog](https://keepachangelog.com/).
Add an entry under **[Unreleased]** for every notable change; on a release, rename
the section to the build/version and start a fresh **[Unreleased]**. Reference the
commit hash in parentheses where useful.

## [Unreleased] — branch `pd/bsl-tuning`

### BF-12936 (2026-07-16) — `web_publication publish` can carry custom HTTP services into the vrd

- **`web_publication publish` now accepts publication content beyond the plain infobase binding**, so an
  EDT-managed publication can reproduce a hand-authored `default.vrd`'s custom HTTP services and flags:
  `http_services: [{name, root_url, enable}]`, `publish_http_by_default`, `publish_web_by_default`,
  `enable_standard_odata`, `enable_system_analytics`, and a publication-level `pool: {size, max_age}`. These
  map onto the EDT `InfobasePublication` model (`HttpServices`/`HttpService.rootUrl`, `OData`,
  `enableSystemAnalytics`, `Pool`) and the publish delegate serializes them into the generated vrd. Unblocks
  making script-managed sandbox publications first-class EDT-managed — chiefly reproducing
  `<service name="BSLAnalyzerService" rootUrl="bsl-analyzer">` (the bsl-analyzer-workspace MCP dependency),
  which the bare publish silently dropped. Feasibility + design:
  `TaskArtifacts/BF-12008/BF-12936/edt-optione1-feasibility.md`.
- **Documented fidelity limit:** the EDT `HttpService` model is `{name, rootUrl, enable}` only — per-service
  pool tuning (`reuseSessions`/`sessionMaxAge`/`poolSize` on a `<service>`) is not representable (only the
  publication-level `Pool`). A migrated publication is functionally faithful (the service publishes at its
  `rootUrl`) but not byte-identical on per-service pool numbers.
- Unit tests: `WebPublicationExtrasParsingTest` (the pure param→options parser). The model→vrd serialization
  is EDT's — validated live (battle test on stack-2). Build green (`-Plocal-target`).

### BF-12936 feedback (2026-07-16) — `mutate_form_model` form titles leaked `ru` in EN-primary projects

- **`add_command` / `rename_command` / `set_item` titles now land in the project's primary content
  language instead of always `ru`.** A plain-string `title` was written under `<key>ru</key>` even in
  English-primary projects, while `create_metadata` synonyms correctly resolved to `en` — the divergence
  was `resolveProjectDefaultLanguageCode(form)`, which walked `EcoreUtil.getRootContainer(form)`: a Form
  EObject lives in its own `.form` resource, so the root container is never the `Configuration` and the
  resolver always fell back to `ru`. A Configuration-aware overload now resolves the title's default
  locale via the same `resolveSynonymLocaleKey` the synonym path uses (default language → first
  configured language → `ru`), threaded through the form-mutation dispatcher (which has the
  Configuration in scope) into `add_command`, `rename_command`, and `set_item`'s title handling. The
  rare `itemManagementService == null` fallback (new table/decoration titles) keeps the prior ctx-only
  resolution. Correctness rests on the already-tested `BmSynonymLocaleResolver`; live confirmation on the
  AM project pending. (feedback `2026-07-16-mutate-form-model-add-command-title-locale-defaults-ru`)
- Residual (queued, not in this change): no op removes/replaces a *specific* stray title-locale key on an
  existing command (only prevents new ones).

### BF-12936 feedback (2026-07-16) — `update_metadata` localized strings also leaked `ru`

- **`update_metadata` `set:{synonym|objectPresentation|listPresentation|…}` with a plain string now lands
  in the project's default content language, not a hard-coded `ru`.** The addendum to the form-title note
  reported the same leak on `recordPresentation`/`listPresentation`; the shared cause was the
  `update_metadata` localized-string writers (`applyEMapStringPatch` / `applyStringMapPatch`) hard-coding
  `RU_LANGUAGE` for a plain string — so `create_metadata` synonyms resolved to `en` (via
  `setCommonProperties`→`resolveSynonymLocaleKey`) but the *update* path always wrote `ru`. Both writers +
  the synonym set/unset now resolve the locale key via the same `resolveSynonymLocaleKey`, threading the
  `Configuration` (already in scope at every call site). Fixes synonym and every localized presentation
  property in one choke-point. `BmSynonymLocaleResolver` (already tested) backs the resolution; live
  confirmation pending. (feedback `…add-command-title-locale-defaults-ru`, addendum)

### BF-12936 feedback (2026-07-16) — `mutate_form_model set_item userVisible` flat per-role map

- **A flat per-role `userVisible` map no longer silently wipes the item's visibility default.** The tool
  description advertises `userVisible` as accepting a "per-role map", but `set_item` only implemented the
  structured `{common, for:[{role,value}]}` shape — a flat `{common:false, "RoleName":true}` (the shape
  the description invites) dropped every role key and applied only `common=false`, wiping the working
  default to hidden-for-everyone (a destructive, data-losing no-op). `EdtMetadataService` now harvests a
  flat map's role→bool keys (`parseFlatRoleEntries`) when no `for` key is present, skipping the reserved
  `common`/`value`/`visible`/`enabled`/`for` keys; a non-boolean role value is rejected rather than
  silently dropped. Both accepted shapes are now documented on the tool. (feedback
  `2026-07-16-mutate-form-model-set-item-uservisible-by-role-breaks-default`)
- Unit tests: 6 new cases in `UserVisibleForRoleParsingTest` (incl. the exact reported input). Build
  green (`-Plocal-target`, 17/17).

### BF-12936 feedback (2026-07-16) — `update_infobase` job-id/timeout ergonomics

- **`update_infobase` no longer tells a caller to poll a job id that cannot be resolved.** A
  synchronous (or just-registering async) update holds the in-flight guard slot with a `"sync"` /
  `"starting"` sentinel, not a `BackgroundJobRegistry` job. The `UPDATE_ALREADY_RUNNING` rejection used
  to emit `in_flight_job_id: "sync"` + a "poll `update_infobase_status(job_id="sync")`" hint, which dead-
  ended at `Unknown job: sync`. Now the payload distinguishes a real, pollable `in_flight_job_id` (async)
  from a non-pollable `in_flight_mode: sync|starting` (adds `in_flight_pollable`), with mode-specific
  guidance (wait out the sync call / retry the poll). `update_infobase_status` also recognizes the two
  sentinels and returns a `not_pollable_sentinel` explanation instead of the bare `Unknown job`.
  (feedback `2026-07-16-update-infobase-sync-job-id-unpollable`)
- **The 300s update ceiling is now a caller-overridable `timeout_s` (60..1800, default 300).** A full-
  schema exclusive update of a multi-hundred-MB+ file infobase can genuinely need longer than 300s; the
  old hard cap aborted it and reported the misleading "held by another process" as the sole cause. The
  `PROCESS_TIMEOUT` message now names both plausible causes (still-restructuring large IB → raise
  `timeout_s`; or a real external holder) and surfaces the Designer PID(s) EDT spawned that may still
  hold the file lock (`aborted_designer_pids` + `timeout_s` in the payload) — the process is *named, not
  killed*, since killing a Designer mid-restructure would corrupt the update. (feedback
  `2026-07-16-update-infobase-process-timeout-300s-ceiling-reproducible`)
- Unit tests: `EdtUpdateInfobaseErgonomicsTest` (sentinel vs real job id, timeout clamp, `timeout_s`
  parsing, `PROCESS_TIMEOUT` message). Build green (`-Plocal-target`, 24/24 across the touched suites).

### Experiment — `mcp-bridge-lite`: strip the in-EDT chat/agent, keep the MCP bridge only

- **Experimental branch `pd/mcp-bridge-lite` (forked from `pd/bsl-tuning`) turns the plugin into a
  lightweight MCP-server-only bridge, removing the entire in-EDT chat/agent/LLM feature (~40% of the
  Java source, ~69k lines).** Delivered in three staged, build-green commits:
  - `c52b62d` (s1) — decouple the MCP host from the agent/LLM engine: drop the `/remote` browser
    companion, the dead agent imports, and the `state` MCP resource; serve static prompt templates.
  - `96d81b7` (s2) — delete the chat/agent UI (`views`/`chat`/`markdown`/`theme`/`editor`/`diff`/
    `statusbar`/`menu`/`remote`/`dialogs`, AI code-action handlers, model/provider preference pages)
    + 18 chat-only jars (flexmark ×16, autolink, annotations); keep the MCP-host prefs + `get_diagnostics`.
  - `6c5fa27` (s3) — delete the core engine: `agent`/`provider`/`session`/`memory`/`skills`/`backend`/
    `remote`/`context`/`feedback`/`streaming`/`evaluation.benchmark`, chat-state, the outbound MCP
    client, and the LLM-wire `model` classes (kept `ToolDefinition`/`ToolCall`); trim
    `VibeCorePlugin`/`ToolRegistry`/`ToolSurfaceContext`/`AgentTraceSession`/`GitService`; drop the
    `llmProvider`/`promptProvider` extension points + 8 jars (langchain4j, langgraph4j, pdfbox, …).
- **What remains:** the MCP host (`mcp/host`, `mcp/model`), the EDT tools (`tools/*`), diagnostics,
  git, permissions, and the host preference/startup UI. Build green (`mvn -Plocal-target -DskipTests
  verify`, 11/11); the 4 host test suites pass (19/19). Plan + review notes: `mcp-bridge-lite-PLAN.md`.
  Not merged to `pd/bsl-tuning` — kept as a self-contained divergent line pending a keep/mainline call.
- **Live-validated** on sandbox EDT 2025.2.3: MCP host binds (`:8764`/`:8767`), `/health` 200,
  `tools/list` (per-endpoint gating) + `tools/call` clean; `get_diagnostics` present, agent tools gone.
- `f252b64` — follow-up fix: the root preference page kept the chat/agent field editors (Max Tool
  Iterations, auto-compaction threshold, …) after their defaults stopped being seeded, so the range
  validators rejected the un-seeded `0` and blocked saving the whole page (incl. MCP-host settings).
  Removed the 6 dead fields; kept the host/QA/terminal/diagnostics ones.

### BF-12705 — get_infobase_sync_state: actionable guidance for the update→still-NOT_EQUAL non-convergence mode

- **The `needs_update` hint no longer sends an agent into a non-terminating `update_infobase` loop.** (this
  commit) A live finding (BF-12705, 2026-07-13) showed a clean `update_infobase updated:true` (zero
  contention, no phantom Designer, no Apache-wsap lock, not dynamic-only-reported) still leaving
  `get_infobase_sync_state` at `NOT_EQUAL`/`work_ready:false` indefinitely — and the old hint ("run
  update_infobase before tests") is not actionable there, so an agent either rabbit-holes or must be told
  out-of-band to ignore `work_ready`. The hint now states that if `update_infobase` already returned
  `updated:true` and the state is still `NOT_EQUAL`, re-running the same update will NOT converge; the
  usual cause is a DYNAMIC apply (main↔DB config diverged until an EXCLUSIVE update) or a not-yet-refreshed
  in-memory equality state; remediation is one exclusive update or treating `work_ready` as advisory for a
  no-schema-impact change. **Honest scope on the "explain the residual delta" ask:** EDT's
  `IInfobaseSynchronizationManager` exposes only the coarse 3-value `InfobaseEqualityState`
  (`EQUAL`/`NOT_EQUAL`/`LOADING`) — there is no object-level "what differs" API at this layer without a
  full Designer-style comparison (which would spawn a configurator and defeat the tool's read-only
  purpose), so surfacing the delta itself is not implemented; the tool instead documents the limitation
  and gives the actionable remediation. Ref:
  `codepilot1c-feedback/2026-07-13-update-infobase-completes-but-equality-state-never-converges-third-mode.md`.

### BF-12839 — mutate_form_model: new `add_form_parameter` op to declare a form-level Parameter

- **`mutate_form_model` now supports an `add_form_parameter` operation** (`{op:"add_form_parameter",
  name, type, [key_parameter], [comment]}`) that creates a `FormParameter` in the form's
  `getParameters()` collection with a resolved value type. (this commit) It is the only tool path to
  declare a form Parameter: `set_form_props` rejects `parameters` as a reference collection
  (`applySimpleFeatureValue` → "Reference property updates are not supported directly"), and
  `add_metadata_child` has no `Parameter` child_kind. Without it a correct
  `OpenForm(..., New Structure("X", ...))` + `Parameters.Property("X")` still tripped the cosmetic
  `unknown-form-parameter-access` diagnostic, which the dev pre-commit gate (0 warnings on touched
  lines) could not clear through the tool surface. The op mirrors `add_command` (non-visual,
  name-keyed, no form-item id); the value type reuses the attribute type-resolver, which was
  generalized from `AbstractFormAttribute` to `EObject` (a `FormParameter`'s `valueType` lives on a
  distinct EReference — `getFormParameter_ValueType()` — and `setTypeDescriptionOnEObject` now resolves
  the generic `valueType` feature). No validation-layer change (the validate path passes `operations[]`
  through verbatim). Source-contract-covered by `AddFormParameterContractTest` (the `Form`/`FormParameter`
  EMF types resolve only in the OSGi runtime, so behavior is validated live).
  Ref: `codepilot1c-feedback/2026-07-14-bf12839-mutate-form-model-no-way-to-declare-form-parameter.md`.

### BF-12839 — connect_infobase: distinguish Designer-agent SSH auth failure from a credential prompt

- **New error code `EDT_DESIGNER_AGENT_AUTH_FAILED`** and a synchronous-path classifier
  (`ConnectInfobaseTool.findDesignerAuthFailureMessage` / `isDesignerAuthFailureMessage`) so a JSch
  `Auth fail` when EDT opens an SSH session to its OWN locally-spawned Designer agent is no longer
  collapsed into `EDT_AUTH_REQUIRED`. (this commit) The new code carries `retry_with:{force:true}` and
  a hint that login/password will NOT help (a fresh `connect_infobase(force=true)` usually rewrites the
  stale/OS-auth-only association and clears it). **Honest scope:** this classifier only fires when EDT
  throws the failure *synchronously*; in the observed BF-12839 case it instead manifested as the 50s
  bind timeout, where no exception reaches the tool and the sub-cause is indistinguishable from a real
  credential prompt at the tool layer.
- **Broadened the misleading `EDT_AUTH_REQUIRED` timeout message + hint.** (this commit) It used to tell
  the caller to "pass login/password" even when login/password were already passed. It now enumerates
  the three real causes (interactive credential prompt / Designer-agent SSH auth failure / unreachable
  server), states that passing creds rules out (a) so (b) is the suspect, and tells the caller to verify
  with `get_workspace_state` before re-binding — EDT's worker may finish the bind after the 50s cap, so
  a reported timeout must not be misread as a broken bind. Addresses the note's "reported failure but the
  bind actually registered" gap without flipping `success:false`→`true` (a stale pre-existing association
  would falsely read as this call's success — worse for a gating agent than a clear "verify first" signal).
- Unit-covered by new cases in `ConnectInfobaseLockDetectionTest` (classifier + enum distinctness).
  Ref: `codepilot1c-feedback/2026-07-14-bf12839-launch-app-hangs-after-designer-agent-sshauth-fail-bind.md`.

### BF-12839 — connect_infobase: non-interactive bind for credentialed file bases (prime access settings before associate)

- **The access-settings modal on a credentialed (re-)bind is fixed at the root: passed credentials are
  now written BEFORE `associate()`.** (this commit) `EdtInfobaseConnectService.finishBind` used to
  `associate()` first and `storeAccessSettings()` last (the BF-13140 creds-last order, which keeps a
  blocked shared secure-storage flush from stranding the primary pointer). But `associate()`
  *synchronously* fires EDT's association event, whose behaviour delegate restores previously-open
  Designer sessions (`connectAndRestoreState → DesignerClient.connect`) using the infobase's *currently
  stored* access settings — still OS/empty at that point, so on a base copied from a credentialed source
  the restore fails to authenticate (JSch `Auth fail`) and EDT raises the native **"Configure Infobase
  Access Settings"** modal on the UI thread. That modal is a hard block on a headless/agent bind
  (escalated to an autonomy blocker after a 3rd recurrence, 2026-07-15). This explains why passing
  `login`/`password` did not prevent the modal: the credentials were written a few lines too late. The
  fix: when explicit credentials are passed, **prime** the access settings before `associate()` so the
  connect-restore authenticates; the authoritative store still runs after (its skip-if-unchanged fast
  path makes it a no-op flush in the common case, and it correctly re-targets a UUID adopted during
  `associate()`'s setDefault retry). The **no-creds path is untouched** — it keeps the BF-13140
  creds-LAST order (nothing useful to prime; primary durability against a blocked secure-storage flush
  preserved). Complements the synchronous `EDT_DESIGNER_AGENT_AUTH_FAILED` classifier above by removing
  the trigger for the credentialed-bind case rather than only reporting it after the fact.
- Regression-covered by `EdtInfobaseConnectFinishBindOrderTest` (pins store-before-associate for
  explicit creds; associate-before-store for the no-creds and blank-login paths). **Live-validated
  PASS** by infra on a real EDT + credentialed file sandbox (2026-07-15, bus report `4eda6a36`): the
  credentialed bind completed with no UI-thread access-settings modal. The autonomy blocker is closed;
  the item #6 residual (headless auto-suppression of the native dialog on *other* paths, e.g. a later
  `update_infobase` under a genuinely-unauthenticable base) is untouched and stays a parked candidate.
  Ref: `codepilot1c-feedback/2026-07-10-connect-infobase-access-settings-modal-credentialed-file-base.md`
  (§ESCALATION 2026-07-15).

### discover_tools: reflect per-endpoint tool gating instead of an unconditional "now available"

- **`discover_tools` now reports category tools the calling endpoint gates out** under an `unavailable`
  array with `available:false` + a `reason`, marks exposed tools `available:true`, and replaces the flat
  "These tools are now available. You can call them directly" note with an honest per-endpoint one. (this
  commit) The announce (`tools/list`), call gate, and `discover_tools` already share one
  `DefaultMcpToolExposurePolicy` per endpoint, so current tip is self-consistent — the infra observation
  (`discover_tools` claimed `yaxunit_run` available while `tools/call` rejected it) was almost certainly a
  **stale deployed build** (the note could not capture the plugin version). This change makes the tool's
  own response unambiguous going forward so a caller never treats a gated tool as callable. Unit-covered by
  `DiscoverToolsGatingTest`. Ref:
  `codepilot1c-feedback/2026-07-14-yaxunit-run-discover-tools-claims-available-but-uncallable.md`.

### BF-12705 — update_infobase: reject a second concurrent update of the same project (double-fire guard)

- **`edt_update_infobase` now refuses a second concurrent (schema) update of the same project while
  one is in flight**, returning a structured `UPDATE_ALREADY_RUNNING` error (with `in_flight_job_id`
  and a hint to poll `update_infobase_status` instead of re-firing). (this commit) EDT applies an
  update through a **single-connection Designer thick-client session**; a re-fired update on the same
  infobase does not run twice — it collides (`Infobase … is already connected`) and can wedge the
  platform. Live finding (BF-12705, 2026-07-10): a headless caller re-fired an async update while the
  first job was still `RUNNING`; the two Designer sessions contended and the update hung ~51 min with
  no clean terminal state. The guard is a process-wide reservation keyed by project name (static, so
  it is shared across tool instances and matches EDT's per-infobase single-connection reality),
  covering **both** the async and synchronous paths; dry runs spawn no Designer session and never
  acquire it. Slot release is identity-checked so a rejected caller can never evict the real holder,
  and a fast-completing async job can never leak the key. New error code
  `EdtToolErrorCode.UPDATE_ALREADY_RUNNING`; unit-covered by `EdtUpdateInfobaseGuardTest` (5 tests,
  green). NB: this addresses the double-fire *trigger*; the separate question of why the existing 300s
  `UPDATE_JOIN_TIMEOUT_MS` cap did not fire under that contention is tracked in the feedback note and
  is most plausibly a symptom of the double-fire itself.

### Q15 — get_infobase_sync_state (project↔IB readiness probe)

- **New read-only MCP tool `get_infobase_sync_state`.** (this commit) Reports whether an EDT
  project is in sync with its primary infobase (`work_ready`) or needs an incremental
  `update_infobase` (`needs_update`), by exposing EDT's in-memory equality state WITHOUT launching
  a configurator/DESIGNER and without mutating anything. Thin facade over
  `EdtRuntimeService.readInfobaseEqualityState` — the same read that backs `update_infobase`'s
  `skip_if_current` (commit `027d623`). Payload: `equality_state`
  (`EQUAL`/`NOT_EQUAL`/`LOADING`/null), `determinable`, `work_ready`, `needs_update`, `loading`,
  plus an actionable `hint`. Best-effort: an undeterminable state (EDT cold / project or primary
  infobase unresolved / API absent on this EDT build) returns `determinable=false` +
  `work_ready=false` — the unknown case must never be read as "ready". Motivated by the
  post-`Move-TaskStack` "Incremental change required" gap (SLC-1 F-MIG-7): lets an automated runner
  gate a destination project on readiness before running tests. `mutating=false`,
  `category=diagnostics`. Answers master Q15 (2026-07-10).

### BF-13140 — connect_infobase: decouple credential save from the primary commit

- **A blocked/failed secure-storage flush can no longer leave a stale primary pointer.**
  (this commit) In a multi-EDT-instance host (the stack model runs N EDT instances sharing
  the per-user Eclipse secure storage), `connect_infobase(set_primary,force)` could trip
  Eclipse's interactive *"secure storage has been modified by another program"* modal on the
  UI thread while EDT flushed the infobase credentials. Because the credential flush ran
  **before** the `associate()`+`setDefaultInfobase()` primary-pointer commit, a blocked modal
  left the `ibases.v8i` entry and the pool lease correct but the project's **primary pointer
  stale** — breaking headless bind (`Provision-Task` Step-Bind, `Move-TaskStack` rebind_dst)
  which cannot dismiss a modal (live-observed on the SLC-1 stack lifecycle test). The connect
  paths (`file`/`standalone`/`server`) now **commit the primary FIRST**, via a shared
  `finishBind()`, then persist credentials **best-effort**: a failed/blocked flush is reported,
  never thrown, so the bind's primary contract is durable regardless. `storeAccessSettings`
  returns a `CredentialOutcome` and classifies the Equinox contention as the new
  `SECURE_STORAGE_CONFLICT` error code (distinct from the slow-handler / generic case). The
  success payload now carries `credentials_persisted` (+ `credentials_error_code` /
  `credentials_warning` when false) so a caller can tell "bound, creds pending" apart from a
  bare timeout. **Residual (not in this change):** a live modal that no one dismisses still
  occupies the handler until EDT's flush unblocks — the source-level cure is a per-instance
  keyring (`-eclipse.keyring <workspace>\.secure_keyring` on each `1cedt.exe` launch); an
  optional async/bounded-flush follow-up is deferred pending live EDT validation. From the AM
  feedback note `2026-07-09-connect-infobase-secure-storage-modal-blocks-headless-multiinstance.md`.

### BF-13140 — file-IB test-client cred default

- **File-IB test runs default the test-client login to `Admin`/`1` instead of the cached
  last-user.** (this commit) When `yaxunit_run` or `qa_run` launches the 1C test client
  against a **file** infobase with NO explicit `test_client_login`/`test_client_password`
  (and no `VANESSA_TEST_CLIENT_*` env), EDT previously fell through to the `.1CD`'s cached
  last-user — inherited via robocopy from the live `File_am`, often a real employee who
  cannot run tests. The client then silently produced no artifacts (`no_report`: no
  `junit.xml` / `yaxunit.log` / `exitcode.txt`) — the root cause of BF-12562. Both tools
  now detect a file infobase and default to the documented file-IB test account
  (`Admin`/`1`, per `test-runners.md`) with a loud `WARN`, so an omitted credential fails
  visibly-correct rather than silently-wrong. **Server infobases are unchanged**: they keep
  using the association's EDT-stored owner credentials (the intended path), and no server
  test credentials are hardcoded. Explicit `test_client_login`/`password` always win, and a
  partially-supplied pair keeps the existing incomplete-creds warning. New shared helpers
  `EdtRuntimeService.isFileInfobase(projectName)` (best-effort, never throws) and the
  package-visible static `isFileConnectionString(connectionString)` back both tools.

### get_workspace_state — on-demand stack-state snapshot

- **New read-only MCP tool `get_workspace_state`.** (this commit) A thin, non-mutating
  facade over `EdtWorkspaceStateService` that returns THIS EDT stack's live state in one
  call — the same snapshot the periodic state-beacon writes to its shared file, but on
  demand over the MCP port. Payload: `beacon_version`, `plugin_version`, `stack_id`,
  `workspace`, `pid`/`host`/`updated_at`, `port`/`profile`, `endpoints`, `index`
  readiness and (optionally) `bound_infobases`. One optional param
  `include_bound_infobases` (default `true`; pass `false` for a faster identity+index-only
  snapshot). Best-effort like the underlying service — it degrades to a partial snapshot
  and never throws when EDT is cold. Carries the same `ToolMeta`
  (`category=diagnostics`, `surfaceCategory=smoke_runtime_recovery`) as `edt_index_status`,
  so it lands on the same tool profiles. For an external orchestrator's stack-pool
  liveness/coordination checks without opening files or the workbench.

### state-beacon — periodic shared stack-state file

- **Opt-in state beacon + a reusable `EdtWorkspaceStateService`.** (this commit) A
  multi-EDT "stack pool" is coordinated by an external orchestrator; each stack's
  plugin now periodically writes a small JSON *beacon* file to a shared directory
  so the orchestrator can read each stack's liveness and basic state WITHOUT
  calling its MCP port. Two pieces:
  - `EdtWorkspaceStateService.buildSnapshot(includeSlowFields)` assembles a
    best-effort snapshot: `beacon_version`, `plugin_version`, `stack_id` (reused
    from `InfobaseLeaseGuard`), `workspace`, `pid`, `host`, `updated_at`, `port` /
    `profile` (from `CODEPILOT1C_PORT` / `CODEPILOT1C_PROFILE`), `endpoints`
    (name/port/enabled per MCP profile), and `index` (derived-data readiness,
    mirroring `edt_index_status`). With `includeSlowFields` it also adds
    `bound_infobases` (each open project's current-context infobases). Every
    sub-part is guarded — a missing service / cold EDT / no-OSGi yields an omitted
    field or an `index:{ready:false,state:"UNKNOWN"}` placeholder, and the builder
    never throws, so a partial beacon appears before the workspace index is ready.
    The EDT-touching probes are gated by a non-blocking service peek so a cold EDT
    never stalls the beacon on the 30 s service-tracker wait.
  - `EdtStateBeacon` (singleton) schedules a 45 s tick on a single daemon thread;
    the first tick fires immediately (partial beacon). `bound_infobases` is
    gathered only on a slow sub-cadence (every 4th tick). The snapshot is written
    atomically (temp file in the same dir + `ATOMIC_MOVE` with a plain-replace
    fallback, copied from `InfobaseLeaseStore.forceTake`) to
    `<state-dir>/<InfobaseLeaseStore.fileNameFor(stackId)>`. On a clean `stop()`
    the scheduler is shut down and the beacon file is deleted, so a stopped stack
    leaves no stale beacon.
  - **Hard opt-in** (mirrors the lease guard): the state dir resolves from env
    `CODEPILOT1C_STATE_DIR`, then the `edt.state.dir` instance preference, else a
    sibling `edt-state` directory next to the pool's lease directory when the
    lease guard is configured. With none set the beacon is a no-op — ordinary
    single-instance users see zero behavior change. Wired into
    `VibeCorePlugin.start()` (async, after the MCP host) and `stop()`.

### update_infobase — opt-in skip when already current

- **`skip_if_current` skips the update when the infobase already equals the
  project configuration.** New optional boolean param (default `false`, so the
  default path is byte-for-byte unchanged). When set, the tool reads EDT's
  in-memory `IInfobaseSynchronizationManager.getEqualityState(project, infobase)`
  BEFORE any pin/lease/webserver side effect; on `EQUAL` it returns
  `{"status":"skipped","skipped":true,"updated":false,"equality_state":"EQUAL"}`
  with a human message and spawns no configurator. Otherwise the normal update
  runs and the success payload carries `skipped:false` plus the observed
  `equality_state` (`NOT_EQUAL`/`LOADING`, or JSON `null` when EDT could not
  determine it). The equality read is best-effort and reflective (matching how
  the service already binds the sync manager); if `getEqualityState` is absent or
  throws, it logs a warning and falls back to a normal update — the pre-check can
  never fail the call.

### fix — expose skip_if_current via edt_diagnostics

- **`skip_if_current` is now reachable from the MCP surface.** (this commit) F1's
  opt-in equality short-circuit was added to `EdtUpdateInfobaseTool`, but that tool
  is not a standalone MCP tool — it is dispatched only through the `edt_diagnostics`
  composite (`command=update_infobase`). The dispatcher forwards the raw parameter
  map to the delegate verbatim, but its own JSON schema did not declare
  `skip_if_current`; MCP clients strip arguments not advertised on the called tool's
  schema (the same root cause as the 2026-05-29 `launch_app` `dry_run` regression),
  so the flag never reached the delegate and the whole feature was unreachable.
  Declared `skip_if_current` (boolean) on the `edt_diagnostics` schema alongside the
  other update-only pass-through params (`keep_connected`, `async`,
  `kill_agent_mode`, `allow_webserver_running`). No forwarding code changed — the
  delegate already reads it and returns the `skipped`/`equality_state` fields
  verbatim. Guarded by an extended `EdtDiagnosticsToolSchemaTest`.

### MCP host — multi-endpoint profiles

- **`CODEPILOT1C_PORT`: launch-time port override for the forced profile.**
  (`2fdd263`, 2026-07-03) Profile definitions are machine-shared; the port was the
  only per-workspace piece and required a GUI preference step per stack. The new
  env `CODEPILOT1C_PORT` (or `-Dcodepilot.mcp.host.profile.port`, which wins)
  overrides the port of the endpoint forced by `CODEPILOT1C_PROFILE`, so a stack's
  start script fully describes its identity (profile + port + stack id + lease
  dir) with no GUI steps. Ephemeral by design: applied at server start only, never
  persisted into the instance's port map; invalid values degrade to the stored
  port with a warning; ignored (with a warning) when no single profile is forced.

### Infobase leases — consumer-review fixes (stack pool, phase 5)

- **`EDT_LEASE_HELD` from connect_infobase/update_infobase carries a structured
  `holder{}`.** (`5f709e5`, 2026-07-03) Previously the holder was only in the message
  text there (manage_leases already returned it structured), so an orchestrator
  building an escalation command had to parse prose for `holder.stack_id`.
  `EdtToolException` now carries an optional flat details map; the two lease guards
  fill it from the holder lease (`InfobaseLease.holderFields()`) and both tools render
  the nested `holder{stack_id,workspace,host,pid}` + `acquired_at`/`branch`. Closes the
  C1 gap for the enforcement tools (consumer feedback 2026-07-03, Q5).

- **`connect_infobase` reconnect errors carry a machine-readable `retry_with{}`.**
  (`aed451b`, 2026-07-03) The migration re-entry dance — `PRIMARY_EXISTS` (add
  `force=true`) followed by `PATH_ALREADY_ASSOCIATED_AS` (reuse the existing
  binding name) — forced orchestrator scripts to parse hint prose. Both error
  payloads now include `retry_with` (`{"force": true}` / `{"infobase_name":
  "<existing>"}`): merge it into the original arguments and re-issue. A silent
  `reconnect=true` mega-flag was considered and rejected — auto-adopting a
  conflicting name would mask exactly the naming drift that produced duplicate
  `ibases.v8i` rows on the polygon. Also: the `manage_leases take` refusal now
  names the requested infobase, not only the branch, when the conflict is
  identity-keyed. Consumer review 2026-07-03, items A1/C1.

- **Leases are keyed by the canonical infobase identity; the branch is an
  attribute.** (`e85d77d`, 2026-07-03) The leased resource is the PHYSICAL
  infobase: phase branches of one task (which share a single per-task IB) now
  contend for one lease file instead of littering the pool with parallel
  branch-keyed claims, a cosmetically different path spelling cannot dodge the
  guard, and a detached HEAD no longer disables enforcement when the infobase is
  known. A branch hop by the holder on the same IB refreshes the lease payload in
  place; re-pointing a branch at a new IB claims a second lease (the old IB stays
  claimed until released). `manage_leases release` resolves by branch-attribute
  scan and refuses an ambiguous match (pass `ib_path`). Driven by the AM-side
  consumer review of the stack-pool brief (2026-07-03, feedback A5).

- **`update_infobase` checks the lease BEFORE the webserver pre-flight.**
  (`e0d059a`, 2026-07-03) On live pools a web server is always running, so
  `UPDATE_BLOCKED_BY_WEBSERVER` masked the more specific `EDT_LEASE_HELD` from a
  non-holder (live polygon observation; consumer review flagged the order as a
  rollout precondition, feedback A1). New `EdtRuntimeService.checkUpdateLease`
  runs first in both the sync and async tool paths; the in-path enforcement
  before the configurator write is unchanged.

### connect_infobase — reliability (stack-polygon live findings)

- **Retry `setDefaultInfobase` once on a fresh association context.** (2026-07-02)
  Live-observed on EDT 2025.2.x: the FIRST bind into a new git-branch association
  context persisted the association file, yet the immediately following
  `setDefaultInfobase` threw "Project ... is not associated with infobase ..."
  (2/2 repro on fresh contexts; an external re-run of the connect always healed).
  Root cause of the silent history: EDT throws a plain `IllegalArgumentException`
  here — NOT `InfobaseAssociationException` (verified against 2025.2.x bytecode) —
  so the historical wrap-and-rethrow never fired at all. `associate()` now catches
  `RuntimeException`, settles briefly, re-adopts the persisted identity, best-effort
  re-persists the reference, re-`associate`s (tolerating its "Infobase ... is already
  connected" complaint — proof the first associate landed) and retries
  `setDefaultInfobase` once — a persistent failure still surfaces as
  `EDT_SERVICE_UNAVAILABLE`. Regression test: `EdtInfobaseConnectSetDefaultRetryTest`
  (injects the live exception types).
- **Canonical connection-identity matching everywhere.** (2026-07-02) EDT normalizes
  stored connection strings (slash direction, trailing separator, drive-letter case)
  and may re-identify registry rows (different UUID after reload), so the raw
  `equals()` comparison in `findExistingByIdentity`/`adoptExistingAssociationName`
  missed the row the very same call had just added — the retry then hit
  NAME_COLLISION on its own infobase. Identity checks now use the canonical
  `connectionIdentitiesMatch` (previously force-path-only), a UUID mismatch no longer
  short-circuits the connection comparison, and the registry row's UUID is adopted as
  the identity authority over a locally minted one.
- **Assign the infobase UUID BEFORE `add()`; repair legacy `ID=null` rows via
  delete+add.** (2026-07-02) `IInfobaseManager.add()` does not populate the row's
  UUID — the `ibases.v8i` row is written with `ID=null`; `persistReference` then
  assigned a UUID to the in-memory reference only, so every connect minted a NEW
  identity for the same infobase (three different UUIDs observed for one infobase
  across two workspaces and a restart), per-branch associations in different
  workspaces diverged, and association-UUID resolution failed. The row also cannot
  be repaired by direct mutation afterwards — the registry model is transactional
  ("Cannot modify resource set without a write transaction", live-observed). Now
  the UUID is minted before `add()` so the row is stored with a resolvable ID, and
  legacy null-UUID rows are replaced through the manager API (delete + add),
  best-effort. Regression test: `EdtInfobaseConnectPersistUuidTest`.

### Multi-stack pools — infobase lease exclusivity + association surgery (phase 4)

- **Per-branch infobase leases: two EDT instances can no longer work the same file
  infobase.** (2026-07-02) Stack-pool requirement (see the polygon design in
  `stacks\LEASE-DESIGN.md`): tasks = branch + its per-branch IB migrate between EDT
  stacks through a bare git hub, and nothing stopped two stacks from binding/updating
  one IB concurrently. New `InfobaseLeaseStore` (one JSON per branch in a shared
  directory, by convention `<hub>/leases/`; atomic `createFile` claim, atomic-replace
  steal, owner-checked release) + `InfobaseLeaseGuard` decision layer. **Hard opt-in:**
  active only when env `CODEPILOT1C_LEASE_DIR` (or instance pref `infobase.lease.dir`)
  is set — unset means zero behavior change; deliberately no auto-derivation from git
  remotes. Enforcement: `connect_infobase` (all three kinds, before the idempotent
  shortcut) and `update_infobase` (before the configurator writes) refuse with the new
  typed `EDT_LEASE_HELD` (holder + hint in the message) when another stack holds the
  branch — or the same physical IB under another branch (canonical identity match);
  a free lease is auto-taken (bind/update = claim). New `manage_leases` tool:
  status / take (`force` steals, reports the previous holder) / release (`force` drops
  a stale foreign lease). Only branch contexts are leased; detached HEAD/non-git are
  exempt. Tests: `InfobaseLeaseStoreTest` (incl. 16-thread claim race, corrupt-payload
  reads as held), `InfobaseLeaseGuardTest`, `EdtInfobaseConnectLeaseTest`,
  `EdtRuntimeServiceUpdateLeaseTest`, `ManageLeasesToolTest`.
- **`manage_associations` tool: per-branch binding surgery without a checkout.**
  (2026-07-02) list (all contexts with bound IBs, default markers, current-context
  flag) / bind (attach an EXISTING `ibases.v8i` entry to ANY branch's context) /
  copy (replicate one branch's bindings onto another) / dissociate. Grounded against
  2025.2.x bytecode: the git provider builds contexts as
  `InfobaseAssociationContext.of(repo.getFullBranch())` — a public single-segment
  factory — so `refs/heads/<branch>` contexts are synthesizable byte-for-byte
  (`EdtInfobaseAssociationServiceTest` pins the shape). Registry lifecycle stays with
  `connect_infobase` (this tool never creates/repairs v8i rows); bind/copy run under
  the lease guard. Identity helpers extracted to `InfobaseIdentity` (shared by the
  connect service, the association service and the guard).
- **manage_associations: setting a default for a NON-current branch context now works.**
  (2026-07-02, found by polygon scenario 4) EDT quirk, bytecode-verified on
  services.core 21.0.0: the 3-arg `setDefaultInfobase(project, ref, ctx)` validates
  membership against the no-arg `getAssociation(project)` — the CURRENT provider
  context — and uses the `ctx` argument ONLY for the final
  `storeProperty("DefaultInfobase", uuid, ctx)` write, so for any branch that is not
  checked out it always threw "Association does not contain infobase ...". Fixes in
  `setDefaultWithAdoption`: (1) adopt the target context association's own entry
  before the call (ctx-parameterized twin of `adoptExistingAssociationName` — a
  legacy `ID=null` registry row can never match the association entry otherwise);
  (2) when the official API still refuses and the TARGET context's association does
  contain the entry, write the DefaultInfobase property through the manager's own
  private `storeProperty` (reflective) — exactly what the official method does after
  its mis-scoped validation; (3) settle+retry once on the fresh-context read-back
  race (connect-arc pattern). Live-validated: bind into a never-checked-out branch
  lands `Infobases=` + `DefaultInfobase=` under `refs/heads/<branch>`.
  Test: `EdtInfobaseAssociationBindAdoptionTest`.

### MCP host — multi-endpoint profiles

- **Live reload of the shared profiles file + a server-only restart.** (2026-06-29)
  A running server captures its profile snapshot at start and never re-read the
  shared file, so a direct edit of `~/.codepilot1c/mcp-profiles.json` — or an edit
  made in another EDT instance (the file is shared) — only took effect on the next
  full EDT restart, while the preference page preview already showed the new (lower)
  tool count. Two fixes: (1) a background watcher (`McpProfilesChangeMonitor`,
  owned by `VibeUiPlugin`) polls the file and, when it drifts from the live surface,
  prompts to reread + restart the MCP server(s); drift is judged by
  `McpHostManager.signatureOf` vs the last-started signature, so this instance's own
  saves don't nag. (2) A **"Перезапустить MCP-сервер"** action — a button on the
  endpoints preference page and a `1C Copilot ▸ Перезапустить MCP-сервер` command
  (`RestartMcpHandler`) — restarts only the embedded MCP server(s) from saved
  settings, no EDT restart. Backs the "needs EDT restart after a settings change"
  feedback.
- **Profiles are now shared across all plugin instances; only the port is
  per-instance.** (`e58ca92`, 2026-06-29) The profile set — name, tool set, bearer
  token, enabled flag — lives in a per-user file `~/.codepilot1c/mcp-profiles.json`
  (override with `-Dcodepilot.mcp.host.profilesFile` / `CODEPILOT1C_PROFILES_FILE`),
  so every instance/EDT installation of the same user sees the same profiles. Each
  instance keeps only its own port assignment per profile (InstanceScope key
  `mcp.host.profilePorts`); host-level settings (enabled/HTTP/bind/auth/mutation) stay
  per-instance. First load migrates the legacy per-instance profiles (or seeds the
  starter set) into the shared file and records ports. Note: the token now lives in
  the shared file in plain text (was EDT secure storage) — accepted for a
  loopback-bound host.
- **Per-tool picker in the profile editor.** (`e58ca92`, 2026-06-29) The CSV fields
  (enable/disable tools, disable groups, name filter) and the separate preview table
  are replaced by one checkbox tree (groups → individual tools with descriptions),
  with group-cascade tri-state, an "Announced tools: N" counter and a "≥1 tool"
  validation. The endpoints-table Port column is inline-editable (per-instance).
- **`discover_tools` answers strictly per calling port; live tool preview in the
  profile editor.** (`8aa63b6`, 2026-06-27)
- **Endpoints table UI** — add / edit / duplicate / delete / regenerate-token /
  copy-connection / check-status. (`8cf43ec`, 2026-06-26)
- **One EDT serves N MCP ports concurrently**, each a named profile with its own
  port, token and tool set (allowlist/denylist by group + per-tool overrides).
  (`c07f835`, 2026-06-26)
