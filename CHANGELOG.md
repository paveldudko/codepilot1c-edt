# Changelog

All notable changes to the codepilot1c-edt plugin are recorded here.

The format is loosely based on [Keep a Changelog](https://keepachangelog.com/).
Add an entry under **[Unreleased]** for every notable change; on a release, rename
the section to the build/version and start a fresh **[Unreleased]**. Reference the
commit hash in parentheses where useful.

## [Unreleased] — branch `pd/mcp-bridge-lite`

### Subsystem nesting is written on both sides (2026-07-29) — F1

Live check on an EDT-authored configuration (132 `.mdo`: 98 with `<parentSubsystem>`, 22 with
`<subsystems>`) settled how EDT actually stores nesting: **twice**. The parent lists
`<subsystems>WaveChild</subsystems>` by **bare name**; the child carries
`<parentSubsystem>Subsystem.WaveParent</parentSubsystem>` as a **flat FQN**. They are two independent
non-containment references with no `EOpposite`, so EMF maintains neither copy — and `update_metadata`
`set.parentSubsystem` wrote only the child side. The result was a **half-linked** tree: the parent could
not see its child, while the metadata tree, the command interface and our own
`Subsystem.Parent.Subsystem.Child` nested alias all read the parent side.

Both slots now write both sides. Setting `parentSubsystem` adds the child to the new parent's
`subsystems` and drops it from the previous parent's; setting `subsystems` points every listed child at
the parent and clears the pointer of every child dropped from the list; unsetting either slot clears
both. Idempotent by construction — membership is tested before adding, the pointer is only rewritten when
it differs, and an unchanged list is not cleared and rebuilt — so a re-run neither duplicates an entry nor
churns the `.mdo`. Re-running against an already half-linked model **repairs** it, because membership is
ensured even when the pointer already matched. Identity is the flat name, not the Java instance: a value
resolved inside the write transaction is a different handle from the one already in the list.

The far side lives in a **different** `.mdo`, so it needs its own export target and its own EOL snapshot
or nothing reaches disk. A write now reports every co-edited top object to a sink that both extends the
export batch (`forceExportTopLevelObjects`, one `forceExport` call for the whole set) and extends the EOL
guard (`EolGuard.addCoEditedFqn`, snapshotting the far side the moment it becomes known — still ahead of
the export pipeline, since the mutation happens inside the BM transaction). `create_metadata` with a
`parentSubsystem` property is wired the same way.

Left as-is and worth knowing: writing `Configuration.subsystems` (target = the configuration, not a
subsystem) still goes down the generic reference path and does not clear `parentSubsystem` on the
subsystems it lists, so promoting a nested subsystem to the root that way can still leave a half-link.
Pre-existing behaviour, not part of this fix.

### Dotted subsystem FQNs are refused with the right advice (2026-07-29) — F2

The flat form is the only subsystem address guaranteed to resolve, yet the refusal pointed at a dotted
one. `Subsystem.<Parent>.<Child>` failed
with `METADATA_PARENT_NOT_FOUND: Nested FQN segments must be marker/name pairs`, which reads as "you
forgot the marker" and sends the caller to `Subsystem.<Parent>.Subsystem.<Child>` — which fails again,
now as `METADATA_NOT_FOUND`. When the leading type token is a subsystem, the message now names the
**flat** form as the only canonical address (`Subsystem.<Name>` at any depth, because both subsystem
collections are non-containment and every nested subsystem is its own top object) and says to drop the
parent segments rather than add a marker. The general marker/name-pair rule is unchanged for every kind
that owns containment children. The text lives in one pure helper
(`SubsystemTree.nestedFqnRejectionMessage`) shared by the configuration walker and the external-object
walker, and agrees with the wording already in `update_metadata`, `add_metadata_child` and
`edt_metadata_details`.

Note on the second failure: the nested alias is not itself broken — it walks `getSubsystems()`, which was
empty precisely because of F1. On an EDT-authored model it resolves; on a model our own writes had built
it could not.

### `ExchangePlan.content` is writable (2026-07-29) — B2

The registration list of an exchange plan could not be written by any tool. `content` is a containment
reference whose entries are `ExchangePlanContentItem`s — a flat EClass that is neither an `MdObject` nor
named — so every generic child shape missed it: `findNestedChild` skips non-`MdObject` values and matches
on `getName()`, `buildChildOpsFromContainmentSet` requires a `name` per entry and yields no ops without
one, and the containment arm of the reference writer then refused the write outright.

`set.content` now builds one item per requested entry, in either shape:
`content:["Catalog.Foo","Document.Bar"]` (bare FQNs) or
`content:[{mdObject:"Catalog.Foo", autoRecord:"Deny"}]` (`object` and `fqn` are accepted aliases,
`auto_record` too). `autoRecord` defaults to **`Allow`** — the platform default for a new content line —
and an unrecognised value fails loud with the valid literals instead of silently registering everything.
The object slot resolves through the shared reference resolver, which brings FQN resolution, the
compatibility check and the loud `METADATA_NOT_FOUND` along for free, so an unresolvable entry can never
be dropped while the write reports success; resolution runs against
`ExchangePlanContentItem.mdObject`, not against the owning `content` reference, whose own type would
reject every legitimate `Catalog`.

### Exotic `EDataType`s are writable; `thisNode` is refused honestly (2026-07-29) — B3

`convertAttributeValue` knew String / Integer / Long / Double / Float / Boolean / enum and then threw
"Unsupported value type", so **every** exotic model data type was unwritable — `Uuid`, and with it
`QName`, `Shortcut`, `Version`. Each of those is an `EDataType` whose own `EFactory` parses its literal
(`McoreFactoryImpl` declares `createUuidFromString`), so conversion now ends with a generic
`EcoreUtil.createFromString` fallback. This **narrows** the unsupported surface; a data type that cannot
parse its literal is still refused with the same message.

`ExchangePlan.thisNode` is the one field that must stay unwritable, and it is now refused for the right
reason: it is the identity of the plan's own node (`ЭтотУзел`), assigned by EDT on first load, and
overwriting it against a live infobase re-identifies the local node for every peer. It joins `uuid` in an
explicit deny-list at the top of the write path, with a message that says exactly that instead of the old,
misleading "Unsupported value type". **Order is the point:** the deny-list is consulted before conversion,
because the new fallback resolves a `Uuid` literal perfectly well and a guard placed after it would have
quietly made `thisNode` writable. `unset` is denied too — dropping the identity is the same damage as
overwriting it.

### Composite `type.types` is written whole (2026-07-28) — BF-12936 case 1

Asking for a composite type wrote only the first one, with no error anywhere. Three call sites shared one
root: the request normalizer collapsed a list to its first element, and the writer then built a **fresh
single-element** `TypeDescription` and assigned it, replacing any multi-type already there. The same
collapse happened during pre-resolution, so even the type-name pre-pass saw one name. Fixed together —
`update_metadata` on any `BasicFeature` (every register's Dimension, Resource, Attribute), the
`add_metadata_child` create path, and form attributes/parameters/columns — because fixing one would just
move the bug. The form path additionally validated the whole list recursively while applying one element;
now it applies all of them.

The split of raw input into one carrier per requested type moved into a pure, EDT-free `TypeValueSplitter`,
and the assembly into one `buildTypeDescription` that **fails loud on any element it cannot resolve**
rather than skipping it — a silent drop is what this whole entry is about. Each element keeps its own
qualifiers (`String(100)` next to a `CatalogRef`), with outer qualifiers inherited and per-element ones
winning. Every element resolves against the pre-mutation state and the description is assigned once, so a
composite applies atomically. No new tool parameters: the `type` shapes already accepted a list.

The single-type path had to stay behaviourally identical — it is the hot path of nearly every metadata
mutation — so the splitter returns the caller's own object (asserted by identity, maps are not copied) and
branches to synthetic carriers only when more than one type is actually requested; the qualifier blocks
were transplanted line for line, defaults included, and the error texts are unchanged. One intended
delta: a one-element list now yields the element, so an inline qualifier in `["String(100)"]` survives —
without it a list of one would lose what a list of two keeps.

`DefinedType` (and `commandParameterType`) travel a different path that `f184637` had already made
multi-type, and the recon expected it to merely lose qualifiers. It was worse: that path built its type
from a bare query string extracted by a reader that only understands `fqn`/`target_fqn`, so an element
like `{type: "String", length: 100}` resolved to null and was **dropped whole**. Both the qualifiers and
that second silent drop are closed. Qualifier support there is strictly additive — the block appears only
if asked for or already present.

Known adjacent gap, pre-existing and deliberately left: a **map** carrier written as
`{type: "String(100)"}` resolves the type but loses the bracketed length (falling back to 150), because
the inline form is parsed only when the carrier is itself a string. Composites are unaffected (their
carriers are raw strings); changing it would alter the single-carrier path this change was careful to
freeze.

### update_infobase: post-update equality is no longer opt-in (2026-07-28)

The sibling/equality work below made `equality_state` available in the update result, but only behind
`skip_if_current=true` (default false) — and the caller who does not know that flag is exactly the caller
who reads `updated: true` as "the infobase now matches" and then runs tests against stale code. The
result now always carries `equality_state_after`, read after the apply with the same in-memory EDT query
as the pre-check (no DESIGNER spawned, advisory only: an unreadable state omits the field rather than
inventing a verdict). `equality_state` keeps its old meaning — the opt-in PRE-check — so nothing that
already parses it changes. When the apply reported success yet the state is still `NOT_EQUAL`, the payload
names the documented non-convergence mode and says outright that re-running the same update will not
converge, instead of leaving the caller to issue a second call and guess. The dynamic-only case stays
quiet here because `dynamic_only_forward_warning` already carries the richer explanation.

### Live validation of the 2026-07-28 wave on build `0.1.7.20260728-1704`

Validated against a real EDT (sandbox workspace, `plugin_version` confirmed through the beacon), not only
unit tests. Confirmed live: the DCS main schema reaches disk (`match=true`, `attached=true`,
`dataSourceSeeded=true`, `Template.dcs` present with a seeded `<dataSource>`); `runtime_used` with
`candidates_tried` on `dry_run` and `reject_reasons` plus a loud `runtime_not_resolved` on an unmatchable
version; zero executed tests yield `status=no_tests_matched` with a `reason` (`infobase_stale` takes
precedence over `filter_matched_nothing`, with the filter echoed back) instead of a green verdict; red
tests return on the success channel as `status=tests_failed`; `equality_state` and `preflight_warnings`
ride along in the run result; a foreign `1cv8c` was **spared** with an explicit warning naming its PID,
proving the three-way orphan-kill guard (thin client **and** `RunUnitTests=` marker **and** infobase
match) holds in the presence of other stands' clients; the shared-infobase fan-out fields on the read
path; `edt_metadata_details` resolving a `Subsystem` (no more false `exists:false`); and `adopt_existing`
refusing to hijack an already-registered object.

Two findings the wave did not deliver, corrected here rather than left in the changelog as done:

* **Nested subsystem addressing.** The flat canonical form works, but no dotted form resolves at all —
  neither `Subsystem.<Parent>.<Child>` (`METADATA_PARENT_NOT_FOUND`) nor the marker/name pair
  `Subsystem.<Parent>.Subsystem.<Child>` (`METADATA_NOT_FOUND`). The tolerant alias the recon planned is
  not in the build, so the schema texts for `update_metadata`, `add_metadata_child` and
  `edt_metadata_details` document the flat form as the only one and tell the caller not to build a nested
  one. The `must be marker/name pairs` message is actively misleading for subsystems — it points at a form
  that also fails.
* **`edt_metadata_details` answered a nested FQN with the wrong object.** It read the leading
  `<Type>.<Name>` pair out of a longer dotted FQN and returned whatever that resolved to, so
  `Subsystem.<Parent>.<Child>` came back carrying the **parent's** properties under the requested path —
  a silent wrong answer, strictly worse than the `exists:false` this same method was fixed to stop
  emitting. A longer FQN is now a miss with a message that names the supported form (flat for subsystems,
  child objects out of scope) instead of a confident lie.
* **Half-linked nesting.** `set.parentSubsystem` writes only the child side. EDT itself writes nesting on
  both: `<subsystems><Child></subsystems>` (bare name) on the parent and
  `<parentSubsystem>Subsystem.<Parent></parentSubsystem>` on the child, so after our call the parent does
  not list the child.

Not reproducible locally, delegated to the stacks that hold the fixtures: the `qa_run` BDD suite (no
`qa-config.json`/`.feature` in the sandbox clone) and the `adopt_existing` orphan path (the two-index
desync cannot be synthesised). Also recorded: in auto mode `candidates_tried` lists only the installations
actually tried, so "rejected" and "never enumerated" stay indistinguishable — on this box `8.5.1.1302` is
installed, auto picked `8.3.27.2074` and tried nothing else, while an explicit `runtime_version=8.5.1`
resolves it, which points the pre-release autoselect reports at a project pin or EDT's preferred runtime
rather than at the resolver preferring the newest install.

### create_metadata adopt_existing (2026-07-28) — BF-13405: exists:false and "FQN already in use" for the same object

There are two independent metadata indexes. **A** is the configuration composition: the flat typed lists
in `Configuration.mdo`, which in EMF are non-containment `refers X[]` references on `Configuration`.
**B** is the BM top-object FQN registry: every `.mdo` on disk is imported as its own top object and its
FQN registered in the namespace regardless of whether `Configuration.mdo` ever mentioned it.
`create_metadata` consulted A (`existsTopLevel` said "absent"), then created and called `attachTopObject`,
which consults B ("occupied"). Hence the reported pair: `edt_metadata_details` → `exists:false`,
`create_metadata` → FQN already in use, `update_infobase` → "Unknown metadata object". The BM state is
not corrupt — the object is loaded and attached; exactly one thing is missing, the entry in
`Configuration.<collection>`.

It surfaced as `EDT_TRANSACTION_FAILED` because `BmFqnAlreadyInUseException` and
`BmNameAlreadyInUseException` are siblings with **no common base class** and only the latter had a catch
arm, so the FQN clash fell into the generic `RuntimeException` handler. Both `executeWrite` and
`attachTopLevelObject` now translate it to `METADATA_ALREADY_EXISTS` with text that names the
disagreement and the cure — an honest backstop that stands on its own, independent of the verb below.

`create_metadata` now probes index B before creating (`getTopObjectByFqn`, best-effort: a probe failure
degrades to null and creation proceeds as before). On a hit, the default — `adopt_existing` unset — is a
loud, actionable refusal that changes nothing and offers both exits: adopt, or delete the `.mdo`
directory. With `adopt_existing=true` the existing top object is registered into its typed collection
and nothing else: strictly additive, one entry in one list, no `createTopLevelObject`, no re-attach, no
uuid rewrite. This is the mirror image of the `rights_manage` defect (`c990eaa`): there an object was
reachable by model reference but was not a resolvable top object; here it IS a resolvable top object but
is not reachable from the configuration. The method transfers, not the direction — do not trust one
index, probe the other before acting. Because the registration is additive to a single collection, it
also closes the "Configuration.mdo flat-list registration conflict" report (a git merge had dropped
`<roles>` entries) without the risk of a `changes.set.roles` rewrite of ~1300 entries.

Adoption never mutates the object it registers: `properties` passed together with `adopt_existing=true`
are **refused** (`INVALID_METADATA_CHANGE`, pointing at `update_metadata`) rather than silently dropped,
and a FQN held by an object of another EClass is refused too instead of blowing up in the typed cast.
The result carries the distinction: a new `CreateMetadataOutcome` adds `adopted: true|false` and
`registered_into: Configuration.<collection>` to the tool output, leaving the shared
`MetadataOperationResult` that other tools consume untouched.

Contract changes: new optional `adopt_existing` (alias `adopt`), default false; it must be passed in the
`edt_validate_request` payload too — the token payload is authoritative for every field of a mutation, so
a token issued without the flag is refused with `INVALID_VALIDATION_TOKEN` rather than quietly downgraded,
and `edt_validate_request` now always echoes `adopt_existing` in its normalized payload. `create_metadata`
output gains two lines. An FQN clash that used to read `EDT_TRANSACTION_FAILED` now reads
`METADATA_ALREADY_EXISTS`, so playbooks branching on that code change path.

**Caution for live validation:** `rebindTopLevelIntoConfiguration` removes the object from
`Configuration.getContent()` by name. In an EXTENSION project `<content>` is meaningful (it lists adopted
base objects), so adoption there could strip a legitimate entry. BF-13405 is a base configuration and the
same removal already ran on every create, but do not exercise adoption on a live extension first.

### Subsystem FQNs and the kind→collection map (2026-07-28) — nested subsystems were unaddressable, half the kinds reported exists:false

A subsystem's canonical EDT FQN is FLAT at every nesting depth — `Subsystem.PaymentCalendar`, never
`Subsystem.Finance.Subsystem.PaymentCalendar`. Both subsystem collections (`Configuration.subsystems`
and `Subsystem.subsystems`) are non-containment, so a nested subsystem has `eContainer() == null`, and
`MdUtil.getFullyQualifiedName` falls back to a two-segment name when `eContainingFeature()` is null;
each nested subsystem is a top object with its own `.mdo`. So the flat form the caller wrote was
canonically CORRECT and still failed: `findTopLevel` resolved SUBSYSTEM through
`configuration.getSubsystems()`, i.e. the first level only. The nested spelling failed too, in a
different place — `findNestedChild` iterates containment references, and `subsystems` is not one.

Resolution is now recursive (new pure `SubsystemTree`, generic over a node accessor so the traversal is
unit-testable without EMF, with an identity-based visited set because a non-containment link can cycle).
A name shared by two subsystems under different parents is **refused, not resolved**: a flat FQN cannot
address one of them, so the error names every colliding parent and points at the nested alias, instead of
silently returning whichever the traversal reached first. The nested chain is accepted as a tolerant
alias, deliberately narrow — only the `subsystems` feature of a `Subsystem` — because `Subsystem.content`
is also a non-containment many reference and a generic rule would turn every content member into an
addressable child.

The same fix removes a whole family of false negatives. The kind→collection mapping existed in FOUR
divergent copies, and every kind absent from a copy silently became "does not exist" for whatever read
it: `EdtMetadataInspectorService.findMdObjectByFqn` carried a nine-kind switch with
`default -> List.of()`, so `edt_metadata_details` answered `exists:false` for Subsystem, Role,
ExchangePlan, DefinedType and every register beyond information/accumulation — objects that plainly
existed. All four copies are now one `TopLevelCollections`: `forKind` (typed getters, exhaustive switch,
so a kind added to the enum is a compile error rather than a silent gap), `configurationTag` and
`indexScopeToken`. `existsTopLevel` collapses from 48 arms to one line, `scan_metadata_index` from 48
hand-written calls to a loop over `MetadataKind.values()`, and the inspector derives its kind through
`MetadataKind.fromString`, so plural and Russian type tokens work there too. The index token stays a
separate mapping on purpose: `scan_metadata_index` has always reported `chartofaccounts` (singular
"chart") and its scope-alias table keys off that spelling. The 47 non-subsystem getters, all 48
`.mdo` tags and all 47 index token→getter pairings were diffed against the previous switches to keep the
refactor byte-identical where it must be.

Contract changes: `Subsystem.<Name>` now resolves for nested subsystems in `update_metadata`,
`add_metadata_child` and `edt_metadata_details` (was `METADATA_NOT_FOUND` / `exists:false`); the nested
chain resolves too; `scan_metadata_index` now lists nested subsystems, so totals and `scope=subsystems`
grow; `edt_metadata_details` returns real data for ~39 kinds that used to report `exists:false`; and a
duplicate subsystem name — previously first-match-wins — is now a loud `METADATA_ALREADY_EXISTS`, which
can turn a call that "worked" on such a configuration into a refusal. `create_metadata kind=Subsystem`
also now refuses a name already taken by a NESTED subsystem, which is correct: it occupies the same flat
FQN in the BM namespace.

### yaxunit_run (2026-07-28) — the runtime resolution was invisible, and the version parameter had the wrong name

`resolveThinClientFile` returned a bare `File` and had two failure branches, both blind: one returned
`null` without logging a single line, the other logged a warning only into the plugin log while the
caller surfaced the generic `Thin client (1cv8c.exe) runtime component not resolved`. `launch_app` and
`update_infobase` have reported `runtime_used` since `b8ad7c7`; `yaxunit_run` reported nothing, so a
resolve-fail cost the owner four blind install rounds and the request to print the chosen version and the
paths tried stood open from 2026-07-03.

The resolution is now a record — `ThinClientResolution` with `file`, `versionWithBuild`, `location`,
`source`, `candidatesTried` and `rejectReasons` — and BOTH failure branches log the whole audit trail.
A dedicated `ThinClientNotResolvedException` carries it, so a tool can render the structure instead of a
sentence; `resolveThinClientFile` stays a null-returning delegate, so nothing existing changed shape.
`yaxunit_run` reports `runtime_used {version, location, source, requested, binary}` plus
`candidates_tried` / `reject_reasons` on every outcome — success, failure, and `dry_run`, which is the
only cheap way to ask which client would be launched without spending a 300-second run. An unresolvable
runtime is now `status="runtime_not_resolved"` with the candidates and the reason each one lost, not an
opaque string.

The version parameter was also the odd one out: `launch_app`, `update_infobase` and `connect_infobase`
all take `runtime_version`, this tool took `version_mask` — a trap for the calling model rather than a
cosmetic inconsistency. `runtime_version` is now the primary name, `version_mask` a synonym (on a
conflict `runtime_version` wins and the loser is echoed back as `ignored_version_mask`). Priority is the
same chain `launch_app` applies: explicit parameter → the project's `.launch` pin (`USE_AUTO=false`) →
project+infobase auto-resolution, so the two tools can no longer resolve to different platforms for the
same project. Reading the pin is best-effort and never fails the run.

### thin-client resolution (2026-07-28) — a client launch asked EDT as if it were an infobase update, and took a single newest-wins shot

`InfobaseAccessType` has exactly two values and every path passed `UPDATE`, including all four thin-client
launches. Decompiling `ResolvableRuntimeInstallationManager` shows the access type selects the version
compatibility filter (`filterUpdateVersionCompatible` vs `filterClientLaunchVersionCompatible`), so a
platform the EDT launch UI had selected for launching a client could be filtered out when asked for under
`UPDATE`. Client launches now ask under `CLIENT_LAUNCH`; the infobase update path keeps `UPDATE`
(`resolveInstallation` grew the parameter, the old overload still defaults to `UPDATE`, so
`update_infobase`, `import_project` and the thick/designer launch paths are untouched).

The second half was the single shot. `resolveByProjectAndInfobase` is newest-wins, and its compatibility
filter goes through `getCompatibilityModeIfStarted(IV8Project)` — an empty `Optional` on a project whose
DT model has not started, i.e. the filter silently switches off and the newest installed platform (a
pre-release) wins. Worse, one bad pick sank the whole resolution: an installation registered in
Preferences whose directory is gone resolved to a path that does not exist. The resolver now walks
candidates from `findUsefulForProjectAndInfobase(..., CLIENT_LAUNCH)` (falling back to the full registry,
then to EDT's single shot) and accepts the first whose `resolve` + `resolveExecutor` yields a `1cv8c.exe`
that is actually on disk, recording why each loser lost. A ghost registry entry now costs one line of
diagnostics instead of the run.

There is no "pre-release" flag to filter on — `RuntimeInstallation` exposes only version, build,
location, arch and isTraining — so the beta is de-selected by ordering: an explicit mask, then the
EDT-stored per-project+infobase pin (read straight from `IInfobaseAccessManager`, because EDT itself drops
the pin when the project version cannot be calculated on a cold project), then the version the infobase
is bound to, then newest. An explicit mask keeps its old semantics exactly: EDT's own
`resolveByVersionOrMask` answer remains the first candidate and only other builds of the same requested
line queue behind it, so a caller's pin is never silently substituted.

**Touches the live-validated `qa_run` arc — both spawn paths resolve the thin client here, so the BDD
suite (7/7) must be re-run live before this is relied on.** Deliberately NOT extended to `launch_app`'s
thick/designer paths: they are client launches too, but switching them would widen the re-validation
surface beyond what the diagnosis establishes. The parameter now exists, so that is a one-token change
later. The CompatibilityMode line was also left out of the preference chain on purpose — the only public
route (`IV8Project → getConfiguration() → getCompatibilityMode()`) triggers a BM configuration load
exactly on the cold-project case this fix targets.

### dcs_manage create_schema (2026-07-28) — the schema was never attached to the BM, so nothing reached disk

`DcsFactory.createDataCompositionSchema()` was assigned straight into `template.setTemplate(schema)`.
`BasicTemplate.template` is a **transient, non-containment** reference — the same flags as `Role.rights`
and `BasicForm.form` — and the schema is a SEPARATE top-object serialized into
`Templates/<name>/Template.dcs`. So the freshly created schema was an orphan: the model accepted the
write, `attachTopObject` was never called in any branch, and the tool reported success with no artifact.
Exactly scenario "b" of the `rights_manage` defect (`c990eaa`), and the repo already documented the
failure text in the `attachBootstrappedRoleDescription` javadoc.

`createMainSchema` now attaches the schema as an external top-object, in the order the FQN generator
requires: `setName` + `setTemplateType(DataCompositionSchema)` + insertion into the owner's `templates`
list FIRST, only then `generateExternalPropertyFqn(template, BASIC_TEMPLATE__TEMPLATE)` — decompiling
`MdTopObjectFqnGeneratorDelegate` shows it appends `capitalize(reference.getName())` to the owner's
qualified name and throws a bare `AssertionFailedException` when that name is null, which is what a
template outside the container chain yields. Then: BM namespace, a defensive `getTopObjectByFqn` reuse
probe, `attachTopObject`, a re-read of the attached object from the transaction, and only THAT object
written into the reference. The generation call is wrapped so the failure surfaces as an actionable
message instead of a multi-line assertion dump.

Four defects of the same tool that would each have produced a second round are fixed with it.
**No force-export:** every `EdtMetadataService` mutator follows its transaction with `forceExport` plus a
derived-data flush; the DCS path did nothing, and since the schema is its own file, exporting only the
owner writes `Report.mdo` and leaves the schema unwritten — so `create_schema` and all three upserts now
export the owner AND the schema FQN in one batch (new `DcsExportSupport`, a deliberate copy of the
metadata-service plumbing rather than a hoist that would touch ~30 hot call sites).
**No EOL guard:** the path rewrites `Report.mdo`, which the BM serializer emits as CRLF regardless of the
file's convention; all four mutators now snapshot and restore per-file EOL, with `dcs` added to the
managed extensions. **Duplicate `<templates>`:** the old code checked "does a schema exist", never "is
the NAME taken", so `force_replace` only ever appended — the requested name is now matched
case-insensitively and `force_replace` REPLACES (reuses the template, resets the schema content).
**Empty schema:** `createDataCompositionSchema()` produces no `dataSource`, while every real `.dcs`
carries `<dataSource><name>DataSource1</name><dataSourceType>Local</dataSourceType></dataSource>`
(`dataSourceType` is a plain string, not an enum) — one is now seeded.

The result no longer claims success blind: it reports `schemaFqn`, `schemaExternalFqn`, the expected
`src/<Folder>/<Owner>/Templates/<Name>/Template.dcs`, whether that file actually exists, and a warning
when it does not. A schema present in the model but missing on disk — the reported state — triggers a
repair export and a re-probe rather than another artifact-free success. `[dcs]` diagnostics log the
generated external FQN against its expected shape, the `getTopObjectByFqn` verdict, the export target
list and the file probe, so a single install round confirms or refutes the fix by itself.

Contract change: `force_replace=true` now replaces instead of adding, and with no same-name template it
rebinds the owner's existing DCS template rather than creating a second one (the `template_name` default
is materialized into the validated payload, so an explicitly requested different name is not
recoverable downstream — fixing that properly means `normalizeDcsCreateMainSchemaPayload` must stop
defaulting and pass null through). A same-name template of another `templateType` is refused without the
flag and converted with it, detaching the foreign top-object from the FQN slot — destructive, but
explicitly opt-in and log-warned. All four DCS mutators now block on the export pipeline (default 120 s)
and can fail where they previously returned success immediately; that is the point, but it is a latency
and failure-mode change for callers.

Tests: `DcsSchemaSupportTest` (17), `DcsCreateMainSchemaRequestTest` (7),
`DcsMainSchemaPersistenceContractTest` (21 source-contract pins on the operation order, the extraFqn
export, the EOL guard and the honest state). First DCS tests in the repo. `attachTopObject` itself and
the `.dcs` appearing on disk stay live-only.

### bsl_list_methods / bsl_module_exports (2026-07-28) — doc-comment `См.` / `See` links are now reported, and the incoming premise was wrong

The report said "EDT resolves a doc link one hop only". **That premise is wrong, and the correction matters more
than the feature.** Decompiling `com._1c.g5.v8.dt.bsl.comment` (2025.2.3) shows
`BslDocumentationComment.computeReturnTypes` / `computeParameterTypes` recursing into the linked comment and
guarding loops with an `alreadyProcessingMethods` set — the resolution is already fully transitive. It only
*looks* like a single hop because the recursion is gated on the intermediate comment being a bare link
(`lastPart instanceof LinkPart && returnSection == null && parametersSection == null`): the first hop that
declares its own `Параметры:` / `Возвращаемое значение:` section wins outright and its own link is never walked.
There is nothing to fix in the resolver and nowhere to add a transitive walker — **do not write one.** Our own
repo, meanwhile, had no `See` handling at all — zero hops, not one: `BslSemanticService.extractDocumentation`
only scraped contiguous `//` lines.

What was added is reporting, not resolution: new dependency-free `BslDocSeeChain` in `core/edt/lang` exposes
`seeTarget` plus the in-module `seeChain` (depth 8, visited-set seeded with the start method, mirroring EDT's
own guard) with `seeChainTruncated` / `seeChainCrossModule` on `BslMethodInfo`. Rules were taken from the
decompiled source rather than guessed: the keywords are exactly `см.`/`see`, the link-text terminator set is
identifier chars plus `.` `:` `/`, the bracketed `(См. X)` form extends to the `)`, `@`-tag lines are not
scanned, and only the parameters/returns headers break a chain — `Пример:`, `Варианты вызова:`, `Устарела.`
end the description but not the chain. The module index is built from **all** module methods, not the exported
subset, because a chain routinely hops through a filtered-out helper. All four fields are omitted from the
payload when absent, so an ordinary method serializes byte-for-byte as before.

Three EDT parsing quirks that silently kill a chain are now pinned by tests and documented instead of being
normalised away (normalising would claim a resolution EDT does not perform): `См. МойМетод.` dangles, because
`LinkPart.computePartsWithOffset` splits on `.` keeping the empty tail; `См. также Модуль.Метод` links to
`также`; `См. Модуль.Метод()` stops at `(` and leaves `()` as trailing text, which unseats the link.
One deliberate deviation from EDT: the keyword must start at a word boundary, so `Просм.` / `Foreseen` are not
matched — EDT's raw `indexOf` does match them, and the boundary only removes false positives.

Tests: `BslDocSeeChainTest` (31 hermetic cases — both languages, case-insensitivity, link not last, cycles,
self-reference, depth truncation, cross-module, trailing period) + two serialization pins in
`BslSemanticToolsContractTest`. The contract sentence is single-sourced in `CONTRACT_HINT` so the two tool
descriptions cannot drift, with the full explanation in `knowledge/edt-gotchas.md`.

### get_diagnostics (2026-07-28) — review annotations from a sibling plugin were indistinguishable from EDT diagnostics

`collectFromMarkers` reads `file.findMarkers(null, true, DEPTH_ZERO)` — **every** marker type, subtypes
included, no allow-list (same on project scope with `DEPTH_INFINITE`). The sibling commit-review plugin
contributes `com.dudko.edt.review.commentMarker` (a `org.eclipse.core.resources.textmarker` subtype,
`persistent=true`) and never sets `IMarker.SEVERITY`, so `getAttribute(SEVERITY, -1)` returned `-1`,
`Severity.fromMarkerSeverity` mapped the default branch to `INFO`, the entry passed the default
`severity=info` threshold and landed in the diagnostics list with **nothing to tell it apart** — the
provenance the record already carried (`markerType` / `source`) was printed only under the
diagnostics-verbose debug gate.

Fix is a provenance label, not a type allow-list (an allow-list would silently lose newly contributed EDT
marker types). New dependency-free `DiagnosticOrigin` in core classifies `compiler` | `analyzer` |
`custom-check` | `review-annotation` | `unknown` from `(markerType, source, severityDeclared,
textMarkerSubtype)`; `EdtDiagnostic` gains an `origin` component filled by all four factories; the marker
loops classify **before** the severity gate and the dedup key, so foreign markers never enter `seen` or the
soft scan budget. `origin` is now rendered outside the debug gate for everything that is not
`compiler`/`analyzer` (collapsed groups carry an `[origin: x]` tag).

**Behavioural change:** the default answer is now clean diagnostics — new `origin` param defaults to
`diagnostics`, which excludes `review-annotation`; `origin=all` brings them back in a separate
`### Review annotations` section, and a single origin (or a comma list) narrows further. Review entries are
**never** counted in `errorCount`/`warningCount`/`infoCount`, even with `origin=all` — a review comment is
not an error/warning/info. Platform `taskmarker`/`bookmark` are deliberately exempt from the
severity-less-textmarker rule (they classify as `unknown` and keep flowing) so TODO markers do not silently
disappear.

Also removed a stray NUL byte inside a string literal in `EdtDiagnosticsCollector` (line 240 group-key
separator) which made ripgrep treat the whole file as binary and skip it in searches.

Tests: `DiagnosticOriginTest` (19 — rules + filter semantics), `DiagnosticOriginWiringContractTest`
(6 source-contract pins: origin in the record, origin rendered outside the debug gate, every
`findMarkers(null, …)` classifies + honours the filter, counters skip review, no NUL byte),
`GetDiagnosticsToolSchemaContractTest` (3 — the UI tool's schema parses and declares `origin`; MCP clients
strip undeclared params). Live validation pending.

### yaxunit_run (2026-07-28) — `total=0` read as "all tests passed"; red tests move to the success channel

`buildResult` decided green as `failures == 0 && errors == 0 && exitCode in {null, 0}` — `report.tests` was
never in the predicate, so an empty report satisfied it identically and a run that executed **zero** tests
returned `status: "passed"` on the success channel. Reachable in practice: `QaJUnitReport.parseDirectory`
returns non-null for any `*.xml` in the run dir and `getIntAttr` defaults a missing `tests` attribute to 0,
so both `<testsuites/>` and `<testsuite tests="0">` produce `report != null, tests == 0`.

The fix fixes the channel contract, not just the predicate: **the error channel means "there is no verdict —
draw no conclusions", the success channel means "there is a verdict — read the report"** (clients reliably
branch only on `McpHostRequestRouter`'s `isError = !result.isSuccess()`; the existing `preflight_warnings`
were walked past in the 07-14 incident). Two coupled consequences:

- A completed run with RED tests is now a **success** with `status: "tests_failed"` (was an error whose text
  was the JSON envelope) — closes feedback `2026-07-03-yaxunit-run-red-tests-as-mcp-error.md`, which showed
  6 of 10 "failures" in the weekly window were normal TDD iterations. Precedent: `qa_run` already returns
  `tests_failed` as success and `no_features`/`feature_not_found` (zero resolved work) as failure.
- A run that executed **zero tests** is now an **error**: `no_tests_found` when no filter was passed
  (`reason: no_tests_in_infobase`, pointing at the extension being missing/safe-mode), `no_tests_matched`
  when a filter was passed, with the cause split by EDT's equality state —
  `infobase_stale` (NOT_EQUAL/LOADING → run `update_infobase`), `filter_matched_nothing` (EQUAL → check
  `Модуль.Метод`, the `ИсполняемыеСценарии` registration, the `extensions` filter) or `no_match_unverified`
  (state unreadable on a cold EDT → both hints, stale first). The old vague `status: "failed"` is gone;
  exit≠0 with a green report is now `report_exit_mismatch` (error, inconclusive).

Mechanics: the decision is a pure `static Verdict classify(report, outcome, filterPresent, equalityState)`
(`record Verdict(status, reason, message, ok)`); `buildResult` only renders it and picks the channel.
`EdtRuntimeService.readInfobaseEqualityState` is read in preflight (best-effort, never throws) and reported
as `equality_state` on every run — including green ones, where it is the only cheap signal that the tests
ran against stale code; NOT_EQUAL/LOADING also raises a `preflight_warnings` entry.
**Read `equality_state` on every run, not only when something looks wrong.** A green run against a stale
infobase is the one failure mode this tool cannot detect for you: the tests really did pass, just not
against the code you just wrote. `equality_state` is the cheapest available proof that they did — checking
it always is strictly better than reaching for `get_infobase_sync_state` after a result looks suspicious. `getDescription()` and
the schema now carry the input decision ("run update_infobase after editing .bsl") instead of output-format
prose; the empty-log detail moved to `resources/knowledge/edt-gotchas.md`.

Tests: `classify_*` (8, incl. the ok=true pin for red tests) and `classifyNoReport_*` (3, previously
uncovered) in `YaxunitRunToolTest`. Live validation pending.

### yaxunit_run (2026-07-28) — the run dir is also the client's CWD, so any stray `*.xml` became "the report"

`QaJUnitReport.parseDirectory` walked the whole directory for `*.xml` and summed everything it found. For
`yaxunit_run` that directory is also the thin client's working directory (`processBuilder.directory(runDir)`),
so any unrelated XML dropped there was silently counted as the jUnit report. New overload
`parseDirectory(dir, maxFailureDetails, preferredFileName)`: with a preferred name only files with that name
are parsed; when it is absent but other `*.xml` are present the legacy scan still runs but sets the new
`fallbackScan` flag, which `yaxunit_run` surfaces as `report_source` (`junit.xml` | `fallback_xml_scan`) plus
a `report_source_note` — back-compatible, never silent. A fallback scan that yields 0 tests is routed to the
existing `no_report` diagnosis so the richer safe-mode hint is not lost. `qa_run` keeps the 2-arg call: its
`junit` directory is dedicated and Vanessa writes one file per feature there, so nothing changes for it.

Tests: `reportParsesEmptyRootAsZeroTests`, `reportParsesZeroTestSuiteAndMissingTestsAttribute`,
`reportPrefersJunitXmlOverStrayXmlInTheRunDir`, `reportFlagsFallbackWhenOnlyStrayXmlIsPresent`.

### qa_run (2026-07-28) — a report with zero executed tests no longer reads as `passed`

Same defect class as `yaxunit_run`: `status = (failures + errors) > 0 ? "tests_failed" : "passed"` never
looked at `report.tests`, so a parsed-but-empty jUnit report surfaced as a pass. A finished run whose report
contains 0 executed tests is now `status: "no_tests_executed"` with a `message` (scenarios filtered out,
Vanessa aborted before the FeaturePlayer, or the infobase does not match the EDT source) and it travels on
the **error** channel (`QA_RUN_ERROR: the run executed 0 tests` + the full envelope) — consistent with
`no_features`/`feature_not_found`, which already fail for zero resolved work. Red tests keep their existing
success channel with `tests_failed`; `timeout`/`infra_error`/`update_failed` channels are unchanged.

### yaxunit_run (2026-07-28) — orphaned `1cv8c` is now reaped per-infobase instead of warned about globally

The preflight scanned **every** process on the machine for the substring `1cv8c` with no project/infobase
binding and only warned: on a multi-stand box that fires on every run and cannot tell our leaked client from
a neighbouring stand's legitimate one. And `terminateProcessTree` never cleaned the leak up, because the real
`1cv8c` is not a descendant of the process we spawn — the launcher reparents it, and the incident logs show
`descendants=0` on every heartbeat — so the orphan has to be found by command line and killed by PID.

New `InfobaseProcessScanner.killLeakedTestClients(ibPath)` returning
`TestClientCleanup(killed, spared, unreadable)`, reusing the existing WMI command-line overlay (mandatory —
`ProcessHandle.info().commandLine()` is empty on Windows). The kill predicate is triple-gated: the process is
a thin client (`1cv8c`), it carries **our** `RunUnitTests=` startup parameter (an interactive session never
does), and its command line references **this** infobase (`matchesIb`, boundary-checked). Anything that
cannot be attributed — a server infobase, an unresolved association, or an unreadable command line because
WMI is unavailable — is reported loudly with its PIDs and left running: killing without an infobase match
could take down another stand's client.

The gate encodes a rule worth reusing wherever this plugin destroys something: **what you could not
attribute, you do not destroy — you report it.** An unreadable command line is not permission to guess; it
is the reason to stop and name the PIDs so a human can decide. Note the asymmetry that makes this cheap:
failing to reap a leaked client costs one warning and a retry, while killing a neighbouring stand's client
costs someone else's session with no way to tell them why it died. `fileIbPath` is now public (the binding key is needed from
`tools.qa`); the preflight warning is scoped to this run instead of the whole machine.

