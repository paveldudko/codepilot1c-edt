# Bus-drain recon — 2026-07-28

Диагностика по бэклогу, снятому с шины `edt` 2026-07-28 (14 непрочитанных → 5 закрыто, 9 в работу).
Ветка `pd/mcp-bridge-lite`, baseline сборки зелёный (`mvn -B -Plocal-target -DskipTests clean verify`, 33 с).

Разведка велась субагентами; прогон прерван лимитом сессии (reset 17:40 Europe/Warsaw).
Ниже — то, что успело завершиться. **Реализации в дереве нет**, все правки ещё не начаты.

## Статус кластеров

| Кластер | Разведка | Реализация |
|---|---|---|
| A — DynamicList extInfo (BF-13330) + add_button FQN | **готова** | в работе |
| B — composite/containment metadata (BF-12936) + **BF-13405 blocker** | **готова** | схема — `13077db`; остальное ждёт освобождения `EdtMetadataService` |
| C — yaxunit false-green / prerelease autoselect | **готова** | в работе (без резолвера рантайма — файл занят) |
| D — update_infobase shared-IB equality | **готова** | в работе |
| E — web_publication SWT/probe-auth | **готова** | в работе |
| E — diagnostics origin, doc See | **готова** | не начата (волна 3) |
| F — dcs_create_main_schema | **готова** | не начата (волна 3) |

Сделано и закоммичено: `13077db` — `add_metadata_child` рекламировал невалидный JSON (регрессия `f184637`,
инструмент был невызываем) + `ToolSchemaValidityTest` расширен с 7 до 16 инструментов. Тест прогнан точечно
полным реактором: `Tests run: 1, Failures: 0`.

**Порядок волн определяется не приоритетом, а занятостью файлов:** `EdtMetadataService.java` —
общий choke point для кластеров A и B, поэтому composite `type.types` и adopt-verb физически не могут идти
параллельно с DynamicList extInfo. Аналогично `EdtRuntimeService.java` держит D и блокирует C2 (наблюдаемость
резолвера рантайма).

---

## Немедленные обходы (работают на текущем билде, без сборки)

1. **BF-13330 / DynamicList extInfo.** Плоские ключи не работают, но **вложенная форма уже поддерживается**:
   `apply_form_recipe attributes:[{name:"List", action:"update", set:{extInfo:{customQuery:true, queryText:"…"}}}]`.
   Маршрут: `EdtMetadataService.applyFormAttributePatch:4104-4113` (`removeMapValueIgnoreCase("extInfo","ext_info")`
   → `applyFormPropertySet(attribute.getExtInfo(), …)`). Вероятность, что сработает — ~0.85; если упадёт
   `Attribute extInfo is not initialized` → у атрибута пустой extInfo (нужен `ensure*`), любой другой текст → смотреть по месту.
   **Probe без сборки:** тот же рецепт с `set:{extInfo:{autoFillAvailableFields:true}}` (поле уже true в живом `.form`).
2. **web_publication inline probe (ложный 401).** `probe_user`/`probe_password` **уже читаются** и для `action=publish`
   (`WebPublicationTool.maybeProbe:325-331` → общий `runProbe:333-349`; унификация в `da405ea`, предок HEAD).
   Достаточно передать их вместе с `publish` — дефект чисто в текстах схемы, которые описывают их как «probe:».
3. **get_diagnostics подмешивает review-аннотации.** Обход — `severity=warning`: маркеры `edt-review`
   не выставляют `IMarker.SEVERITY`, поэтому классифицируются как INFO и отсекаются порогом.

---

## A. DynamicList extInfo (BF-13330) — размер M

**Root cause — не «пути нет», а «нет пути плоскими ключами».** Все три входа
(`set_item`, `apply_form_recipe attributes`, `set_form_props{attributes}`) сходятся в один choke point
`EdtMetadataService.applyFormAttributePatch:4068-4120`; плоский `customQuery` доживает до
`applyFormPropertySet(attribute, set)` (`:4117-4119`), а `applySimpleFeatureValue:5565-5588` →
`resolveStructuralFeatureIgnoreCase:12133-12147` смотрит **только `target.eClass()`** и в extInfo не заглядывает.
Контрольный тест ноты с `autoFillAvailableFields:true` дал ту же ошибку именно поэтому: поле существует,
но на `DynamicListExtInfo`, а target — `FormAttribute`.

Отдельная ловушка: `set_item item_id=1` резолвит **FormItem**, не FormAttribute (`resolveRequiredItem:3389-3411`) —
пространства id у items и attributes независимы, в репро `id=1` попал в `Table "List"`.

**Факты EDT API** (декомпиляция 2025.2.3, `com._1c.g5.v8.dt.form.model_14.0.0`):
`DynamicListExtInfo extends FormAttributeExtInfo` — `queryText:String`, `mainTable:DbViewDef`,
`customQuery/dynamicDataRead/autoFillAvailableFields/autoSaveUserSettings/getInvisibleFieldPresentations:bool`,
`fields:EList<dcs.schema.DataSetField>`, `calculatedFields`, `parameters`, `listSettings`, `keyType`, `keyField`.

Эталон семантики — `ChangeDynamicListExtInfoCustomQueryTask`:
`customQuery=true` → `setQueryText(DynamicListAttributeService.createQueryText(mainTable, scriptVariant))`;
`false` → `fields/calculatedFields/parameters.clear()` + `setQueryText(null)`. Атрибут должен быть attached к Form.

**Важно для объёма:** `fields` при `customQuery=true` **не заполняется** — остаётся пустым, пока
`autoFillAvailableFields=true`. Проверено на живом конфиге `tester`: ни один `.form` с `customQuery` не содержит
`<fields>`. Просьбу ноты про `fields` можно закрыть честным отказом, а не реализацией.

**План фикса:**
- новый pure-Java `edt/metadata/DynamicListExtInfoRules.java` (по образцу `FormDefaultsRules`): whitelist
  нормализованных ключей + `hoist(set)` с канонизацией (`query`→`queryText`) + `isDynamicListOnlyKey`;
- врезка одна — в `applyFormAttributePatch` по образцу уже существующего `applyFormAttributeTypeQualifiers:4132-4303`
  (тот же паттерн хойста плоских ключей в под-объект). Порядок: `useAlways` → hoist → merge с явным
  `extInfo:{}` (явный побеждает) → legacy-ветку `dynamicDataRead:4088-4102` не удалять, пропустить через тот же merge;
- новый `applyDynamicListExtInfo(...)`: `ensureDynamicListExtInfo` (по образцу `ensureFormFieldExtInfo:3273`)
  вместо глухого throw `:4108-4110`; не-DynamicList → fail-loud; `mainTable` FQN → `resolveByFqn` →
  `getDbViewDefs().getMainView()`; семантику `customQuery` воспроизвести **вручную**, BM-таск не запускать
  (он `BmBasicTask1` = своя транзакция, а мы уже внутри `executeWrite`);
- `fields/calculatedFields/parameters/listSettings` → явный `INVALID_METADATA_CHANGE` вместо тихого игнора;
- ловушка на `type:"DynamicList"` (`validateFormAttributeType:4665-4687`) — сейчас уходит в value-type резолвер
  и падает `Type not found in BM: DynamicList`;
- hint в `applySimpleFeatureValue:5582-5588`: если target — FormAttribute и ключ DynamicList-only, объяснить,
  что это свойство extInfo и адресуется через патч реквизита, а не `set_item`;
- MANIFEST: `Import-Package: com._1c.g5.v8.dt.metadata.dbview` (нужен только для `mainTable`;
  пакет есть в `com._1c.g5.v8.dt.metadata_18.0.100` в 2025.2.3). `form.service` и `dcs.model.*` уже импортированы;
- схемы/доки: `MutateFormModelTool:50,56`, `ApplyFormRecipeTool:96` (там `extInfo` не упомянут вообще),
  `resources/knowledge/managed-forms.md`.
- опционально новый op `set_attribute_props` — снимает корневую ловушку «`set_item` единственный видимый глагол».

**Риск:** `applyFormAttributePatch` общий для create/update и соседних типов ExtInfo (ValueTable/ValueTree/DCS) —
хойст обязан быть **строго whitelist-based**; generic «нет на target → искать в extInfo» откроет тихое
проглатывание опечаток для всех типов.

