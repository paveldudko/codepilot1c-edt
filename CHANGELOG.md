# Changelog

All notable changes to the codepilot1c-edt plugin are recorded here.

The format is loosely based on [Keep a Changelog](https://keepachangelog.com/).
Add an entry under **[Unreleased]** for every notable change; on a release, rename
the section to the build/version and start a fresh **[Unreleased]**. Reference the
commit hash in parentheses where useful.

## [Unreleased] — branch `pd/bsl-tuning`

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