Tests: `isLeakedTestClient_*` (our runner on the target IB / the same runner on another IB / an interactive
client of the target IB / unknown IB path / thick client and designer), `isThinClientAndUnitTestRunner_areNullSafe`,
`hasReadableCommandLine_falseWhenOnlyTheExeIsVisible`. The `ProcessHandle` scan/kill itself is
environment-dependent and not unit-covered; live validation pending.

### BF-13330 (2026-07-28) — dynamic list: `customQuery` / `queryText` / `mainTable` are finally authorable

Switching a dynamic list from auto to a custom query had no working path. `set_item set:{customQuery:true}`
failed with `Unknown form property: customQuery`, `apply_form_recipe attributes:[{set:{customQuery:…}}]` failed
identically, and `type:"DynamicList"` failed with the opaque `Type not found in BM: DynamicList`. The property
was never missing — it lives one level deeper, on the attribute's `form:DynamicListExtInfo`, and the generic
feature resolver (`resolveStructuralFeatureIgnoreCase`) only ever inspects `target.eClass()`. `set_item` was a
dead end for a second reason: it resolves form ITEMS, whose ids are an independent id space from form
attributes, so an attribute property can never arrive there. The nested `set:{extInfo:{…}}` form did exist but
was undocumented and threw `Attribute extInfo is not initialized for patch` whenever the extInfo was absent.

