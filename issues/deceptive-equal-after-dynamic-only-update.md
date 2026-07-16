# get_infobase_sync_state can report a deceptive EQUAL after a `dynamic_only` update

> **Status:** OPEN (2026-07-16). Filed off BF-12936 option-2 battle-test; orc `orch-BF-12936`
> GO'd it as a separate, fleet-wide issue (bus ack `ba2e6a1f`). Out of BF-12936 deliverable scope.
> **Severity:** medium — silent-wrong-signal (an operator/agent trusts "EQUAL" and proceeds as if the
> schema physically landed, when it did not).

## Symptom (live, stack-2, project "Accounting management", 2026-07-16)

Sequence infra hit while applying the BF-12936 schema:

1. `update_infobase(timeout_s=1800, kill_agent_mode=true)` → `DONE` in 27s, `updated=true`, **but
   `dynamic_only=true`** with `dynamic_only_reason` = *"Could not acquire exclusive lock; existing
   client/test sessions blocked the update. Schema changes are NOT live."*
2. `get_infobase_sync_state("Accounting management")` immediately after → **`EQUAL`**
   (`determinable=true`).
3. That EQUAL was **deceptive**: the dynamic update could not create the new physical
   tables/columns for `InformationRegister.FinanceVerification` and the `RequireVerified` attribute
   on `Catalog.TMSExportBatch` (those need an exclusive-lock restructure). Only the *later* run
   (`kill_agent_mode=true` killed the phantom → exclusive lock → non-dynamic apply) actually
   materialized the schema, after which EQUAL was genuine (`work_ready=true`).

So between steps 2 and 3 the tool pair said "converged" while the DB schema was, in fact, not applied.

## Root cause (hypothesis, needs confirmation)

`get_infobase_sync_state` derives EQUAL from EDT's `getEqualityState` — a comparison of the DB's
**stored** configuration against the project configuration. A dynamic (non-exclusive) update commits
the config *metadata* to the DB but **defers the physical restructure**. So at the config-comparison
level EQUAL is technically true, while at the "is the schema physically present and usable" level it
is not. `get_infobase_sync_state` has no memory that the preceding update was `dynamic_only`, so it
cannot warn.

This is the same false-EQUAL class the spec-skeptic F3 pass previously flagged (durable note exists,
per orc) — config-EQUAL ≠ schema-physically-applied.

## Why the update side is already honest (so the gap is narrow)

`EdtUpdateInfobaseTool` already emits `dynamic_only:true` + `dynamic_only_reason` ("Schema changes are
NOT live") on both the sync and async paths (`EdtUpdateInfobaseTool.java` ~L304-312 / ~L472-476). The
producer is correct. The gap is purely that a *subsequent, independent* `get_infobase_sync_state` call
re-reports EQUAL with no cross-reference to that just-emitted warning.

## Options (unranked — pick during the fix)

- **(a) Cheapest — documentation/ergonomics.** In `update_infobase`'s `dynamic_only` payload, add an
  explicit forward-warning: *"A subsequent get_infobase_sync_state may report EQUAL even though the
  exclusive restructure did not run — re-apply with an exclusive lock (kill_agent_mode=true / close TC
  sessions) and re-verify work_ready before trusting EQUAL."* Cross-note the same caveat in the
  `get_infobase_sync_state` tool description.
- **(b) Detect a pending restructure.** If the platform/EDT API exposes a "restructure pending" /
  "DB schema differs from stored config" signal, surface it from `get_infobase_sync_state` as a
  distinct field (e.g. `restructure_pending:true`) so EQUAL is qualified. Needs API research
  (`EdtRuntimeService` / `GetInfobaseSyncStateTool`).
- **(c) State-tracking.** Have the plugin remember the last update was `dynamic_only` for a given IB
  and annotate the next `get_infobase_sync_state`. Stateful, most fragile — least preferred.

Recommend (a) now (safe, immediate), (b) if the API supports it.

## Touch points

- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/workspace/GetInfobaseSyncStateTool.java`
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/workspace/EdtUpdateInfobaseTool.java`
  (`dynamic_only` payload)
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/runtime/EdtRuntimeService.java`
  (equality-state source; check for a restructure-pending signal for option (b))

## Provenance

BF-12936 option-2 apply; infra reports `e63c3fb3` / `e3fe521e` (orch-BF-12936 inbox); edt report
`b8bb2b3f`; orc GO ack `ba2e6a1f`. Related memory: `bf12936_feedback_batch_2026_07_16`.