**Тесты:** полноценный JUnit только на `DynamicListExtInfoRules` (чистая логика); остальное — source-contract
(`Form`/`FormFactory` не резолвятся в plain Maven test bundle, см. `AddFormParameterContractTest:15-19`).
Live-обязательно: что BM реально пишет `<customQuery>`+`<queryText>` и **сохраняет** `<mainTable>`;
что EolGuard не портит `<extInfo>` на round-trip; что `getDbViewDefs().getMainView()` не null на холодном EDT.

### add_button + FQN command name (S)
`findFormCommandByName:2356-2366` сравнивает сырую строку с `cmd.getName()`, а `Form.Command.X` — **ровно та
нотация, которой BM сериализует `<commandName>`**, т.е. FQN-попытка агента была чтением собственного `.form`.
Фикс: срезать префиксы `Form.Command.`/`FormCommand.`/`Command.`; `Form.StandardCommand.*` → внятный отказ;
чужое пространство (`Catalog.X.Command.Y`) → `METADATA_NOT_FOUND` с пояснением; в сообщение добавить список
доступных form-команд (как уже сделано в `remove_command:1512-1519`).

---

## D. update_infobase shared-IB equality — размер M

**Плагинного хранилища equality НЕ существует.** Единственный источник — EDT API,
`EdtRuntimeService.readInfobaseEqualityState:997-1029` → `IInfobaseSynchronizationManager.getEqualityState(IProject, InfobaseReference)`.
Состояние принадлежит EDT и живёт **по паре (проект, ИБ)** — это корректная семантика, не баг.
Дефект целиком в слое агрегации: `GetInfobaseSyncStateTool.read:109-159` и payload
`EdtUpdateInfobaseTool` (`:299-318` sync, `:466-484` async, `fillSkippedEqual:730-738`) видят только один проект.

Ключ агрегации — `InfobaseIdentity.canonical` (`InfobaseIdentity.java:61-78`), по нему уже кейится lease-guard.
Перечислить соседей через EDT API нельзя: `IInfobaseAssociationManager.getAssociation` — lookup
«первый попавшийся» (проверено байткодом). Рабочий путь: прогнать `resolveDefaultInfobase:136-203`
по открытым проектам и сматчить canonical; резолвер сам покрывает association, расширение→ИБ родителя
(`resolveExtensionParentInfobase:339-379`) и standalone. Прецедент перебора с peek-гейтом —
`EdtWorkspaceStateService.addBoundInfobases:238-282` (гейт на `peekV8ProjectManager()==null`, иначе риск 30-с
`waitForService`).

**Обязательна классификация `relation`**, иначе ложная тревога: `extension_of`/`parent_of`/`co_extension`
обязаны сходиться → `stale` осмысленен; `same_ib_configuration` (два независимых конфигурационных проекта
на одной ИБ) взаимоисключающи, оба EQUAL невозможно → info-only, `stale` не ставить. `LOADING`/`null` → `unknown`.

**Побочный реальный баг (F4):** in-flight-guard `update_infobase` кейится по **имени проекта**
(`IN_FLIGHT_UPDATES:82`, `updateKey:341-343`), хотя Designer-соединение к ИБ одно → на shared-ИБ два апдейта
оба получают слот и сталкиваются. Пере-кеить на `canonical(resolveDefaultInfobase(...))` с fallback на имя проекта.

**F5 — классификатор битой целевой БД:** `TARGET_INFOBASE_DAMAGED` + `isTargetDbDamaged(Throwable)`,
токены EN+RU (`integrity of configuration structure` / `нарушена целостность структуры конфигурации`).
**Критично: проверять ДО `isBlockedByLockedIB:915-925`** — тот срабатывает на подстроку `xml.zip` в любом
сообщении цепочки и может маскировать битую БД под `IB_LOCKED`. Точный текст исключения вживую не наблюдался →
сохранять сырую цепочку причин в payload + лог, чтобы первое живое падение подтвердило токены.

**F6 — `metadata_smoke` только формулировки:** инструмент работает с EDT-моделью проекта и целевую БД
не открывает вообще. Источник ложного успокоения — `EdtDiagnosticsTool:46` «Use metadata_smoke for headless
verification». Плюс non-goal в `getDescription:68-70` и строка `scope:` в шапке `renderReport:274-293`.

**Решения по развилкам (приняты, не пересматривать):** авто-обновление соседей — нет, только предупреждение;
`work_ready` не менять (добавить `all_projects_work_ready` рядом); F4 включить отдельным коммитом как
поведенческое изменение; cross-EDT соседи вне охвата (там lease-guard); смежную
`issues/deceptive-equal-after-dynamic-only-update.md` забрать в ту же волну.

---

## E. web_publication + diagnostics/doc — четыре независимых дефекта

Все четыре **открыты** (в CHANGELOG и issues упоминаний нет).

### A) register_server → сырой `SWTException: Invalid thread access` (M)
Гипотеза ноты про «маршалинг ответа» **неверна**: исключение летит из самого EDT-вызова, уже **после** персиста.
`WebPublicationTool:184` гоняет работу в `CompletableFuture.supplyAsync` (MCP-worker), `EdtWebPublicationService:176`
→ `manager.add(server)`, а `WebServerManager.add` сначала `save(...)`, потом **синхронно** `fireWebServerAddedEvent`
на потоке вызывающего. Незащищённые слушатели: `WebServerEditor.webServersReloaded` → `bind(...)`,
`AbstractPublicationEditor`/`InfobasePublicationEditor.webServersReloaded` → `close(false)`.
Триггер — **открытый редактор** веб-сервера/публикации, а не открытая View (`WebServersView` защищён `asyncExec`).
Сырой текст наружу потому, что `WebPublicationTool:227` ловит только `EdtToolException`, а асинхронное падение
проходит мимо синхронного try/catch `AbstractTool.execute:134-167` → `ToolExecutionService:117-125`.
**Смежно:** `removePublication` (`EdtWebPublicationService:440`) — та же дыра через `firePublicationRemovedEvent`;
`publish` не падал только потому, что мы обходим `IPublicationManager.publish` и дёргаем делегат напрямую.
Фикс: приватный `onUiThread(Supplier)` по образцу `QlValidationService:70-87` (inline при `Display.getCurrent()!=null`,
иначе `syncExec`, headless-fallback) вокруг `add`/`remove`; плюс `catch (RuntimeException)` со структурированным
JSON и верификацией `manager.get(name)!=null` → success с advisory. `org.eclipse.ui` уже в MANIFEST:29.

### B) publish inline probe без auth (S) — плумбинг уже рабочий, дефект в текстах схемы
См. «немедленные обходы» выше. Фикс: префикс `probe:` → `publish/restart/probe:` в `:139`/`:143`,
явно сказать, что без кред inline-probe уходит неаутентифицированным → гарантированный 401;
в `probe_url:135-138` дописать про передачу кред в том же вызове. Опционально (по решению владельца) —
вытаскивать `Usr=`/`Pwd=` из `infobase_connection` с полем `probe_auth_source`.
Тест — целиком JUnit, по образцу `probePassesCredentialsToService:180`, но с `action=publish`.

### C) get_diagnostics подмешивает review-аннотации (M)
Инструмент живёт в **UI-бандле**: `com.codepilot1c.ui/.../tools/GetDiagnosticsTool.java`. Корень —
`EdtDiagnosticsCollector:1123` `file.findMarkers(null, true, DEPTH_ZERO)`: `type==null` = все типы + подтипы,
allow-list'а нет (то же на project-скоупе `:1180`). Источник — соседний плагин `edt-review`, маркер
`com.dudko.edt.review.commentMarker` (`super type=org.eclipse.core.resources.textmarker`, `persistent=true`),
`IMarker.SEVERITY` не выставляет вообще → `getAttribute(SEVERITY,-1)` → `-1` → `Severity.fromMarkerSeverity`
(`EdtDiagnostic:65-71`, `default -> INFO`) → проходит дефолтный порог.
Поле происхождения **фактически уже есть, но скрыто**: `EdtDiagnostic:13-29` несёт `markerType` и `source`,
но рендерятся только под debug-гейтом `:243-251`.
Фикс: поле `origin` (`compiler|analyzer|custom-check|review-annotation|unknown`) + **чистый** классификатор
(положить в `core/diagnostics` рядом с `DiagnosticsLineFilter` — тогда покрывается полноценным юнитом) +
рендер вне debug-гейта + фильтр в `DiagnosticsQuery:147-176` (default — исключать `review-annotation`) +
не капать review-записями в `errorCount/warningCount/infoCount` (`DiagnosticsResult:189-196`).
**Попутная уборка:** в `EdtDiagnosticsCollector:240` в строковом литерале сидит настоящий NUL-байт,
из-за которого ripgrep считает файл бинарным.