- New pure-Java `DynamicListExtInfoRules` — a **strict whitelist** of the 14 `DynamicListExtInfo` features
  (plus the `query` → `queryText` alias) with `hoist()` / `canonicalize()`. Deliberately not a generic "no such
  feature on the target → look inside extInfo" rule: that would silently swallow typos for every ExtInfo kind
  (ValueTable / ValueTree / DCS) and turn a fail-loud `Unknown form property` into a no-op.
- `applyFormAttributePatch` — the single choke point all three entry points share — now hoists the flat keys
  before the generic pass and merges them with an explicit `set:{extInfo:{…}}` block (canonicalized on both
  sides, explicit wins on conflict). The legacy flat `dynamicDataRead` branch is kept for backward
  compatibility but now only *contributes* to that merged patch, so the value is applied exactly once.
- New `applyDynamicListExtInfo` reproduces the form editor's `ChangeDynamicListExtInfoCustomQueryTask` by hand
  (that task is a `BmBasicTask1` and would open a nested transaction inside our `executeWrite`):
  `customQuery=true` generates `queryText` from `mainTable` via `DynamicListAttributeService.createQueryText`
  when none was passed and none is stored — and **reports the generated text back in the operation summary**,
  so the caller sees what was written; `customQuery=false` clears `fields`/`calculatedFields`/`parameters` and
  drops `queryText`; `mainTable` is assigned from exactly one place, so flipping `customQuery` either way
  preserves it. A contradictory `customQuery=false` + `queryText` patch is rejected.
