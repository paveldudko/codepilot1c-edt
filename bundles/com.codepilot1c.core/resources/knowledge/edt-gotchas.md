EDT development pitfalls:
- BM sync: after metadata mutation, wait for derived data recalculation before reading results
- Export timing: forceExport() is asynchronous; do not assume files exist immediately after call
- UUID handling: always null-check UUIDs; use bmGetTopObject() before bmGetFqn()
- Form materialization: newly created forms need disk file creation before they can be opened
- Configuration comparison: EDT comparison mode treats extension-adopted objects differently
- Workspace refresh: IProject.refreshLocal() may be needed after external file changes
- Derived data: validation results and cross-references update asynchronously after metadata changes
- Module editing: BSL modules are XText resources; use proper resource set for editing
- Platform version: Version.LATEST is a fallback, not an actual detected version

YAxUnit test runs (yaxunit_run):
- Empty onec.log / launch.log after a successful run is normal: closeAfterTests kills the thin client before it flushes them. The engine's full log is yaxunit.log in the run dir; junit.xml is the authoritative result
- Zero executed tests is never "green": the run is reported as an error with a reason — infobase_stale (equality_state NOT_EQUAL/LOADING → run update_infobase first), filter_matched_nothing (check the Модуль.Метод spelling, the test's registration in the module's ИсполняемыеСценарии handler, and the extensions filter), no_match_unverified (equality state unreadable on a cold EDT → rule out staleness first), or no_tests_in_infobase (extension not installed/attached, or attached in safe mode)
- Failing tests are a normal (non-error) result with status tests_failed — read the failures list; the error channel means "no verdict", not "red tests"
- exitcode.txt is written with a UTF-8 BOM ahead of the 0/1 digit; strip it before parsing
- A leaked 1cv8c from an earlier run holds the infobase's named pipe and makes the next run burn its whole timeout. It is NOT a descendant of the spawned process (the launcher reparents it), so terminating the process tree never reaps it — it has to be matched by command line (RunUnitTests= + the infobase path) and killed by PID. Without a command line (WMI unavailable) attribution is impossible and killing would hit a neighbouring stand's client

BSL doc-comment See / См. links (bsl_list_methods, bsl_module_exports):
- The resolver is EDT's, not ours, and it IS transitive: BslDocumentationComment.computeReturnTypes / computeParameterTypes recurse into the linked comment and guard loops with an alreadyProcessingMethods set. "It only goes one hop" is a misreading of the symptom, not the behaviour
- What actually stops the walk is the guard on that recursion: EDT follows a link only while every comment on the chain is a *bare link* — the link is the last part of the description AND the comment declares no Параметры:/Возвращаемое значение: (Parameters:/Returns:) section of its own. The first hop that declares one of those sections wins outright: EDT takes the types from it and never looks at its own link. Practical rule — put the type documentation on the ROOT of the chain and link to that root, not to a middleman
- Пример:/Example:, Варианты вызова:/Call options: and Устарела./Deprecated. end the description but do NOT break the chain; only the parameters/returns sections do
- The link text swallows a trailing period, and EDT then splits it on "." keeping the empty tail: `// См. МойМетод.` links to `МойМетод.` → segments ["МойМетод", ""] → resolution fails silently. Write the link without a final period
- `// См. также Модуль.Метод` links to `также` (the keyword scan takes the next word) and the rest becomes trailing text, so the chain does not run at all. Same for `// См. Модуль.Метод()`: the scan stops at "(" and the leftover "()" is trailing text
- The tool payload mirrors all of this: seeTarget is the link EDT would consider (a link buried in prose is not reported, because EDT would not chain through it either), seeChain lists the hops it would actually walk inside this module. seeTarget present with an empty seeChain means exactly one thing — this comment declares its own Параметры:/Возвращаемое значение: section, so EDT ignores the link. seeChainCrossModule means the chain left the module (a qualified target, or a name this module does not declare); seeChainTruncated means it is longer than 8 hops. A name repeated at the end of seeChain means the chain is cyclic