### D) doc-comment `See` — premise ноты неточен (S)
В нашем репо резолва See **нет вообще**: `BslSemanticService.extractDocumentation:999-1034` только текстом
скребёт подряд идущие `//`-строки. Резолв — внутри EDT и он **транзитивный** с cycle-guard
(`BslDocumentationComment.computeReturnTypes`, рекурсия + `alreadyProcessingMethods`). Выглядит как один хоп
потому, что цепочка продолжается только если у промежуточного комментария **нет своих секций**
(`returnSection == null && parametersSection == null`) и `LinkPart` — последняя часть Description.
⇒ транзитивный резолвер писать не нужно и негде. Реализуемое: чистый `BslDocSeeChain` (вытащить `См.`/`See`
цель regex'ом), поля `seeTarget`/`seeChain` в `BslMethodInfo:12-47`, отражение в `bsl_list_methods` /
`bsl_module_exports` + честная формулировка контракта в SCHEMA.

### Порядок и параллелизация E
Польза/цена: **B → A → C → D**. **A и B пересекаются полностью** (`WebPublicationTool.java`,
`EdtWebPublicationService.java`, `WebPublicationToolStandaloneTest.java`) → одному агенту, сначала B.
**C** изолирован в `com.codepilot1c.ui` (+ классификатор в core), **D** — в `core/edt/lang` + `tools/bsl`;
C и D можно вести параллельно друг с другом и с парой A+B. Единственный общий файл — `CHANGELOG.md`.

---

## C. yaxunit_run — три дефекта в одном файле

### C1. total=0 → false-green (доказано кодом, probe не нужен)
`YaxunitRunTool.java:446-447`:
```java
boolean green = report.failures == 0 && report.errors == 0
        && (outcome.yaxunitExitCode() == null || outcome.yaxunitExitCode() == 0);
```
`report.tests` в предикате **не участвует** → пустой отчёт (0 тестов, 0 падений, exit 0) удовлетворяет его
тождественно → `:453-456` `status="passed"` + `ToolResult.success`. Путь достижимости:
`QaJUnitReport.parseDirectory:37-55` возвращает non-null, если в runDir найден **любой** `*.xml`;
`getIntAttr:141-151` отдаёт 0 при отсутствующем атрибуте ⇒ `<testsuites/>` или `<testsuite tests="0">`
даёт `report != null, tests == 0`.

Отпечаток бага — асимметрия внутри одного файла: `classifyNoReport:470-476` **уже** описывает кейс
«фильтр не совпал ни с одним тестом» и возвращает failure. Один смысл, два противоположных канала.
Тот же класс дефекта в соседнем инструменте: `QaRunTool.java:1554-1562` (`report.tests` тоже не проверяется).
Не покрытый нотой подслучай: **частичное** совпадение (`tests=[A.t1, A.t2]`, исполнился один → `total=1`,
`passed`) — `total==0` его не ловит.

**Контракт, который предлагается зафиксировать для обоих раннеров:**
> канал ошибки = «вердикта нет, выводов делать нельзя»; канал success = «вердикт есть, читай отчёт».

Прецедент в репо, снимающий спор о ложной тревоге: `QaRunTool.java:396-411` — `no_features`/`feature_not_found`
(ноль резолвнутой работы) уже возвращаются как failure, а `tests_failed` — как success.
**Coupled change:** из этого же контракта следует, что красные тесты должны переехать в success с
`status="tests_failed"` (сейчас `:453-456` отдаёт failure) — т.е. открытая нота
`2026-07-03-yaxunit-run-red-tests-as-mcp-error.md` закрывается тем же изменением. Починить только `total=0`,
оставив красные в error, — значит оставить контракт неразличимым для вызывающего.

Почему именно `isError`, а не предупреждение в payload: на проводе MCP клиент надёжно различает только
`McpHostRequestRouter:277-282` (`isError = !result.isSuccess()`). У инструмента **уже есть**
`preflight_warnings:220-225`, и в инциденте 07-14 вызывающий прошёл мимо них.

Статусы (коды `EdtToolErrorCode` не применяются — `yaxunit_run` не использует `EdtToolException`,
«код» = поле `status`): `total==0` + непустой фильтр → `no_tests_matched` (error); `total==0` без фильтра →
`no_tests_found`/`no_tests_in_infobase` (error); `total>0` с красными → `tests_failed` (**success**);
`total>0` зелено → `passed`; нет отчёта/timeout → как сейчас (error).

**«Тестов нет» vs «ИБ stale» различимо, сигнал уже в коде** — `EdtRuntimeService.readInfobaseEqualityState`
(`:997-1029`, best-effort, never throws): `NOT_EQUAL`/`LOADING` → `reason=infobase_stale` + «run update_infobase,
then retry»; `EQUAL` → `filter_matched_nothing` (проверить имя `Модуль.Метод`, регистрацию в `ИсполняемыеСценарии`);
`null` → `no_match_unverified` (оба хинта, stale первым); пустой фильтр → `no_tests_in_infobase`
(расширение не установлено/не подключено/safe-mode — переиспользовать `SAFE_MODE_MARKERS:83-85`).
Реализуется одним вызовом в preflight рядом с `checkStaledClientProcesses:220` + поле `equality_state` в envelope.

Реализация: вынести чистый `static Verdict classify(QaJUnitReport, RunOutcome, boolean filterPresent, String equalityState)`
из `buildResult:411-457` (без экстракции юнит-тестировать нечего — метод приватный инстансный);
рассмотреть точечный парс `junit.xml` вместо скана `*.xml` (runDir — это ещё и CWD клиента, `:206`,
так что чужой `.xml` становится «отчётом»); выровнять `QaRunTool:1554-1562`.

### C2. Устойчивый resolve-fail тонкого клиента / автовыбор pre-release
**Исключено сразу:** это НЕ регрессия `b8ad7c7` (`EdtRuntimeService:703` уже передаёт реальный проект)
и НЕ отсутствие бинарника — во всех четырёх установках на машине (`8.3.27.1644/1786/2074`, `8.5.1.1302`)
есть и `1cv8.exe`, и `1cv8c.exe`. Воспроизведение на `dry_run` закономерно: `buildUnitTestCommand`
вызывается на `:203`, а early-return по `dry_run` — только на `:215`.

**Установлено декомпиляцией `platform.services.core_21.0.0`:** `resolveInstallation:447-456` без `version_mask`
зовёт `resolveByProjectAndInfobase(ENTERPRISE, project, infobase, UPDATE)`; внутри цепочка
`findStoredInstallation(typeId, project, infobase, accessType)` → `getAll` → `filterVersionCompatible` →
`calculateRequiredComponents` → `filterByBitness` → `orderByUsefulness().reversed()` → **newest-wins**. Отсюда:
1. `InfobaseAccessType` имеет ровно два значения — `UPDATE` и `CLIENT_LAUNCH`; мы жёстко передаём `UPDATE`
   во **всех** путях, включая три запуска тонкого клиента (`:544`, `:603`, `:654`, `:703`). Пин-стор
   **ключуется access-типом**, и фильтры совместимости разные (`filterUpdateVersionCompatible` vs
   `filterClientLaunchVersionCompatible`) ⇒ пин, поставленный EDT-UI для запуска клиента, нашим кодом не читается.
2. Фильтр совместимости Optional-gated и **молча выключается на «холодном» проекте**:
   `calculateCompatibilityMode` идёт через `getCompatibilityModeIfStarted(IV8Project)` — если DT-проект не поднят
   (ровно контекст BF-13159, см. `edt_telemetry_eclipse_password_dtproject_block`), Optional пуст → фильтр
   по версии не применяется → выигрывает самая новая установка, т.е. 8.5.1.x pre-release.
3. Требуемый компонент для ENTERPRISE — `ThickClient` для **обоих** access-типов, а мы затем просим именно
   тонкий (`resolvable.resolve(List.of(COMPONENT_TYPE_THIN_CLIENT), appArch)`, `:501`). Отбор и запрос
   рассогласованы by design EDT — обрабатывать промах обязаны мы.

**Достоверно (не гипотеза): дефект наблюдаемости.** `resolveThinClientFile:496-514` имеет две слепые ветки
отказа — `:505-508` возвращает null **без единой строки в лог**, `:510-513` пишет warn только в vibe.log;
наружу уходит generic текст `:704-707`. Асимметрия с сиблингами: `launch_app`/`update_infobase` после `b8ad7c7`
отдают `runtime_used`/`runtime_source`/`runtime_auto_resolved`, `yaxunit_run` — ничего. Просьба печатать
выбранную версию и пробованные пути висит с 03.07 и стоила 4 попыток.

Гипотезы проксимальной причины: **H1 ~45%** ghost-запись в реестре рантаймов (каталог беты 03.07 назывался
`…​.disabled`, сейчас — без суффикса) → newest-wins выбирает запись, файла нет; **H2 ~30%** `resolveExecutor`
не разрешается для генерации 8.5 под EDT 2025.2; **H3 ~20%** (contributing, не проксимальная) `UPDATE` вместо
`CLIENT_LAUNCH`; **H4 ~5%** bitness/AppArch. Расщепляется без сборки: `dry_run` + греп `Failed to resolve thin client`
в vibe.log (есть строка → `:510-513`, нет → слепая `:505-508`); `edt_launch_app(dry_run, mode=thin)` на том же
проекте (зелёный launch_app при красном yaxunit ⇒ H3-профиль, т.к. yaxunit не наследует `.launch`-пин);
поиск в EDT Preferences → Runtimes записи с несуществующим путём (H1).

Фикс: параметризовать `resolveInstallation` access-типом (клиентские пути → `CLIENT_LAUNCH`,
`buildUpdateCommand:731` остаётся `UPDATE`); в `resolveThinClientFile` вместо одного выстрела идти по
кандидатам `findUsefulForProjectAndInfobase(..., CLIENT_LAUNCH)` до первого, у которого `resolve(ThinClient,arch)`
+ `resolveExecutor` дают существующий файл; возвращать не `File`, а запись с
`versionWithBuild/location/source/candidatesTried/rejectReasons`; слепую ветку обязательно логировать.
Флага «pre-release» в API нет (`RuntimeInstallation` отдаёт только version/build/location/arch/isTraining),
поэтому отсев — политикой: `version_mask` → `.launch`-пин → `InfobaseReference.getVersion()` →
линия по CompatibilityMode проекта → newest. В `yaxunit_run` принять `runtime_version` как алиас к
`version_mask` (сейчас разнобой с `launch_app`/`update_infobase`/`connect_infobase` — отдельная ловушка)
и добавить `runtime_used{version,location,source}` в envelope, включая `dry_run` и текст ошибки.

### C3. Орфан `1cv8c` (нота 2026-07-14, открыта, те же строки)
`checkStaledClientProcesses:533-558` только предупреждает, kill нет; хуже — **фильтра нет вообще**:
скан по подстроке `1cv8c` по всем процессам машины (`:536-542`), без привязки к проекту/ИБ ⇒ на многостендовой
машине шумит всегда. Почему «terminating tree» не помог и PID выжил: `terminateProcessTree:394-407` убивает
только `process.descendants()`, а в логах ноты `descendants=0` на каждом heartbeat — реальный `1cv8c`
не потомок нашего процесса (запуск через лончер/reparent); дерево, которого нет, убить нельзя.
Всё нужное уже есть в репо — `InfobaseProcessScanner`: WMI-оверлей командных строк (`:196-238`, обязателен,
т.к. `ProcessHandle.info().commandLine()` на Windows пуст), `LockKind.CLIENT` для `1cv8c` (`:108-110`),
`fileIbPath:69-81` + `matchesIb:120-136`, образец `killPhantomDesigners(ibPath):257-272`.
Фикс = аналог `killTestClients(ibPath)` + вызов из preflight с fail-loud при неудаче.

### Решения по развилкам C (приняты, не пересматривать)
- фикстуры сделать для **обеих** форм нулевого отчёта (`<testsuites/>` и `<testsuite tests="0">`) — реального
  артефакта из провалившегося прогона нет, поэтому покрываем оба варианта, а не угадываем;
- `no_tests_matched` при `tags`/`suites` — **безусловно error**, без opt-out `allow_zero_tests`
  (не размывать поверхность; ложная тревога дешевле false-green);
- ноту 07-03 (красные ≠ error) закрываем **той же волной** — это одно изменение контракта;
- R2 из ноты 07-14 (`-Dynamic+` для непструктурных дельт) — **не** входит в волну, отдельный трек;
- порядок внутри C: сначала **наблюдаемость** C2 (S, dual-purpose — она же расщепляет H1/H2/H3),
  потом C1 (статусы), потом остальное C2 (`CLIENT_LAUNCH`) и C3.

### Риски C (требуют анонса, а не тихого деплоя)
1. Смена канала наблюдаема для AM-стороны: `total=0` станет `isError`, красные — перестанут. Плейбуки,
   ветвящиеся по `isError`, поедут в обе стороны.
2. Предложенный infra «1cv8c warm-up probe» (прогон с фиктивным фильтром) после фикса начнёт падать громко —
   для нас желаемое поведение, для них сломанный шаг launch-verify.
3. `CLIENT_LAUNCH` меняет резолв для **live-validated** дуги `qa_run` (7/7) ⇒ до вливания нужен повторный
   live-прогон qa_run. Поэтому C2 разносится на два коммита: наблюдаемость (безопасно) и смена access-типа
   (требует live-подтверждения).
4. `dry_run` обязан продолжать резолвить рантайм — иначе теряется единственный дешёвый диагностический вход.
5. Kill чужого `1cv8c` — самый опасный кусок: без `matchesIb` убьём клиент соседнего стенда; без WMI командная
   строка пуста ⇒ привязка невозможна ⇒ корректное поведение fail-loud, а не kill наугад.

---

## B. composite/containment metadata (BF-12936) + BF-13405 blocker

### B5 / BF-13405 — осиротевшая регистрация. Корень: ДВА индекса (S, blocker)
| Индекс | Что это | Где читает плагин |
|---|---|---|
| **A** — composition конфигурации | плоские типизированные списки из `Configuration.mdo`; в EMF это **non-containment** `refers X[]` на `Configuration` (`MdClass.xcore:2077-2340`) | `existsTopLevel:12582` (CommonModule → `:12590`), `topLevelCollection:8447`, `findTopLevel:8421`, `EdtMetadataIndexService.collectFromKnownCollections:332`, `EdtMetadataInspectorService.findMdObjectByFqn:156-183` |
| **B** — реестр FQN top-объектов BM | каждый `.mdo` на диске импортируется как свой top object, FQN регистрируется в namespace **независимо** от упоминания в `Configuration.mdo` | `attachTopLevelObject:12555` → `transaction.attachTopObject:12572`; проба `getTopObjectByFqn:12573`, `:12844` |

`create_metadata` идёт A → B: pre-check `existsTopLevel` (`:325`) по A говорит «отсутствует» → создаём →
`attachTopLevelObject` (`:332-335`) по B говорит «занят». Отсюда наблюдённая пара `exists:false` +
`FQN already in use`. Состояние на уровне BM **не битое**: объект корректно загружен и attached, не хватает
ровно записи в `Configuration.commonModules`.

**Почему код ошибки был `EDT_TRANSACTION_FAILED`, а не `METADATA_ALREADY_EXISTS`:** бросается
`BmFqnAlreadyInUseException`, а `executeWrite` ловит только `BmNameAlreadyInUseException` (`:13490`) — это два
сиблинга, оба `extends RuntimeException`, **без общего базового класса** (проверено `javap`) → падает в generic
`catch (RuntimeException)` (`:13499`). Этот мис-маппинг и сделал состояние загадочным вместо диагностируемого.

**Adopt-примитив в репо уже есть и обкатан:** `rebindTopLevelIntoConfiguration:12820-12865` (резолвит top object
из индекса B → `removeTopLevelObjectLinks` + `addTopLevelObject` в A → верифицирует `existsTopLevel`), и
`createMetadata` уже цепляет его с `forceExportTopLevelObject:355` + `verifyConfigurationEntryPersisted:357`
(текстовая проверка `<commonModules>FQN</commonModules>` через `containsConfigurationEntry:13294`).
`addTopLevelObject:12442` намеренно линкует **только типизированную коллекцию, не `<content>`** — что здесь и
правильно: в расширении `<content>` зарезервирован под адаптированные базовые объекты.
Варианты (b) снять регистрацию через BM API и (c) forced reindex — **отвергнуты**: (b) чинит обратную задачу
(объект нужен, а не лишний), (c) перечитает тот же `Configuration.mdo` без записи и воспроизведёт индекс A дословно.

**Аналогия с `c990eaa` подтверждается как зеркальная:** там объект был достижим по ссылке модели, но **не был**
резолвимым top-object'ом → forceExport молча пропускал; здесь объект **является** резолвимым top-object'ом,
но **не достижим** через ссылку Configuration. Переносится метод, не направление: **не доверяй одному индексу —
пробируй второй перед действием.**

**Кейс 5 и «Configuration.mdo flat-list registration conflict» — ОДИН корень, два триггера** (у одного git-мерж
уронил записи `<roles>`, у другого модуль вообще не регистрировали). Один adopt-verb закрывает оба, и он
**строго аддитивен** к одной коллекции — то есть снимает риск «`changes.set.roles` перезапишет список из ~1300 записей».

План: новая проба `findAttachedTopObject(tx, project, fqn, kind)` рядом с `existsTopLevel`; ветка adopt в
`createMetadata` сразу после гарда `:330` (без `createTopLevelObject`/`attachTopLevelObject`/`ensureUuidsRecursively`,
только `addTopLevelObject`); `adoptExisting` в `CreateMetadataRequest:8-15` (default `false` → громкий
actionable отказ); `catch (BmFqnAlreadyInUseException)` → `METADATA_ALREADY_EXISTS` с текстом, называющим
расхождение индексов — **делать даже если ветка adopt не поедет**, как честный backstop. Пост-транзакционная
цепочка `:354-357` правок не требует — отсюда размер S.

### B1 — composite `type.types` сворачивается в первый тип (M, риск med-high)
`TypeSpec:261-273` — запись на **один** тип, и `normalizeTypeLookupQuery` возвращает первый элемент, отбрасывая
остальные (`:10741-10749` для `List`, `:10759-10765` для `{types:[…]}`). Затем `setAttributeType:9739-9813`
строит **свежий одноэлементный** `TypeDescription` (`:9772-9773`) и `feature.setType` (`:9811`) — заменяя любой
существующий мультитип. Ошибки нигде → silent drop. Свёртка происходит и на стадии пре-резолва:
`collectTypeStrings:10673-10733` нормализует через тот же first-only хелпер.
Три call site с одним корнем, чинить вместе, иначе баг просто переезжает: `setFeatureValue:9637-9648` →
`setAttributeType` (`update_metadata` на любом `BasicFeature` — Dimension всех регистров, Resource, Attribute);
`applyDefaultTypeIfNeeded:8786-8835` (create-путь `add_metadata_child`); `applyFormAttributeType:4546-4663`
(form-атрибуты/параметры/колонки — причём `validateFormAttributeType:4665-4671` **рекурсивно валидирует весь
список**, а применяет один: классический validate-all/apply-one).
**DefinedType — другой путь и частично уже работает:** он `extends MdObject, TypeDescriptionProvider`
(`MdClass.xcore:2604-2609`), не `BasicFeature` ⇒ идёт в `applyReferenceValue:11346` →
`applyTypeDescriptionReference:11400-11424`, который `f184637` уже сделал мультитиповым (`extractTypeQueryList:11427-11441`),
**но без квалификаторов вообще** — `String(100)` теряет длину. Дешёвый probe до реализации: `update_metadata`
`DefinedType.<X>` с `type=["CatalogRef.A","CatalogRef.B"]` и чтение `.mdo` — два `<types>` ⇒ для DefinedType
осталось только квалификаторы.
Фикс: `normalizeTypeSpecList` (по одному `TypeSpec` на элемент, каждый со своими квалификаторами; старый
`normalizeTypeSpec` = `.get(0)`), общий `buildTypeDescription(...)` с **fail-loud на любом неразрешённом
элементе** (никогда не пропускать), три call site переключить, `collectTypeStrings` — на collect-all.
Прецедент мультитипового `TypeDescription` в репо: `createEventSubscription:424-455`. Новых параметров не нужно.
Митигация риска: 1-элементный путь обязан остаться байт-идентичным по поведению.

### B2 — ExchangePlan `<content>` (S/M, аддитивно)
`MdClass.xcore:2436` — `contains ExchangePlanContentItem[] content`, а `ExchangePlanContentItem:2490-2501` —
**плоский EClass, не `MdObject`, и без `name`**. Поэтому ни один шейп не адресуется: `children_ops` →
`findNestedChild:8500-8524` скипает всё, что `!(value instanceof MdObject)` (`:8512-8514`) и матчит по
`getName()` (`:8515`) → `METADATA_NOT_FOUND`; `changes.set.content=[…]` → `buildChildOpsFromContainmentSet:9475-9518`
требует `name` на каждой записи (`:9491-9493`) → пусто → `applyReferenceValue` → «Containment reference updates
are not supported in set» (`:11357-11361`). Вывод ноты «принимаемого шейпа не существует» буквально верен.
Фикс: ветка `isExchangePlanContentReference` перед throw в `:11346-11361`, строится по одному
`createExchangePlanContentItem()` на запись; `mdObject` через `resolveSingleReferenceValue:11696` (даёт FQN-резолв,
проверку совместимости и громкий `METADATA_NOT_FOUND` бесплатно), `autoRecord` — enum `AutoRegistrationChanges`
(`MdClass.xcore:2503-2507`), дефолт `Allow` **обязательно объявить в схеме**. Шейпы: `content:["Catalog.Foo",…]`
и `content:[{mdObject|object|fqn, autoRecord}]`.

### B3 — ExchangePlan `thisNode` (S, но семантика под вопросом)
У `ExchangePlan:2424-2482` **нет** predefined-коллекции вообще (она есть только у `Catalog:1503` и
`ChartOfCharacteristicTypes:1753`) — «предопределённые узлы» сводятся к одному скалярному
`Uuid[1] thisNode` (`:2426`). Писать его нельзя потому, что `Uuid` — `type Uuid wraps UUID` (`Mcore.xcore:33`),
а `convertAttributeValue:11752-11838` покрывает String/Integer/Long/Double/Float/Boolean/enum и затем **бросает**
(`:11835-11837`). Это не дефект `thisNode` — **любой экзотический `EDataType` модели неписуем**.
Фикс генерический: перед финальным throw добавить `EcoreUtil.createFromString(dataType, raw)` в try/catch
(проверено: `McoreFactoryImpl` объявляет `createUuidFromString`) — заодно открывает `QName`, `Shortcut`, `Version`
и т.д., и **сужает** «unsupported», а не расширяет поверхность.
**Решение владельца: `thisNode` плагином НЕ управляется** — это идентичность платформенного узла `ЭтотУзел`,
перезапись на живой ИБ переидентифицирует локальный узел (потенциально разрушительно для состояния обмена).
Ставим generic-fallback, а `thisNode` — в явный deny-list рядом с гардом `uuid` (`:9622-9626`) с честным
сообщением «assigned by EDT on first load; not plugin-managed» вместо нынешнего лживого «Unsupported value type».

### B4 — вложенный Subsystem content: канонический FQN ПЛОСКИЙ (S, low risk, высокий рычаг)
Прослежено до FQN-провайдера EDT: `MdQualifiedNameProvider` → `MdUtil.getFullyQualifiedName` читает
`eContainingFeature()`, и **если он `null`** → возвращает плоский двухсегментный `QualifiedName.create(eClass, name)`.
А обе коллекции подсистем в модели **non-containment**: `Configuration.subsystems` (`MdClass.xcore:2287`) и
`Subsystem.subsystems` (`:2553`, плюс `refers Subsystem parentSubsystem:2555`) ⇒ у вложенной подсистемы
`eContainer() == null` ⇒ её FQN — **`Subsystem.PaymentCalendar`**, и каждая подсистема сама себе top object
со своим `.mdo`.
Это объясняет все четыре репро: плоская форма была **канонически верной** и падала лишь потому, что
`findTopLevel:8421-8434` резолвит SUBSYSTEM через `topLevelCollection:8471` = `configuration.getSubsystems()`,
т.е. **только верхний уровень** (та же дырка делает `scan_metadata_index` слепым — `EdtMetadataIndexService:360`
без рекурсии); вложенные формы падали в `findNestedChild`, который итерирует **только containment**-ссылки
(`:8503`), а `subsystems` — non-containment.
Дальше вниз всё уже работает: `Subsystem.content` — `refers MdObject[]` (`:2550`) ⇒ путь
`applyReferenceValue:11364-11374`, уже подтверждённый контрольным тестом на верхнем уровне; и поскольку FQN
плоский, `extractTopLevelFqn:12077-12085` даёт правильный top object, так что EOL-guard/forceExport/verify
не требуют правок вовсе.
Фикс: рекурсивный обход в `findTopLevel` для SUBSYSTEM (**fail-loud при дубликате имени**, а не произвольный
выбор), терпимый алиас вложенной формы в `findNestedChild`, рекурсия в `scan_metadata_index:360` и
`existsTopLevel:12606`. **Решение владельца: документируем плоскую форму, вложенную принимаем как алиас.**

### B-бонус — `edt_metadata_details` даёт ложный `exists:false` для половины видов
`EdtMetadataInspectorService.findMdObjectByFqn:156-183` — захардкоженный switch на 9 видов
(catalog/document/commonmodule/informationregister/accumulationregister/report/dataprocessor/enum/constant)
с `default -> List.of()` (`:174`) и **без поддержки вложенных FQN** (читает только `parts[0]`/`parts[1]`).
Все прочие виды — `Subsystem`, `Role`, `ExchangePlan`, `DefinedType`, остальные регистры — получают ложный
`exists:false`. Это тот же дефект-класс, который `topLevelCollection:8447` был написан закрыть для
`update_metadata` (см. его javadoc `:8437-8446`). Фикс: вынести `topLevelCollection` в общий
`TopLevelCollections.forKind(Configuration, MetadataKind)` и подключить всех трёх консьюмеров — убивает целое
семейство ложных отрицаний и снимает третью расходящуюся копию маппинга вид→коллекция. S/M, высокий рычаг.

### Порядок внутри B
**B5 + фикс схемы** (blocker, оба S; схема уже сделана — коммит `13077db`) → **B4 + общий
kind→collection хелпер** (S, убивает семейство ложных not-found) → **B1** (M, самый частый) → **B2** → **B3**.

---

## F. dcs_create_main_schema — не персистится (M)

**Аналогия с `c990eaa` подтверждается полностью (~95%), но это «сценарий b» в чистом виде:**
`attachTopObject` не вызывается в `EdtDcsService` **ни в одной ветке**. Дефектный код —
`edt/dcs/EdtDcsService.java:197-202`: `DcsFactory.createDataCompositionSchema()` создаётся как сирота и
присваивается в `template.setTemplate(schema)`, а `BasicTemplate.template` — **transient, non-containment**
(декомпиляция `MdClassPackageImpl.initializePackageContents`, те же флаги, что у `Role.rights` и `BasicForm.form`;
`Report.templates`, наоборот, containment — его добавление корректно). `DataCompositionSchemaImpl` наследует
`BmObject`, т.е. `attachTopObject` применим.
Репо **само документирует этот текст ошибки**: javadoc `attachBootstrappedRoleDescription`
(`EdtMetadataService.java:5989-5996`) — «a bare `role.setRights(factory.create())` of an unattached EObject
commits to a "Failed to persist reference value" failure». Ground truth на диске (`tester/Reports/JobsSummary`):
в `.mdo` только inline `<templates>` + `<mainDataCompositionSchema>`, а содержимое схемы — **отдельный файл**
`Templates/Schema/Template.dcs`, т.е. отдельный top object; exporter этого ждёт (`DcsBmExporter.supports` →
`DATA_COMPOSITION_SCHEMA`, путь из `bmGetFqn` через `QualifiedNameFilePathConverter`, расширение по
`TEMPLATE_EXTENSIONS{DataCompositionSchema → "dcs"}`). Гипотеза ноты про `Template.mxl`-stub **опровергнута**
самим репро (попытка на чистом Report упала так же). Probe не нужен, диагноз детерминированный.

**Сопутствующие дефекты того же тула — дадут повторный отказ уже после главного фикса:**
- **нет force-export:** `EdtDcsService.executeWrite:687-703` делает только BM-транзакцию, тогда как весь
  `EdtMetadataService` после каждой мутации зовёт `forceExportTopLevelObject:12901-12953`, причём для внешних
  property-объектов — с `extraFqn` (`:5862`, комментарий `:5858-5860`: экспорт только владельца **не** пишет
  отдельный фрагмент). Здесь ровно тот же класс: `Report.X` и `Report.X.Template.Y.Template` — два файла.
  Касается и `upsertQueryDataset`/`upsertParameter`/`upsertCalculatedField`;
- **нет EOL-guard** (`8306b5b`): DCS-путь пишет `Report.mdo` без него → риск CRLF-флипа на LF-репозитории;
- **дубликат `<templates>`:** `:183-195` проверяет наличие схемы, но не занятость **имени** — в состоянии из
  ноты создастся второй `<templates>` с тем же именем; `force_replace=true` (`:184`) только добавляет и никогда
  не удаляет старый Template;
- **пустая схема нежизнеспособна:** `createDataCompositionSchema()` не создаёт `dataSource`, а реальный `.dcs`
  всегда содержит `<dataSource><name>DataSource1</name><dataSourceType>Local</dataSourceType></dataSource>`
  (`dataSourceType` — строка, не enum).

**Порядок операций в фиксе критичен** (иначе `AssertionFailedException` вместо внятной ошибки): сначала
`setName` + `setTemplateType(DATA_COMPOSITION_SCHEMA)` + `templates.templates().add(template)`, **только потом**
`generateExternalPropertyFqn(template, MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE)` — FQN считается по
цепочке контейнеров, вне списка Report'а он не вычислим. Дальше: namespace через `getBmModelManager`,
защитный reuse через `getTopObjectByFqn`, `attachTopObject`, **перечитать** объект из транзакции и писать в
ссылку только его (как `:6037-6044`), затем создать дефолтный `dataSource`. Геттеры уже есть на
`EdtMetadataGateway` (`:153`, `:81`, `:90`). `report.setMainDataCompositionSchema(template)` (`:205-211`)
остаётся как есть — фича не transient и указывает на `BasicTemplate`.
Плюс: идемпотентность по имени, force-export с `extraFqn`, EOL-guard, и возврат честного состояния
(проверка существования `Templates/Y/Template.dcs`, аналог `describeRightsFileState:5870`) — иначе тул снова
сможет отрапортовать успех без файла.

**Риск:** вынос `forceExportTopLevelObject` в общий хелпер трогает горячий путь всего `EdtMetadataService`
(~30 вызовов). Если бюджет узкий — продублировать в `EdtDcsService`, дедупликацию отдельным рефакторингом.
Также `force_replace` меняет наблюдаемое поведение (было «добавить», станет «заменить») — но текущее дефектно.
Побочно найдено: `resolveTemplateType:7664-7693` при неизвестном/отсутствующем значении молча даёт
`SPREADSHEET_DOCUMENT` (`:7692`) — отсюда 13-байтный `Template.mxl`; правильное имя параметра — top-level
`template_type` (`"dcs"`/`"скд"`), а `properties.templateType` игнорируется молча. И латентное расхождение
расширений: `TEMPLATE_EXTENSIONS` мапит spreadsheet → `mxlx`, а `ensureTemplateArtifact:6421-6422` пишет `.mxl`
(вне этой задачи, отдельный issue).

---

## Остатки на момент прерывания разведки (все закрыты выше — оставлено как след)

- **B / BF-13405 (blocker, stack-3 стоит):** `CommonModule.CM_Integration_GoogleDrive` есть на диске с валидным
  `.mdo`, но нет в EDT-модели; `exists:false`, а `create_metadata` того же FQN → `FQN already in use`;
  `update_infobase` падает `Unknown metadata object …`. Нужно: где расходятся плоский список регистраций
  `Configuration.mdo` и загруженные top-object'ы BM (это, вероятно, два разных индекса); есть ли плагинный путь
  (adopt-existing по образцу `connect_infobase` NAME_COLLISION / снятие осиротевшей регистрации / force reindex);
  проверить аналогию с `c990eaa` (`rights_manage` переиспользовал осиротевший объект без `attachTopObject`);
  один ли это корень с «Configuration.mdo flat-list registration conflict»; **и обходной путь руками для
  разблокировки stack-3 до выхода билда**.
- ~~**C / yaxunit**~~ — выяснено, см. раздел «C. yaxunit_run» выше.
- **F / dcs_create_main_schema:** проверить аналогию с `c990eaa` (создан, но не attached к top-object).

## Addendum — живая валидация билда `0.1.7.20260728-1704` (2026-07-28, вечер)

Прогон против реального EDT (песочница, версия подтверждена маяком `get_workspace_state`). Подробности
в `CHANGELOG.md`; здесь только то, что меняет картину разведки.

**Разведка оказалась неполна в двух местах** (оба закрыты коммитом `b936996`):

1. **Сообщение об ошибке на точечном FQN подсистемы уводит не туда.** Живьём: `Subsystem.<Parent>.<Child>` →
   `METADATA_PARENT_NOT_FOUND` («must be marker/name pairs»), т.е. вызывающего посылают дописать маркер;
   парная `Subsystem.<Parent>.Subsystem.<Child>` в моём прогоне тоже упала (`METADATA_NOT_FOUND`).
   **УТОЧНЕНИЕ (реализация волны 2 опровергла мою первую трактовку):** парный алиас НЕ сломан — он обходит
   `parent.getSubsystems()`, который был пуст ровно из-за полулинка §F1, потому что вложенность я создал сам
   через `set.parentSubsystem`. На EDT-авторской модели (98/22) алиас резолвится. Вывод не меняется —
   каноническая и всегда работающая форма плоская, а текст ошибки надо править; но диагноз «точечная форма
   падает сама по себе» был неточен, и это урок: не делай вывод о резолвере на данных, которые сам же создал
   сломанным путём.
2. **`edt_metadata_details` на длинном FQN возвращал ЧУЖОЙ объект.** Он вычитывал ведущую пару
   `<Type>.<Name>` из длинного FQN и отдавал найденное: запрос `Subsystem.<Parent>.<Child>` приходил с
   свойствами **родителя** под запрошенным `Path`. Это хуже ложного `exists:false`, который тот же метод
   починили не выдавать (§B-бонус) — у вызывающего вообще нет сигнала.

**Новая находка, которой в разведке не было (§F1, чинится в волне 2):** EDT пишет вложенность подсистем
**с двух сторон** — у родителя `<subsystems>Child</subsystems>` голым именем, у ребёнка
`<parentSubsystem>Subsystem.Parent</parentSubsystem>` плоским FQN (ground truth: 132 `.mdo` в
EDT-авторском проекте, 98 с `parentSubsystem`, 22 с `subsystems`). Наш `set.parentSubsystem` пишет только
детскую сторону ⇒ вложенность полулинкованная, родитель ребёнка не видит.

**Сужение старой загадки с автовыбором пре-релиза (§C2).** На этой машине установлена `8.5.1.1302`, но auto
выбрал `8.3.27.2074` и `candidates_tried` содержал ровно один элемент; явный `runtime_version=8.5.1`
резолвится (`source=param`). Значит симптом на стенде идёт не от «резолвер берёт новейшее», а от пина
проекта или preferred runtime самого EDT — запрошен блок `runtime_used` со стенда. Оговорка: в auto-режиме
`candidates_tried` перечисляет только опробованные установки, поэтому «отвергнута» и «не перечислена»
неразличимы.

**Приоритет причин при нуле тестов:** `infobase_stale` выигрывает у `filter_matched_nothing` (проверено
заведомо мусорным фильтром на stale-ИБ), сам фильтр возвращается эхом. `filter_matched_nothing` увидим
только на EQUAL-ИБ.

### Baseline B1 на билде 1704 (снят до установки фикса, для решающего сравнения)

`add_metadata_child` `Catalog.Catalog.Attribute.WaveComposite` с `type=["String","Boolean"]` на билде **1704**
(т.е. ДО коммита `9d6c354`) записал в `Catalog.mdo`:

```xml
<attributes>
  <name>WaveComposite</name>
  <type>
    <types>String</types>
    <stringQualifiers><length>150</length></stringQualifiers>
  </type>
</attributes>
```

Один `<types>`, второй тип потерян, ответ инструмента — успех без единого предупреждения. Это silent drop
в чистом виде. После установки билда с `9d6c354` тот же вызов обязан дать **два** `<types>`; сравнение с
этим слепком и есть проверка фикса.

### Кластер A (BF-13330) — ПРОВЕРЕН ЖИВЬЁМ 2026-07-29, закрыт

Сценарий воспроизведён штатно: `create_form usage=LIST` на `Catalog.Catalog` дал форму с реквизитом `List`
типа `<types>DynamicList</types>`.

- **Плоские ключи работают и доезжают до диска.** `apply_form_recipe attributes:[{name:"List",
  action:"update", set:{customQuery:true, queryText:"…"}}]` → в `.form` появились
  `<extInfo xsi:type="form:DynamicListExtInfo">` с `<queryText>` и `<customQuery>true</customQuery>`.
  Это ровно тот отказ, который описывала нота. Обходной путь через вложенный `extInfo` больше не нужен.
- **`add_button` по квалифицированному `Form.Command.<Name>` работает** (`add_command` + `add_button` одной
  пачкой, 2 операции применены).
- **`add_button` по FQN общей команды отказывает честно:** сообщение называет поддерживаемые формы (голое имя
  и `Form.Command.<Name>`), прямо говорит, что объектные и общие команды пока не поддержаны, и даёт обход
  (добавить форменную команду и вызвать общую из её обработчика). Это осознанная граница, а не дефект.

Наблюдение (низкая важность, НЕ чинилось): у `add_button` документированный параметр `name` **не
соблюдается** — я передал `name="WaveBtn"`, кнопка получила имя `FormWaveFormCmd` (EDT называет кнопку по
команде), и `WaveBtn` в `.form` отсутствует. Смягчает то, что **результат вызова честно возвращает
фактическое имя и id** (`add_button[2]: name=FormWaveFormCmd, id=24`), так что вызывающий не остаётся в
неведении; но последующий `set_item item_name="WaveBtn"` даст not-found. Либо соблюдать `name`
(переименовывать после генерации), либо убрать его из контракта `add_button`.

### НОВЫЙ ДЕФЕКТ: `create_form` отдаёт `FORM_MATERIALIZATION_TIMEOUT` на УСПЕШНОЙ операции (M, волна 3)

Найдено живьём 2026-07-29 при попытке провалидировать кластер A. `create_form` на `Catalog.Catalog`
(`usage=LIST`, `wait_ms=15000`) вернул жёсткую ошибку
`FORM_MATERIALIZATION_TIMEOUT ... ownerMdo=<project>\Catalog.Catalog, ownerMdoExists=false, ownerHasFormEntry=false`,
**при полностью успешной материализации**: на диске появился каталог `src/Catalogs/Catalog/Forms/WaveListForm`,
а в `Catalog.mdo` — и `<forms><name>WaveListForm</name></forms>`, и
`<defaultListForm>Catalog.Catalog.Form.WaveListForm</defaultListForm>`.

Корень: `resolveOwnerMdoWorkspacePath` (~`:9008`). **Внешняя** ветка (`:9020`) фильтрует результат
`toProjectRelativePath` через `isUsableMetadataResourcePath` + суффикс `.mdo`, а ветка **базовой
конфигурации** (`:9030-9041`) возвращает результат как есть. Для обычного top-объекта URI не
platform-resource, поэтому `toProjectRelativePath` (`:8081`, без фильтра вообще) отдаёт `Catalog.Catalog`.
Значение не `null` ⇒ `waitForFormMaterialization` (`:7911-7927`) **пропускает свой корректный fallback**
`src/<folder>/<name>/<name>.mdo` и пробит `project.getFile("Catalog.Catalog")`, которого не существует
никогда ⇒ цикл ожидания не может завершиться успехом и после полного таймаута бросает (`:7980`).

Ложное отрицание на мутации — вызывающий видит hard error после успешной записи и логично пойдёт
ретраить/откатывать. Путь ожидания обязателен (`request.effectiveWaitMs()`, `:825`), т.е. обойти его
параметром нельзя.

Фикс (мал и локален): применить в не-внешней ветке ту же проверку, что во внешней — возвращать путь
только если `isUsableMetadataResourcePath(...) && endsWith(".mdo")`, иначе `null`, чтобы сработал уже
написанный корректный fallback. Тест: гард отклоняет FQN-подобную строку и принимает
`src/Catalogs/X/X.mdo`.

### Припаркованное (найдено попутно, НЕ чинилось в этой волне)

- **Неизвестный параметр игнорируется молча — воспроизведено дважды за одну сессию, оба раза я сам
  наступил.** (1) `scan_metadata_index`: фильтр называется `scope`, а вызов с `kinds='Subsystem'` вернул
  все объекты проекта без предупреждения, то есть выглядел отфильтрованным. (2) `get_diagnostics`: имя
  проекта — `project_name`, а вызов с `project='TestConfiguration'` был воспринят как «не передан» и
  сработал документированный fallback на дефолтный проект, так что ответ пришёл по `Accounting management`.
  Во втором случае вывод **честен**: заголовок называет проект, по которому реально отвечали
  (`## Diagnostics: /Accounting management`), — так что это не молчаливая ложь, а именно проглоченный
  параметр. Тот же класс, что закрытый footgun `grep` (`e01c1df`, честный хинт на нулевой матч):
  правильное лечение — не строгий отказ на любой лишний параметр, а честный хинт, когда неизвестный
  параметр похож на алиас существующего (`project`→`project_name`, `kinds`→`scope`). Разница в цене
  ошибки: у `scan_metadata_index` вывод не раскрывает, что фильтр не применялся.
- **`origin` в `get_diagnostics` живьём не проверяем в этой песочнице.** Параметр на месте и его схема
  описывает ровно нужную семантику (дефолт `diagnostics` исключает review-аннотации, `all` выносит их в
  отдельную секцию), но выставляет эти маркеры **другой** плагин (commit-review), а в песочном EDT он не
  установлен — исключать нечего. Проверка требует стенда с обоими плагинами.

## Addendum round 3 — live 2026-07-29 утро, билд `0.1.7.20260729-0028`

Прогон по двум «дефектам round 2» из снапшота ротации. **Один из них дефектом не является**, и это
меняет диагноз на противоположный.

### B1 create-путь ИСПРАВЕН — заявление «B1 не покрыл create-путь» РЕТРАКТИРОВАНО

Живая проба на том же билде, где round 2 видел потерю:

| вызов | результат в `Catalog.mdo` |
|---|---|
| `add_metadata_child` … `properties:{type:["String","Boolean"]}` → `WaveProbeB1` | **два** `<types>` (String + Boolean) |
| `add_metadata_child` … `type:["String","Boolean"]` **на верхнем уровне** → `WaveProbeB2` | один `<types>String</types>` + `<length>150</length>` |

Общий сборщик, `TypeValueSplitter`, `normalizeTypeSpecList(properties)` и `applyDefaultTypeIfNeeded`
работают как задумано. Потери «между `:9454` и `:9471`» нет — искать её не надо.

Round 2 наступил на **footgun вызова**: `type` был передан верхним параметром вместо `properties.type`.
Совпадение результата с baseline 1704 (`String` + `length 150`) объясняется не сохранившимся дефектом,
а тем, что оба раза сработала одна и та же ветка «тип не запрошен → дефолт `String`», а 150 — это
fallback-длина строки (`:10731`), а не «потерянная» сотня. Признак «в логе нет `Auto-assign default type`»,
на котором держалось сужение, доказанно не выдержал живой проверки.

Тот же урок, что у закрытого footgun'а `grep` (`e01c1df`): **сначала ноль-сборочная проба, разделяющая
гипотезы, потом фикс.** Здесь она стоила два вызова и отменила целую задачу.

### Настоящий дефект в этом сюжете — молчаливое проглатывание неизвестного параметра

`edt_validate_request` с `type` на верхнем уровне вернул `valid:true`, **выбросил ключ из
`normalizedPayload`** и выдал токен; текст checks при этом заявлял, что операция валидирована. Дальше
`add_metadata_child` получил пустые `properties`, применил дефолтный тип и отчитался полным успехом.

То есть опечатка в имени параметра ⇒ **на диск записаны неверные метаданные + отчёт об успехе**. Это
третий живой экземпляр класса, который в разведке лежал в «Припаркованном» (`scan_metadata_index kinds`,
`get_diagnostics project`), и у него самый тяжёлый радиус: там вывод оставался честным, здесь — мутация.
Поднято в волну 3 первым приоритетом, чинится двумя слоями (L2 — отказ в `edt_validate_request` до выдачи
токена, L1 — advisory в `AbstractTool.execute` для read-only инструментов).

### F1 неидемпотентность ПОДТВЕРЖДЕНА, корень подтверждён наблюдением

Два одинаковых `update_metadata set.parentSubsystem` на `Subsystem.WaveChild`:

```
после 1-го:  <subsystems>WaveChild2</subsystems> ×2   <subsystems>WaveChild</subsystems>
после 2-го:  <subsystems>WaveChild2</subsystems> ×2   <subsystems>WaveChild</subsystems> ×2
```

Первый вызов добавил ровно одну запись (то есть `addSubsystemChild` сам по себе исправен), второй —
дублировал. `edt_metadata_details` на `Subsystem.WaveParent` рендерит
`subsystems | [Subsystem, Subsystem, Subsystem, Subsystem]` — все четыре записи безымянны, тогда как у
ребёнка `parentSubsystem | Subsystem.WaveParent` и `content | [Catalog.Catalog]` читаются нормально.
Значит записи родительской коллекции — неразрешённые ссылки, и `sameSubsystem` (`:10548`, по `getName()`)
на них не матчит никогда.

**Чем именно они являются — не установлено, и на догадке фикс строить нельзя.** Варианты идентичности:
EMF-прокси (тогда работает `eProxyURI`), BM-объект (`bmGetId`), либо разрешаемый через `EcoreUtil.resolve`.
Различить их без живого прогона нельзя, поэтому в волну 3 идёт **диагностическая логика** (класс записи,
`eIsProxy`, `eProxyURI`, `bmGetId`, `getName`), а сам фикс — следующим билдом по её выхлопу. Песочница
самодостаточна, так что диагностический раунд не стоит владельцу ничего
(метод: `edt_diagnostic_build_round_method`).

Отвергнутая на этом этапе альтернатива: перестроить `parent.getSubsystems()` из авторитетной детской
стороны (все подсистемы, чей `parentSubsystem` указывает на этого родителя). Идемпотентно по построению и
само вылечило бы уже накопленные дубли, но **деструктивно** на чужой конфигурации: ребёнок, перечисленный
у родителя без обратного указателя, был бы молча выкинут. Не годится как поведение по умолчанию.

### Побочная находка: четыре брошенных agent-worktree с незамёрженными коммитами

`git worktree list` показывает четыре живых worktree под `.claude/worktrees/` (каталог gitignored, поэтому
в `git status` их не видно) — остатки субагентов от 2026-06-08. У каждого один коммит, **не являющийся
предком `HEAD`**:

| коммит | тема |
|---|---|
| `7eda3af` | `update_infobase`: детект `IB_LOCKED` вместо misleading `xml.zip` |
| `bd1b319` | `connect_infobase`: идемпотентный rebind того же пути под `force=true` + таймаут 300s |
| `d18ed5f` | docs: описание `set_item` + хинт `NAME_COLLISION` |
| `33e0457` | `add_metadata_child`: inline length/precision через properties map |

**Все четыре с высокой вероятностью перекрыты.** База у них — `2b30af5` (`main`, 23 апреля), то есть они
ветвились от `main`, а не от девелоперской линии, и разошлись с ней на два месяца. Проверено точечно:
`IB_LOCKED` уже на ветке (9 вхождений в 4 файлах, включая тесты), идемпотентный reconnect
`connect_infobase` закрыт в волне 2026-05-29, а inline-квалификаторы закрыты сегодня и шире
(`{type:"String(100)"}` + каждый элемент composite-списка).

**Worktree НЕ удалялись** — ценность нулевая, но и риск удаления ненулевой, а срочности нет; решение об
удалении за владельцем. Урок процедурный: субагент в worktree, умерший до отчёта, оставляет коммит,
которого не видно ни в `git status`, ни в `git log` ветки — то есть штатный признак «дерево чистое»
его не покрывает.

### Тестовый мусор, добавленный этим раундом

`Catalog.Catalog.Attribute.WaveProbeB1` (два типа, эталон «как надо») и `WaveProbeB2` (один тип, эталон
footgun'а) — оставлены как живые слепки для сравнения. `Subsystem.WaveParent` теперь содержит `WaveChild2`
×2 и `WaveChild` ×2 — это репро-состояние для проверки будущего фикса F1 (он обязан уметь его вылечить
или хотя бы не усугублять).

## Открытые вопросы владельцу

1. Авто-генерация `queryText` при `customQuery=true` без явного текста (как делает редактор EDT) или требовать
   явный `queryText`? Рекомендация разведки: генерировать, но всегда возвращать сгенерированный текст в summary.
2. `mainTable` — в этот фикс (ценой нового `Import-Package` и зависимости от derived data) или фазой 2?
   Рекомендация: включить, но отдельным коммитом.
3. `fields/calculatedFields/parameters/listSettings` — подтвердить вынос за скоуп с честным отказом
   (обоснование: платформа выводит поля из запроса при `autoFillAvailableFields=true`).
4. Новый op `set_attribute_props` — добавлять ли (снимает корневую ловушку `set_item`).
5. `add_button` для объектных/общих команд — технически возможно (`Button.setCommandName` принимает
   `mcore.Command`); сейчас отказ или отдельная фича?
6. Неявное переиспользование кред из `infobase_connection` для inline-probe — согласен ли владелец.