- `ensureDynamicListExtInfo` materializes the missing companion (mirroring `ensureFormFieldExtInfo` /
  `ensureFormGroupExtInfo`) instead of the old blanket refusal; a non-DynamicList attribute is rejected with its
  real `valueType` named. `mainTable` binds from a metadata FQN through `dbViewDefs → mainView`, read
  reflectively to avoid importing the dozens of typed `*DbViewDefs` interfaces (new `Import-Package:
  com._1c.g5.v8.dt.metadata.dbview`).
- The DCS containment collections (`fields`, `calculatedFields`, `parameters`, `listSettings`) are **refused
  explicitly, not ignored**: the editor task does not populate them either, and the platform derives available
  fields from `queryText` when `autoFillAvailableFields=true`.
- New `mutate_form_model` op **`set_attribute_props`** (aliases `set_attribute`, `update_attribute`) — the root
  cause of the whole episode was that `set_item` was the only visible verb for "fix one property", while
  attributes were reachable only through `set_form_props set:{attributes:[…]}`.
- Two guard rails so the next caller does not repeat the search: `type:"DynamicList"` is early-rejected in both
  validators with a redirect to `customQuery`/`queryText`, and the generic resolver now answers a
  DynamicList-only key on the wrong target by explaining the attribute-vs-item id-space split and naming the
  working call.

Tests: `DynamicListExtInfoRulesTest` (whitelist, aliases, snake/camel/kebab, explicit-block precedence, and no
false positives on generic FormAttribute keys) + source-contract `DynamicListExtInfoContractTest` (form/EMF
types resolve only in the OSGi runtime). Schemas of both form tools and `knowledge/managed-forms.md` document
the auto → custom-query recipe and the `autoFillAvailableFields` caveat. Live validation pending.

### BF-13330 (2026-07-28) — `add_button`: accept the `Form.Command.<Name>` a `.form` actually carries

`add_button command_name:"Form.Command.X"` failed with `Form command not found`. `findFormCommandByName`
compared the raw string against `FormCommand.getName()` (the bare name), while `Form.Command.X` is exactly the
notation the BM serializer writes into `<commandName>` — so a caller echoing back what it had just read from
its own `.form` was not inventing a format.

- `stripFormCommandPrefix` removes `Form.Command.` / `FormCommand.` / `Command.` case-insensitively inside
  `findFormCommandByName`; a bare name keeps working unchanged.
- `Form.StandardCommand.*` is rejected with `INVALID_METADATA_CHANGE` explaining that standard form commands are
  platform-provided and appear in the auto command bar on their own — they are not form-local.
- A foreign-namespace FQN (`Catalog.X.Command.Y`, `CommonCommand.Z`) is rejected with `METADATA_NOT_FOUND`
  explaining that `add_button` resolves only `Form.getFormCommands()`, and suggesting a form command whose
  handler calls the object command. Object commands stay out of scope for now.
- The not-found message now lists the form's available command names, the same courtesy `remove_command`
  already extends, and the schema states that both formats are accepted.

Source-contract test `AddButtonCommandNameContractTest`. Live validation pending.

### Shared-infobase fan-out for sync-state and update reporting (2026-07-28) — a green project is not a green infobase

EDT's equality state belongs to a (project, infobase) PAIR, which is correct semantics but was reported
as if it described the infobase. When several projects share one infobase — a configuration plus its
extensions, or two configuration projects on one `.1CD` — `get_infobase_sync_state` answered EQUAL for
the project it was asked about while an extension sat unapplied, and `update_infobase` reported
`skipped, equality_state=EQUAL` for the same reason. Both read as "the infobase is current". Nobody
fanned out over the neighbours; EDT exposes no enumeration of the projects bound to an infobase
(`IInfobaseAssociationManager.getAssociation(InfobaseReference)` is a first-match lookup).

New `InfobaseSiblingResolver` runs `resolveDefaultInfobase` over the open workspace projects and matches
on `InfobaseIdentity.canonical` — the plugin's single "same infobase" rule, already used by the lease
guard — gated by the non-blocking `peekV8ProjectManager` peek so a cold EDT cannot park the caller for
30 s. Relations are classified: a configuration and its extensions MUST converge, so a NOT_EQUAL there is
a real staleness signal; two independent configuration projects are mutually exclusive by construction
and stay informational; LOADING/absent is reported as `unknown`, never as stale. The aggregation is a
pure function, unit-tested without EDT. `get_infobase_sync_state` now reports `siblings`,
`shared_infobase`, `sibling_projects_stale` and `all_projects_work_ready` (opt out with
`include_siblings=false`); `work_ready` keeps its per-project meaning for backward compatibility.
`update_infobase` annotates `sibling_projects_stale` + `sibling_warning` on the sync, async and
skipped-because-EQUAL payloads — output only, no new input parameters. The `dynamic_only` payload also
gained a forward-warning that a later EQUAL does not prove the deferred restructure ran.

### In-flight update guard keyed by infobase, not project (2026-07-28) — shared-IB double-fire now refused

EDT applies an update through a Designer session that is single-connection per INFOBASE, but the BF-12705
guard was keyed by project name. Two different projects bound to the same infobase therefore both passed
the guard and collided on the one Designer connection — exactly the failure the guard exists to prevent.

The guard key is now the canonical infobase identity (`InfobaseIdentity.canonical`, tolerant of slash
direction, case and a trailing separator), falling back to the project name when the infobase does not
resolve; dry runs neither acquire the slot nor pay for the resolution. The slot value now carries the
owning project alongside the job id, and the `UPDATE_ALREADY_RUNNING` payload reports `in_flight_project`
with a message that names the *other* project holding the infobase — without it, "already running" reads
as nonsense for a project that started no update. This is a deliberate behaviour change: on a shared
infobase some calls that previously proceeded now get UPDATE_ALREADY_RUNNING.

### Damaged target database classified instead of masked as IB_LOCKED (2026-07-28)

A physically damaged target database ("the integrity of configuration structure is violated") failed its
config-export step with a temp `xml.zip` message, and `isBlockedByLockedIB` matches a bare "xml.zip"
substring anywhere in the cause chain — so a broken database was reported as IB_LOCKED and the caller was
sent hunting for a lock holder that did not exist. Tools that read only the EDT model (`get_diagnostics`,
`metadata_smoke`) stayed green throughout, reinforcing the wrong conclusion.

New `TARGET_INFOBASE_DAMAGED` error code with an `isTargetDbDamaged` cause-chain predicate (EN + RU
platform tokens), evaluated BEFORE the locked-IB heuristic so the specific cause wins. The payload
explains that the TARGET DATABASE is damaged — not the configuration in git and not a lock — gives the
concrete repair command (`chdbfl.exe -s "<ib-dir>\1Cv8.1CD"`, or Designer → Testing and repair) and notes
that EDT-model-only tools are green here by design. Because the platform's exact wording has never been
observed live, both a successful classification and every unclassified UPDATE_FAILED now echo the raw
cause chain into `raw_error` and the bundle log, so the first live occurrence confirms or refutes the
token set without a separate diagnostic build.

### metadata_smoke states its non-goal (2026-07-28) — wording only

"Use metadata_smoke for headless verification" invited the reading that a green smoke report meant the
environment was verified. It never did: the tool creates and deletes temporary objects through the EDT
metadata API and probes a read-only BM transaction against the PROJECT MODEL, and never opens the target
infobase. The dispatcher description, the tool description and the report header (`scope: EDT metadata
API (project model only) — target infobase NOT verified`) now say so explicitly and point at
`get_infobase_sync_state` / `update_infobase` for infobase readiness. No logic changed; a unit test pins
the wording against regression.

### BF-13159 (2026-07-28) — `web_publication register_server`: raw `SWTException: Invalid thread access`

`register_server` returned a non-JSON `Exception: Invalid thread access` while `list_servers` afterwards showed
the server as registered. Not a response-marshalling problem: decompiling services.core 21.0 shows
`WebServerManager.add` first `save(webServers)` (persist + reload event) and only then, **synchronously on the
calling thread**, `fireWebServerAddedEvent`. `WebPublicationTool.doExecute` runs on an MCP worker thread, so the
unguarded UI listeners in `com._1c.g5.v8.dt.platform.services.ui` — `WebServerEditor.webServersReloaded` →
`bind(...)` and `AbstractPublicationEditor`/`InfobasePublicationEditor.webServersReloaded` → `close(false)` —
throw off-thread *after* the registration is already durable. The trigger is an OPEN web-server/publication
editor, not an open view (`WebServersView` is `asyncExec`-guarded). The raw text escaped because `doExecute`
only caught `EdtToolException`, and an async failure bypasses `AbstractTool.execute`'s synchronous try/catch, so
`ToolExecutionService` rendered `"Exception: " + message`.

Fixed in two layers:
- `EdtWebPublicationService.onUiThread(Supplier)` (+ `uiThreadDisplay()`): hops the mutation onto the SWT UI
  thread via `syncExec` with a RuntimeException relay, inline when already on the UI thread or headless.
  Never calls `Display.getDefault()` (that would create a display on the worker thread). Wraps
  `manager.add(server)` and — same hole, `firePublicationRemovedEvent` → `AbstractPublicationEditor
  .publicationRemoved` → unguarded `close(false)` — `manager.remove(server, publication)` in
  `removePublication`. `publish` was never affected because we drive the publish delegate directly and
  `firePublishedEvent` never runs. Own seam rather than `UiThreadExecutor`, which reports failures as the
  wrong exception family (`EdtAstException`); `protected` so tests can replace the hop.
- `WebPublicationTool`: a `catch (RuntimeException)` belt next to the `EdtToolException` one always answers
  structured JSON (`WEB_SERVER_ACCESS_FAILED` + the failure type and message), and `doRegisterServer` now
  re-reads the registry through the new `EdtWebPublicationService.findServer(name)` on a runtime failure — a
  registration that persisted is reported as success with `registered_with_ui_warning: true`, a `ui_warning`
  advisory and `status: "ok_with_warning"` instead of a failure the caller cannot act on.

Tests: `registerServerRuntimeFailureReturnsStructuredError`, `registerServerSurvivesUiListenerFailureWithAdvisory`,
`unexpectedRuntimeFailureIsStructuredJson`. The `syncExec` hop itself is not unit-covered — the tests bundle runs
on plain maven-surefire with no workbench. Live validation pending: the reproduction needs a web-server editor
open in EDT, and the `syncExec` hop wants one deliberate "register while the EDT UI thread is busy" pass to rule
out a deadlock.

### BF-13159 (2026-07-28) — `web_publication`: the inline probe of publish/restart never got credentials

`publish`/`restart` have run the same `runProbe` as `action=probe` since `da405ea` — `probe_user`/`probe_password`
are read from the shared parameter map and passed straight to `EdtWebPublicationService.probe(url, timeoutMs,
user, password)`, and `ToolArgumentParser` does not filter by schema. The defect was purely in the schema texts:
both credential params were documented starting with `probe: …`, so a caller read them as "only for
action=probe" and never sent them with a publish. The inline self-verification then went out unauthenticated and
any 1C HTTP/web service with mandatory authentication answered 401 — a guaranteed false "the publication does
not work" right after a publication that was in fact fine.

`probe_url`, `probe_user` and `probe_password` are now all prefixed `publish/restart/probe:` and say explicitly
that the credentials apply to the inline check too, and that without them it is UNAUTHENTICATED and returns 401
on a protected endpoint. `getDescription()` gained one sentence: publish/restart self-verify in the same call
when `probe_url` is given. No behaviour change — text only. Pinned by
`WebPublicationToolStandaloneTest.publishInlineProbeCarriesCredentials` /
`restartInlineProbeCarriesCredentials`, which assert the stub receives both user and password for
`action=publish` and `action=restart`.

Deliberately NOT done: reusing the `infobase_connection` credentials for the probe (not confirmed by the owner).

### BF-13405 (2026-07-28) — `add_metadata_child` advertised malformed JSON, so the tool was uncallable

`AddMetadataChildTool`'s `properties` description carried a JSON sample written as `[\"DocumentRef.Invoice\"]`
inside a Java text block. A text block **does** process `\"` and emits a bare `"`, which terminated the JSON
string early — the whole schema failed to parse (`MalformedJsonException: Unterminated object … path
$.properties.properties.description`) and callers could not invoke the tool at all. Introduced by `f184637`
(the `commandParameterType` + `group` work), where the sample was added. Correct form is `\\"`, already used
in `EdtMetadataDetailsTool` and `MutateFormModelTool` — only this one site was wrong.

`ToolSchemaValidityTest` exists for exactly this regression class (its javadoc cites the earlier `qa_run`
unescaped-quote incident that silently bypassed the `features` filter), but its tool list was hardcoded to
seven tools and covered no metadata or form tool — which is why the break shipped. The list now also covers
`add_metadata_child`, `create_metadata`, `update_metadata`, `edt_metadata_details`, `rights_manage`,
`mutate_form_model`, `apply_form_recipe`, `dcs_manage` and `web_publication`; all sixteen instantiate outside
the OSGi runtime, so the guard stays a plain compile-cycle test. Found independently by two diagnostic passes
over the BF-13405 and `dcs_create_main_schema` reports, and confirmed by the reporter's live parse error.

### BF-12936 / BF-12562 (2026-07-20) — `mutate_form_model remove_command`: delete a form-local command

The command-relocation refactor (form-local commands → register-owned commands) could not be finished:
after re-pointing the buttons, the old form commands could not be deleted. `remove_item` rejects them
("Cannot remove root form container item") because form commands live in `Form.getFormCommands()`, not the
UI item tree; and dropping their BSL handler in isolation raised "handler not found" — so the refactor
finished only with dead metadata (BF-12562 Issue 3).

New `mutate_form_model` op **`remove_command`** (aliases `remove_form_command`, `delete_command`):
- Resolves the target form command by `command_name` or `command_id` (the shared `resolveRequiredFormCommand`).
- Collects every button referencing it (`collectReferencingButtons`, walking `eAllContents()` — covers both
  the `CommandRef` wrapper and a direct reference).
- **Refuses by default** with `METADATA_DELETE_CONFLICT` when buttons still reference it, listing them —
  no silent orphaning. Pass `remove_referencing_buttons=true` (alias `force`) to detach those buttons from
  their parent containers and drop them together with the command.
- Removes the command from `getFormCommands()`; its contained `action → FormCommandHandlerContainer →
  CommandHandler` subtree goes with it atomically (no "handler not found"). The BSL handler procedure in the
  form module is left untouched (a harmless orphan; the module still compiles).

Source-contract test `RemoveCommandContractTest` (form/EMF types resolve only in the OSGi runtime, so the
structure is pinned like `RenameCommandContractTest`). Schema + `op`-required hint document `remove_command`.
**Live-validated** on the sandbox (`Catalog.Catalog.Forms.ItemForm`, build `-2130`): removing an orphan form
command succeeded (`buttons_removed=0`); removing a button-referenced command without the flag failed loud
with `METADATA_DELETE_CONFLICT` naming the button; `remove_referencing_buttons=true` removed the command and
its button together — and the `Form.form` on disk was left clean of both.

### BF-12936 (2026-07-20) — author a Command's `commandParameterType` + `group` (no more silent drop)

Relocating form-local commands to register-owned commands was blocked: there was no plugin-only path to
give a `Command` a `commandParameterType` or a `group`, forcing a rule-violating hand-edit of the `.mdo`.
Root causes (all confirmed by decompiling the EDT 2025.2.3 model — `InformationRegisterCommand` →
`BasicCommand`):

- **`commandParameterType` is a containment `mcore.TypeDescription`** (like an attribute's `type`), so the
  generic reference setter rejected it with `[INVALID_METADATA_CHANGE] Containment reference updates are not
  supported in set`.
- **`group` is an `mcore.CommandGroup` reference**; standard command-interface groups (e.g.
  `FormCommandBarImportant`) are addressed by a bare name, not an FQN, so `set={group:"FormCommandBar"}`
  hit `resolveByFqn` and failed with the misleading `[METADATA_PARENT_NOT_FOUND] Parent FQN must be …`.
- **`add_metadata_child` silently dropped both** (and `representation`): its create path applied only
  name/synonym for a `Command` because the property applier early-returned for any non-`BasicFeature` child.

Fix — one call now authors a fully-formed command, via both `add_metadata_child` and `update_metadata`:

- `applyReferenceValue` gains a TypeDescription-containment branch: a TypeDescription-valued containment
  reference (any command's `commandParameterType`) is built from the requested type(s) — scalar or array —
  resolved in the write-transaction namespace (`applyTypeDescriptionReference`), instead of being rejected.
- `applyReferenceValue` gains a CommandGroup branch (`resolveCommandGroupValue`): a dotted value resolves a
  user-defined `CommandGroup` object by FQN; a bare name resolves a standard group to a proxy through the
  platform `IEObjectProvider` (same mechanism the plugin already uses for platform types). An unknown
  standard name now fails loud with the **live list of valid group names** rather than a parent-FQN error.
- `add_metadata_child` create path (single + batch) calls the new `applyCommandProperties`, routing every
  supplied `Command` property through the shared feature setter — fail-loud on an unknown field, never a
  silent drop.
- `add_metadata_child` schema documents the `Command` properties (`commandParameterType`, `group`,
  `representation`, `parameterUseMode`, `modifiesData`, `shortcut`).

Unit test `AddMetadataChildToolCommandPropertiesTest` pins the tool-level plumbing (the properties survive
validation normalization + the validation token and reach the service request). **Live-validated** on the
sandbox EDT (project `TestConfiguration`, build `-2052`): a single `add_metadata_child` wrote
`<group>FormCommandBarImportant</group>` + `<commandParameterType><types>CatalogRef.Catalog</types></commandParameterType>`
+ `<representation>Auto</representation>` into the owner `.mdo`; `update_metadata set={commandParameterType}`
succeeded on the existing command; and `group:"FormCommandBar"` failed loud with the live valid-group list
(confirming the repro's `FormCommandBar` was an invalid name — the real one is `FormCommandBarImportant`).

### BF-13159 (2026-07-18) — `web_publication publish` Alias trailing-separator (403) + extension-services gate (404)

Follow-up after the registry fix below unblocked `publish` live (build `-1751`): the call now writes a
vrd + touches the conf, but the published `BSLAnalyzerService` still didn't answer 200. Two distinct
defects, both fixed:

- **Gap A — 403 (`AH01630`): generated `Alias` target lacked a trailing separator.** On a re-point, EDT's
  publish delegate writes `Alias "/<name>" "<location>"` with `<location>` = `publication.getLocation()`
  verbatim, while `<name>` is EDT's stored publication name (**trailing slash**, e.g. `agent-current/`).
  Apache concatenates the request remainder onto the target with **no** separator, so
  `/agent-current/hs/…` resolves under `…\published\agent-currenths\…` → `client denied` (403). Fix: new
  pure helper `EdtWebPublicationService.vrdLocation(location, name)` appends `File.separator` to the
  location when the publication name is slash-terminated but the location is not (a bare-name fresh
  publish keeps the remainder's own leading slash and is left unchanged). Unit test
  `EdtWebPublicationServiceHelpersTest`.
- **Gap B — 404: `publishExtensionsByDefault="false"` suppressed the extension owning the service.** The
  forced `false` (commit `38a6066`) was introduced as an NPE workaround, but the NPE was actually the
  **null web-extension `Path`** that `IPublicationManager.publish` hands the delegate (`aconst_null`, decompile-
  confirmed) — already fixed by driving the delegate directly with a non-null `Path`. The delegate does
  **not** enumerate project extensions (decompiled), so `false` was pure collateral: it stopped the
  `BSL_Analyzer` extension (which owns `BSLAnalyzerService`) from being published at all → 404, breaking
  the fleet's primary use case. Fix: `applyExtras` no longer forces `false`; it sets
  `publishExtensionsByDefault` only when the caller passes the new `publish_extensions_by_default` param,
  otherwise leaves EMF's default (`true`) — matching the hand-authored vrd that served the service. New
  schema param wired through `parsePublicationExtras`/`appendExtras` (infra found the undocumented param
  was silently ignored before). Test in `WebPublicationToolStandaloneTest`.

Build green (`-Plocal-target`); injector route + live 200 pending the next install round.
(feedback `2026-07-17-web-publication-webserverpublishdelegateregistry-unavailable`, Gaps A/B)

### BF-13159 (2026-07-17) — `web_publication publish` no longer times out on `IWebServerPublishDelegateRegistry`

- **`web_publication action=publish` failed after a 30s wait with `WEB_SERVER_ACCESS_FAILED: "EDT service
  not available: IWebServerPublishDelegateRegistry"`** — reproducibly, on a healthy stack (`list_servers`
  and `get` worked). Root cause (decompile audit of `platform.services.core 21.0`): unlike its siblings
  `IWebServerManager` / `IPublicationManager`, `IWebServerPublishDelegateRegistry` is bound **only in EDT's
  platform-services Guice injector** (`PlatformServicesCoreModule`) and is **not** among the interfaces the
  activator exports as OSGi services (`InjectorAwareServiceRegistrator`). The plugin looked it up with an
  OSGi `ServiceTracker.waitForService(30s)` — the same mechanism EDT's own `com._1c.g5.wiring.ServiceAccess`
  uses — so the lookup could never resolve it and always waited the full timeout. Not a headless /
  lazy-activation / "Servers view" issue; the lookup mechanism was simply wrong for an injector-only binding.
- **Fix:** new `PublishDelegateRegistryResolver` resolves the registry from the injector — `PlatformServicesCore`
  `getDefault()` → package-private `getInjector()` (reflected; classes loaded via the platform-services
  bundle's own class loader, no new `Import-Package`) → `Injector.getInstance(...)` — with a fallback that
  reads the registry EDT's `PublicationManager` (the OSGi `IPublicationManager`) keeps injected, matched by
  assignable type so a field rename can't break it. `VibeCorePlugin.getWebServerPublishDelegateRegistry()`
  now: non-blocking OSGi fast-path (forward-compat) → injector → `PublicationManager` field; the 30s wait is
  gone. Two independent reflective routes minimise EDT-version fragility. Unit test:
  `PublishDelegateRegistryResolverTest` (field-by-type + null-safety); injector route covered by live
  validation. (feedback `2026-07-17-web-publication-webserverpublishdelegateregistry-unavailable`)

### BF-12936 (2026-07-17) — `grep` honest hint when a literal search hides a regex-intent pattern

- **`grep` now hints on a zero-match LITERAL search whose pattern carries regex syntax.** `grep`'s
  `regex` param defaults to false, so the pattern is `Pattern.quote`d and matched literally — an
  alternation like `A|B|C` is searched as the single string `"A|B|C"` (pipes included) and returns a
  clean "0 matches" that reads as "not present" even when each term IS present. Confirmed live on
  stack-2 as a caller footgun (a `ЮТ_X|ЮТ_Y|…` pattern without `regex:true` → 0 matches; the same names
  found individually and with `regex:true`) — a recurring class (feedback `2026-07-11` and `2026-07-16`,
  both pipe patterns). Fix: on a zero-match result with `regex=false`, when the pattern looks like it
  was meant as a regex (contains `|`, `^`/`$` anchors, `.*`/`.+`, a `[..]` class, or `\d`/`\w`… escape
  classes — a bare `.` is ignored so FQNs like `Catalog.Foo` don't trip it), append a one-line hint to
  re-run with `regex:true`. The literal default is unchanged (no behaviour/regression change); the
  `regex` schema description now spells out the literal-matching semantics too. Unit test:
  `GrepRegexIntentTest` (the pure `looksLikeRegexIntent` heuristic). Build green (`-Plocal-target`).
  (feedback `2026-07-16-grep-false-negative-on-present-method-names`)

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
- **Follow-up fix (battle-test NPE — `webExtensions is null`):** the first live option-1 publish crashed
  with `NullPointerException: … "webExtensions" is null`. Root cause (confirmed in services.core 21.0
  bytecode): `PublicationManager.publish(pub, server)` passes a **null** web-extension `Path` to the Apache
  publish delegate (`aconst_null`), and the delegate dereferences it (`.toString()`) when the vrd carries
  `<httpServices>` — so a plain infobase publish always worked but an `http_services` publish NPE'd,
  unconditionally (independent of `publishExtensionsByDefault`). Fix: the `wsap_version`-absent publish
  branch no longer calls `manager.publish` (which nulls the path); it resolves the module itself
  (`IPublicationManager.getWebExtension`, which reads the conf's `LoadModule _1cws_module`) and drives the
  delegate directly with a non-null `Path` (null → clear `WEB_EXTENSION_NOT_FOUND` telling the caller to
  pass `wsap_version`). Also hardened earlier: `publishExtensionsByDefault(false)` (faithful to the hand
  vrds; avoids extension enumeration) and a `RuntimeException`→structured-`WEB_SERVER_ACCESS_FAILED` wrap so
  a delegate crash never surfaces as a raw NPE. The `rootUrl="bsl-analyzer"` serialization was confirmed
  correct in the crashed vrd — option-1's core premise holds. (feedback
  `2026-07-16-web-publication-publish-npe-webextensions-null`)

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
  hold the file lock (`designer_pids_still_holding` + `timeout_s` in the payload) — the process is
  *named, not killed*, since killing a Designer mid-restructure would corrupt the update. (feedback
  `2026-07-16-update-infobase-process-timeout-300s-ceiling-reproducible`)
- **Addendum:** the timeout PID field was renamed `aborted_designer_pids` → `designer_pids_still_holding`
  and the message now states the tool did *not* terminate the process and points at `kill_agent_mode=true`
  as the actual-kill lever on retry. Infra verified the old `aborted_*` name was misleading: the surfaced
  Designer PID stayed alive and kept doing real work (CPU climbing) well past the abort, so a caller that
  trusted the `aborted_*` name could race a still-live process. (infra feedback `29d43ab5` /
  `update-infobase-abort-does-not-kill-designer`)
- Unit tests: `EdtUpdateInfobaseErgonomicsTest` (sentinel vs real job id, timeout clamp, `timeout_s`
  parsing, `PROCESS_TIMEOUT` message + non-kill-implying PID key). Build green (`-Plocal-target`).

### BF-12936 (2026-07-16) — `rights_manage` honest reporting + `edt_validate_request` enum sync

- **`rights_manage` no longer reports unconditional success when nothing changed.** Each grant is now
  marked `(changed from X)` vs `(unchanged — already X)`, and the result message says *"No rights changed:
  … nothing was written"* when every grant was a no-op — instead of the old *"Role rights updated: …"* that
  listed every grant as applied regardless. Root cause: the summary line was appended for every grant even
  when `changeObjectRight` was skipped (`currentValue == newValue`), so a stale-model / already-set case
  masqueraded as a write. (feedback `2026-07-16-rights-manage-reports-success-but-does-not-persist`, ask 2)
- **On-disk advisory:** after the export, `rights_manage` reports whether the role's separate
  `Rights.rights` fragment is actually present on disk, and warns (non-fatally) when a change was made but
  the file is missing — the exact recovery-blocking symptom when a `Rights.rights` was deleted on disk under
  a live EDT (a stale in-memory model then accepts grants without re-serializing). Non-throwing on purpose:
  attribute-default-only grants can legitimately serialize no file, so a missing file is a "verify" hint,
  not a guaranteed error. (ask 1 — the deeper "why the fragment isn't rewritten" needs a live-EDT repro.)
- **`edt_validate_request` advertised `operation` enum re-synced with `ValidationOperation`.** The schema
  stopped at `mutate_form_model` and omitted `rights_manage`, `render_template`,
  `create_event_subscription`, `create_information_register` — all accepted by the backend, so a caller had
  to discover `rights_manage` by trial. A new test asserts every `ValidationOperation` toolName is advertised.
  (ask 3)
- Unit tests: `RightsManageMessagesTest` (changed vs no-op summary, no-op message, changed-count, advisory),
  `EdtValidateRequestToolSchemaTest` (enum-in-sync). Build green (`-Plocal-target`, 23/23 across touched
  suites).
- **Persistence fix (scenario "b", root cause — LIVE-VALIDATED on stack-2).** `rights_manage` applied the
  grants to the BM model but never wrote `Rights.rights`. Root-caused over a diagnostic cycle (honest
  reporting → decompile of `BmModelManager.forceExport` → an in-plugin `RIGHTS-DIAG` build): after a role's
  `.rights` file is deleted on disk under a live EDT, `role.getRights()` returns a **dangling
  `RoleDescription` that is no longer a registered top-object** — `getTopObjectByFqn(Role.<name>.Rights)`
  returns null post-commit, so `forceExport`→`createSaveObjectTask` silently skips it (its `result=true`
  comes from the Role/Configuration tasks) → `RightsExporter` never runs → no file. `ensureRoleDescription`
  reused that orphan directly, never calling `attachTopObject`. **Fix:** reuse an existing `RoleDescription`
  only when it is a resolvable top-object (`getTopObjectByFqn` by its external FQN != null); when orphaned,
  fall through to `attachBootstrappedRoleDescription` (the attach path) so a properly registered, exportable
  fragment is created and the grants re-apply onto it. Normal on-disk roles resolve → reused unchanged (no
  regression). Validated live (build `0.1.7.20260716-2008`): `Rights.rights` written for both roles,
  form-identical to a hand-authored role, fragment now resolves (`getTopObjectByFqn`=`RoleDescriptionImpl`),
  responses carry no warning. NB: re-bootstrap applies only the *requested* grants onto the fresh fragment —
  the recovery intent for a file-deleted role. (feedback
  `2026-07-16-rights-manage-reports-success-but-does-not-persist`, ask 1)
- **Content-validation fix — reject a grant on a rights-less type + a `value:remove` escape hatch.**
  `rights_manage` accepted an invalid grant on an `Enum` (a type with no configurable access rights) and
  wrote a stray `<object>Enum.X</object>` block; that block then stalls the platform DB restructure for
  10–20 min on the next `update_infobase` (owner-confirmed real cause of the BF-12936 prod hang; not
  statically diagnosable). Root cause (decompiled `RightsInfoService`): `resolveRight` matched the right by
  NAME against `getRights(object)`, which returns the **global** pool of every right for the runtime version
  (not the object's own rights), so `Read`/`Use` resolved for an Enum even though its `getEClassRights` set
  is empty; the coarse `isMdObjectHasRights` guard can't catch it (Enum's EClass is in
  `ALL_SUPPORTED_RIGHT_ECLASSES`). **Fix (Part 1):** when the rights service is warm (global pool non-empty)
  but the target's own eClass exposes zero configurable rights (`getEClassRights` empty), reject the grant —
  gated on a non-empty global pool so a cold service never yields a false reject; resolution order for
  objects that DO have rights is unchanged (no regression for registers/catalogs/sub-objects). A dual-purpose
  diagnostic logs `globalRights` count vs `eClassRights` names per grant. **Fix (Part 2):** a new
  `value:remove` verb (aliases `clear`/`delete`/`drop`) drops the explicit right entry and prunes the emptied
  `<object>` wrapper (`RightsModelUtil.removeEmptyObjectRights`) — `unset`/`provided` leave the block on disk,
  so removal was previously impossible; `remove` deliberately bypasses the Part-1 guard so an already-written
  stray block can be stripped. Unit tests: `normalizesRemoveAliases` (parsing), `removalSummaryMarksActualRemovalVsNoOp`
  (message). Build green (`-Plocal-target`). (feedback
  `2026-07-16-rights-manage-accepts-invalid-enum-grant-and-cannot-fully-remove-it`)

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
