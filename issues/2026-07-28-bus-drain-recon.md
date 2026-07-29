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

## Addendum round 11 — BF-12843 `update_infobase`: три канала врали (`c8cc713`)

Взято с шины (`e0fc4f21` + `d63d5d9d`), нота — `codepilot1c-feedback/2026-07-29-update-infobase-timeout-s-accepted-retest-and-dynamic-only-masking.md`.

**§3 — смена контракта (решение владельца).** `dynamic_only:true` больше не приезжает вместе с
`updated:true`: `updated=false`, `status="partial"`, и `schema_applied` присутствует на **каждом** исходе
тула (включая error и async-приём), чтобы отсутствие поля не приходилось читать как значение. Сам вызов
остаётся успешным: динамический апдейт реально применяет BSL-код, и для правки без схемы это полный
результат — уводить его в error-канал было бы ложью в другую сторону. Жёсткий отказ по-прежнему привязан к
вердикту самого EDT. EQUAL-skip — единственный исход, где два поля законно расходятся (не применено ничего,
схема живая), и ровно поэтому `schema_applied` нельзя вывести из `updated`.

**§4 — потолки: причина оказалась НАШЕЙ, и лечится полностью.** «~660s» — не транспортное ограничение
платформы, а наш собственный пер-тул кап в `McpHostRequestRouter` (там же `qa_run` = 3600). Поднято до 1860s
для `update_infobase_status` при собственном потолке ожидания 1800s = максимум `timeout_s` у
`update_infobase`; `connect_infobase_status` оставлен на 660s (минимальный радиус). Истёкшее ожидание и раньше
возвращало `success` + `timed_out:true` — значит ложный `failed` приходил именно от транспортного капа. Теперь
payload дополнительно говорит, ЧТО истекло, и что это не вердикт.

**ask #2 — расхождение «память vs нота» РАЗРЕШЕНО, и обе стороны были правы наполовину.** PID Designer'а
в payload есть с `3ca38df` (16.07), а ретест 29.07 подтверждает текст ошибки из того же коммита — значит билд
фичу нёс. Но `processTimeoutDetails` при пустом скане **просто опускал ключ**, а это неотличимо от «билд без
фичи» (у этого поведения даже был зелёный тест `timeoutDetailsOmitPidKeyWhenNoDesignerFound`). Так что нота
наблюдала пустой скан, а не отсутствие фичи. Починено то, что действительно можно починить не угадывая:
пустой результат теперь произносится вслух (`designer_scan: no_designer_bound_to_this_infobase`), а сообщение
признаёт, что процесс с нечитаемой командной строкой в принципе не атрибутируется к ИБ.

**ДОКАЗАНО payload'ом (`0f9aa75c`, тот же вечер) — и сканер был ПРАВ.** Орк прислал сохранённый result той
попытки: `error_code: PROCESS_TIMEOUT`, `timeout_s: 300`, `details: {}`. Такая форма возможна ровно в одном
случае — `processTimeoutDetails` положил только `update_timeout_s` (он и приезжает отдельным полем `timeout_s`),
а PID-ключ не положил из-за пустого списка; билд без фичи не дал бы и `timeout_s`. И главное: живые `/AgentMode`
и `testclient` в тот момент сидели на путях `File_am` / `File_am_sandbox`, а **не** на `BF-12843` — infra это
подтвердила отдельно. Значит скан отработал верно: holder'а у целевой ИБ не было, `PROCESS_TIMEOUT` означал
именно «всё ещё реструктурирует».

Отсюда две поправки к формулировкам ноты, чтобы они не поехали дальше как факты: (1) **посылка ask #2 в том
прогоне не наступала** — «Designer, которого джоба породила, держит лок» на `BF-12843` не было, убитый фантом
сидел на другой ИБ; сам ask выполнен ещё `3ca38df`, и этот прогон его не опровергал; (2) ведущая гипотеза
сообщения («держит другой процесс») была ложным следом дважды именно потому, что holder'а не существовало —
это довод за формулировку, а не за подъём дефолта (ask #3), и орк с оставлением дефолта согласился.

Побочная ловушка, стоит помнить: `errorPayloadFrom` — **allowlist** ключей `details`. Ключ без своей ветки
там до payload'а не доезжает; маркер пустого скана пришлось рендерить явно, иначе поле, существующее ради
устранения двусмысленности, само бы молча потерялось.

**ask #3 (дефолт 300s) — НЕ менялся.** Сообщение `PROCESS_TIMEOUT` уже называет `timeout_s` и ставит
«всё ещё реструктурирует» первой версией; поднимать дефолт — значит удлинять слепое ожидание для всех
остальных вызовов. Оставлено как есть сознательно, не забыто.

### Живая валидация round 11 — договорённость с орком (ОБЯЗАТЕЛЬНА к соблюдению следующей сессией)

Пути апдейта требуют настоящей ИБ, метаданной песочницы (8763) мало. Стенд согласован: `File_am_BF-12843`
(`C:\1C\Dudko\db\Branches\BF-12843`, stack-1, EDT workspace `workspace-agent`) — файловая ИБ 1.51 GB со
свежепройденной схемной правкой. Билд для проверки зафиксирован: **`0.1.7.20260729-2120`** (HEAD `d86488a`,
код пакета `c8cc713`), лежит в `repositories/com.codepilot1c.update/target/repository/`.

**ЖЁСТКОЕ УСЛОВИЕ (переписка `e22f1e6a` / `e3969300`): стенд НЕ трогать** до выполнения ОБОИХ условий:
(1) владелец закрыл визуальную приёмку UI-навигации на этом стенде, (2) орк `orch-bf12843` **явно** передал
стенд сообщением на шину. Причина: плагин на stack-1 **общий для четырёх профилей** (`orchestrator:8775`,
`dev:8776`, `qa:8777`, `infra:8778`) — установка билда там мутирует общую инфраструктуру стека, а не чью-то
песочницу. Орк держит claim за BF-12843 и притормаживает финализацию; если приёмка затянется — он финализирует
и напишет, что валидация переносится на следующий стенд (ничего не теряется, билд зафиксирован).

Окно нужно **одно, ~30 мин работы / час с запасом**. Порядок проб (короче, чем кажется — п.1 закрывает сразу два):

1. **§4 + §3-happy-path одним длинным апдейтом.** `update_infobase async=true timeout_s=1200`, сразу
   `update_infobase_status(job_id, wait_for_completion=true, timeout_seconds=1500)`. До фикса такой вызов умирал
   по транспорту около 660s и выглядел как `failed`; теперь должен додержать (~13–15 мин) и отдать вердикт за
   один вызов, а payload — нести `schema_applied:true`, `status:"updated"`, без `dynamic_only`.
2. **§3 EQUAL-skip** — повтор с `skip_if_current=true`: `updated:false` **и** `schema_applied:true`,
   `status:"skipped"`. Самый ценный пункт: именно этот исход ломает старые гейты на `updated:true`.
3. **§3 dynamic_only** — специально не воспроизводить. По словам орка приходит сам (фантомный `/AgentMode`
   respawn'ился с новым PID трижды подряд); если поймается — ожидается `updated:false`, `schema_applied:false`,
   `status:"partial"`, `dynamic_only:true`.
4. **Пустой скан** — только если таймаут случится сам: в `details` должен быть
   `designer_scan: no_designer_bound_to_this_infobase`.

Резервный путь, если владелец скажет «не на моём стеке»: дождаться отдельной ИБ. Орк держит его в резерве.

## Addendum round 10 — up-link потомков закрыт и ЖИВЬЁМ подтверждён (`fcc6c7b` + `27349cc`)

Закрывает единственный открытый дефект round 9. Диагноз round 9 подтвердился по коду полностью, без поправок.

**Что было.** Каскад round 9 перерегистрировал FQN каждого потомка, но `parentSubsystem` потомка оставался
прокси, зарезолвленным против СТАРОЙ цепочки владельца. На диске: `WaveR9C.mdo` →
`<parentSubsystem>Subsystem.WaveR9P</parentSubsystem>`, тогда как родитель стал
`Subsystem.WaveParent.Subsystem.WaveR9P`. EDT рисует `parentSubsystem | Subsystem` — заглушка.

**Почему ручной ремонт молчал.** `SubsystemIdentity.of` — идентичность по ЛИСТОВОМУ ИМЕНИ (так и надо для
membership-тестов: в коллекции родителя лежат безымянные прокси, у которых кроме имени в URI ничего нет).
Но висячий прокси `Subsystem.WaveR9P` и живой `Subsystem.WaveParent.Subsystem.WaveR9P` дают ОДНУ И ТУ ЖЕ
строку `waver9p`. Поэтому `sameSubsystem(oldParent, newParent)` отвечал «тот же», `setParentSubsystem`
пропускался, а `update_metadata` возвращал SUCCESS с неизменённым файлом. Это ещё один экземпляр класса
«success = ничего не сделано».

**Фикс.**
1. `SubsystemTree.Relocation` получил компонент `parent` — ЖИВОЙ объект-владелец узла (для верхнего уровня
   плана это сама переезжающая подсистема, глубже — узел плана выше). `descendantRelocations` теперь принимает
   owner-узел, а детей берёт через `childrenOf`.
2. `relocateSubsystemDescendants` после перерегистрации звёт `repointParentSubsystem(descendant,
   relocation.parent())`. Передаётся именно живой ОБЪЕКТ, а не пересчитанная строка: сериализатор пишет то,
   во что резолвится ссылка.
3. **Обе ветки** переуказывают up-link, включая ветку «FQN уже правильный». Это не избыточность: потомок с
   верным FQN и неверным указателем — ровно то, что оставил после себя предыдущий каскад, поэтому эта ветка и
   есть механизм ЛЕЧЕНИЯ уже испорченного дерева повторным переездом.
4. Решение «указатель уже верен?» переведено на ЦЕПОЧЕЧНУЮ сверку — `SubsystemIdentity.chainOf` / `sameChain`
   (живой BM-FQN, иначе URI прокси). Именная идентичность оставлена membership-тестам без изменений. Два
   правила названы по-разному сознательно: их слияние и породило дефект.
5. Ошибка записи up-link логируется, а не бросается: перерегистрация FQN уже сделала объект адресуемым, и
   аборт транзакции обменял бы неверный указатель на неадресуемого потомка.

**Тесты — по результату.** Пара «висячий плоский прокси vs живая вложенная цепочка», которая обманывала
именную идентичность, стала регрессионным тестом; плюс fallback FQN→URI, регистронезависимость, отсутствие
префиксного совпадения, нечитаемая цепочка (сверка отвечает «не равны» ⇒ указатель ПИШЕТСЯ — восстанавливающее
направление), и проводка `parent` в плане. Source-contract пинит только то, чего юнит-тест не видит: что
переуказывают ОБЕ ветки и что гейт — цепочечная сверка.

### Живая проба вскрыла, что фикса было НЕДОСТАТОЧНО — гейт стоял уровнем выше (`27349cc`)

Первая проба на билде с `fcc6c7b`: повторный переезд `WaveR9P` под `WaveParent` → SUCCESS, **файлы не
изменились**. То есть ровно тот же симптом, ради которого фикс и писался.

**Корень.** `relocateSubsystemStorage` делал **ранний выход** на «владелец уже зарегистрирован в целевом
слоте» — и делал его ДО построения плана потомков. Значит весь проход по поддереву был загейтен на смену
FQN САМОГО ВЛАДЕЛЬЦА, а в лечащем сценарии он не меняется по определению. Лечащая ветка внутри
`relocateSubsystemDescendants` была **недостижима** уровнем выше. Лог плагина назвал это прямо: co-edited
сообщён только владелец ⇒ поддерево не обходилось вовсе.

**Почему это не поймали тесты.** Source-contract утверждал «обе ветки переуказывают» — и это ПРАВДА, метод
просто не вызывался. Ещё один экземпляр `source_contract_tests_dont_see_behavior`: утверждение о наличии
текста в исходнике не видит недостижимости. Новый тест пинит именно порядок: план строится до раннего выхода,
и проход по поддереву есть в ОБЕИХ ветках.

**Фикс.** План строится раньше ветки, а сама ветка вместо голого `return` зовёт проход по поддереву. Проход
идемпотентен по FQN, поэтому повторный переезд стоит один обход и пишет только неверное.

### Живая валидация — билд `0.1.7.20260729-2109`, `TestConfiguration` (проверялись .mdo, не ответ тула)

| направление | объект | было | стало |
|---|---|---|---|
| лечение | `WaveR9C` | `Subsystem.WaveR9P` | `Subsystem.WaveParent.Subsystem.WaveR9P` |
| лечение | `WaveR9G` | `Subsystem.WaveR9P.Subsystem.WaveR9C` | `Subsystem.WaveParent.Subsystem.WaveR9P.Subsystem.WaveR9C` |
| вперёд | `WaveR10P>C>G` (свежее дерево) | корень + плоские цепочки | все три `.mdo` по вложенному пути, у каждого полная цепочка |

Внук несёт ПОЛНУЮ цепочку — значит рекурсия передаёт живых владельцев, а не устаревшие прокси (это была
главная неопределённость дизайна). `edt_metadata_details` по плоскому алиасу `Subsystem.WaveR9G` и
`Subsystem.WaveR10G` резолвится и отдаёт верный up-link — адресуемость не пострадала.

**Что осталось.** Пустые каталоги `src/Subsystems/<X>/Subsystems/` после переезда — по-прежнему не чинены
(решение round 10: косметика, delete-walk вглубь несёт риск). Свежая проба подтвердила, что вакантный
`src/Subsystems/WaveR10P/**` остаётся как пустое дерево каталогов без единого файла.

## Addendum round 9 — каскад по потомкам закрыт, up-link вскрыт (билды `1142` → `1206`)

### Что было измерено на билде `1142` (проба ДО фикса)

Гипотеза round 8 («`updateTopObjectFqn` перерегистрирует только тот объект, которому его дали») —
**подтверждена живьём**, не только по коду. `Subsystem.WaveR8P` с ребёнком `WaveR8C`, переезд `WaveR8P`
под `WaveParent`:

* `WaveR8P.mdo` уехал верно → `src/Subsystems/WaveParent/Subsystems/WaveR8P/WaveR8P.mdo`;
* `WaveR8C.mdo` **остался** в освобождаемом каталоге `src/Subsystems/WaveR8P/Subsystems/WaveR8C/`;
* `WaveR8P.subsystems` выродился в **безымянную заглушку** `[Subsystem]` (до переезда было
  `[Subsystem.WaveR8P.Subsystem.WaveR8C]`);
* `Subsystem.WaveR8C` → `exists:false` в `edt_metadata_details` **и** `METADATA_NOT_FOUND` в
  `update_metadata`, т.е. объект неадресуем и **тулом не лечится** — ровно класс `747d617`.
* Уборка `998ad72` сработала правильно и данные не пострадали: каталог с посторонними записями не снесён,
  удалён только дескриптор (`Removed the storage Subsystem.WaveR8P vacated: …/WaveR8P.mdo`).

### Фикс (`00e8d16`) и его живая проверка на билде `1206`

Каскад: FQN всего поддерева читаются **до** переезда владельца, перерегистрация — после. Порядок и есть
фикс: down-link'и `subsystems` — это БАРЕ-имена, резолвящиеся против FQN владельца, поэтому план,
прочитанный после переезда, пуст и каскад молча не сделает ничего. Содержимое плана решает EMF-свободный
`SubsystemTree.descendantRelocations` (тесты по результату).

Проверено на **свежих** объектах и на ТРИ уровня: `WaveR9P > WaveR9C > WaveR9G`, переезд `WaveR9P` под
`WaveParent` →

* три `.mdo` на вложенных путях: `…/WaveParent/Subsystems/WaveR9P/{WaveR9P.mdo,
  Subsystems/WaveR9C/{WaveR9C.mdo, Subsystems/WaveR9G/WaveR9G.mdo}}`;
* все три адресуемы плоским FQN (`exists:true`);
* down-link'и — полные цепочки: `WaveR9P.subsystems = [Subsystem.WaveParent.Subsystem.WaveR9P.Subsystem.WaveR9C]`,
  `WaveR9C.subsystems = […Subsystem.WaveR9G]`. Заглушек нет.
* старый каталог `src/Subsystems/WaveR9P/` `.mdo`-файлов не содержит.

### ВСКРЫТО этим же прогоном — up-link потомка остаётся прежним (ГЛАВНОЕ ОТКРЫТОЕ)

`parentSubsystem` у каскадных потомков хранит **старый** FQN родителя, и на диске это видно точно:

* `WaveR9C.mdo` → `<parentSubsystem>Subsystem.WaveR9P</parentSubsystem>` (родитель теперь
  `Subsystem.WaveParent.Subsystem.WaveR9P`);
* `WaveR9G.mdo` → `<parentSubsystem>Subsystem.WaveR9P.Subsystem.WaveR9C</parentSubsystem>` (старая цепочка).

EDT рендерит это как безымянную заглушку `parentSubsystem | Subsystem`. Побочно установлено: `parentSubsystem`
сериализуется **storage-FQN родителя**, а не плоским именем — плоским он выглядит только когда родитель
корневой. Контрактный тест `SubsystemNestingSymmetryContractTest` (шапка класса) называет его «FLAT FQN» —
это верно лишь для корневого родителя.

**Тулом не лечится, и это измерено, а не выведено.** Повторный `update_metadata Subsystem.WaveR9G
set.parentSubsystem = Subsystem.WaveR9C` вернул **SUCCESS**, а файл не изменился и заглушка осталась.
Причина в коде: `reparentSubsystem` читает `child.getParentSubsystem()` — висячий прокси — и
`sameSubsystem(oldParent, newParent)` через `SubsystemIdentity.same` отвечает «тот же», поэтому
`setParentSubsystem` пропускается. То есть гард идемпотентности принимает висячий прокси за живого
родителя. Ещё один случай канала «success = вердикт есть», где успех означает «ничего не сделано»
(ср. `yaxunit_qa_channel_contract`).

**Направление фикса (не реализовано):** после перерегистрации потомка переставить его `parentSubsystem` на
живой объект родителя — `Relocation` должен нести узел-родителя (для первого уровня это сама переезжающая
подсистема), тогда `descendant.setParentSubsystem(parentNode)` заменит висячий прокси и сериализатор
напишет новый FQN. Отдельно стоит починить `sameSubsystem`, чтобы висячий прокси НЕ считался равным живому
объекту — иначе ручной ремонт так и останется молчаливым no-op'ом. Порча половинчатая (родительская сторона
верна), объект адресуем, поэтому это НЕ потеря объекта.

### Тестовый мусор этого раунда

* `Subsystem.WaveR8P` / `WaveR8C` — **эталон ПОРЧИ до фикса**: `WaveR8C` неадресуем и не лечится
  (`WaveR8C.mdo` лежит в `src/Subsystems/WaveR8P/Subsystems/WaveR8C/`, `WaveR8P` уехал под `WaveParent`).
  `WaveR8P` дополнительно был успешно обновлён по ПАРНОЙ цепочке — доказательство, что она резолвится.
* `Subsystem.WaveR9P` / `WaveR9C` / `WaveR9G` под `WaveParent` — **эталон работающего каскада** с
  оставшимся дефектом up-link'а. `WaveR9C` не трогать: это чистый слепок «после каскада». На `WaveR9G`
  проверялся молчаливый no-op ремонта.

## Addendum round 8 — остаток round 7 закрыт живьём (билд `0.1.7.20260729-1142`)

Уборка освобождённого каталога сделана (`998ad72`) и **проверена на свежем объекте**: создать
`Subsystem.WaveR7Child` в корне → `set.parentSubsystem = Subsystem.WaveParent` →

* на диске ровно ОДИН `.mdo`: `src/Subsystems/WaveParent/Subsystems/WaveR7Child/WaveR7Child.mdo`;
* `src/Subsystems/WaveR7Child/` **отсутствует** (`Test-Path` = False);
* лог называет удалённое: `Removed the storage Subsystem.WaveR7Child vacated: /TestConfiguration/src/Subsystems/WaveR7Child`.

Канал: освобождённый FQN известен только внутри транзакции, поэтому он едет на том же sink, который цепочка
записи и так несёт (`CoEditedSink`, `instanceof`-проба + WARN, если канала нет). Полный тип-рефакторинг
`Consumer<String>` → именованный sink **отвергнут сознательно**: параметр протянут через 15 методов, а
`SubsystemNestingSymmetryContractTest` пинит восемь точных строк с этими именами — цена переименования
оплачивается правкой контрактных утверждений, то есть ослаблением проверок.

Что решает `VacatedSubsystemStorage` (чистый класс, тесты по результату, 9 кейсов):
нет нового `.mdo` на диске → **не трогать** (старый файл — единственная копия); в старом каталоге есть ещё
что-то → снести только дескриптор (дети подсистемы — ОТДЕЛЬНЫЕ top-объекты, переезд их НЕ перерегистрирует,
их файлы лежат внутри освобождаемого каталога); новый путь внутри старого каталога (переезд под своего же
потомка) → тоже только дескриптор. Уборка **никогда не валит операцию**: переезд состоялся, объект адресуем.

### Вскрыто попутно, НЕ починено (наследник, забери)
* **Каскад по потомкам при переезде.** `updateTopObjectFqn` перерегистрирует только сам объект. Подсистема с
  детьми, уехавшая под нового родителя, оставляет детей зарегистрированными по СТАРОЙ цепочке
  (`Subsystem.B.Subsystem.C` вместо `Subsystem.A.Subsystem.B.Subsystem.C`). Не измерено живьём — вывод из
  кода; проверять пробой «родитель с ребёнком → переезд → где `.mdo` ребёнка и по какому FQN он адресуем».
  Уборка это учитывает (каталог с детьми не сносится), но сам каскад — отдельный дефект.
* **Описание `update_metadata.target_fqn` всё ещё утверждает опровергнутое:** «Subsystem FQNs are FLAT at any
  nesting depth … never pass one» (вложенная форма как раз резолвится алиасом). Пятая копия того же неверного
  правила — четыре javadoc round 7 поправил, эта осталась в схеме тула.

## Addendum round 7 — живая валидация переезда подсистемы на билде `0.1.7.20260729-0912`

Проба на СВЕЖЕЙ подсистеме (`Subsystem.WaveR6Child`: создать → `set.parentSubsystem = Subsystem.WaveParent`):

* ✅ **ложный отказ ушёл** — `update_metadata` вернул успех, никакого `EDT_TRANSACTION_FAILED`;
* ✅ `Configuration.mdo` подсистему не числит, `WaveParent.mdo` числит (`<subsystems>WaveR6Child</subsystems>`);
* ✅ **файл переехал**: `src/Subsystems/WaveParent/Subsystems/WaveR6Child/WaveR6Child.mdo`;
* ✅ **объект адресуем** — `edt_metadata_details` по плоскому `Subsystem.WaveR6Child` даёт `exists:true`,
  `parentSubsystem = Subsystem.WaveParent`. Регрессия неадресуемости закрыта;
* ✅ down-link у родителя резолвится с цепочкой владельцев, **как в AM**:
  `subsystems = [Subsystem, Subsystem, Subsystem.WaveParent.Subsystem.WaveR6Child]` — две безымянные записи
  это ПРЕДСУЩЕСТВУЮЩАЯ порча (висячий `WaveChild2` + осиротевший `WaveChild`), третья — новая и правильная.

### ОСТАЛОСЬ (единственное): старый каталог не убирается после переезда
`src/Subsystems/WaveR6Child/WaveR6Child.mdo` (206 байт) **остался на верхнем уровне** — дубль определения.
Это ровно тот единственный пункт, который автор фикса заранее назвал к доработке «если живая валидация покажет
осиротевший `src/Subsystems/<Child>/`»; теперь это измеренный факт, а не риск. Опасность — воскрешение: при
следующем refresh/re-import EDT увидит второе определение подсистемы на верхнем уровне.

Точка вставки готова: `relocateSubsystemStorage` (`EdtMetadataService.java:10644-10691`) уже знает `currentFqn`
и `targetFqn`, а `MetadataResourcePaths.subsystemDirectory(fqn)` считает оба пути. Убирать нужно **после**
экспорта (когда новый `.mdo` уже на диске) и под гардом «новый файл существует» — иначе уборка снесёт
единственную копию. Готовый образец post-commit уборки по файловой системе — `cleanupRemovedFilesystemArtifacts`
(вызов около `:15098`), путь удаления там уже строится из storage-FQN.

**Предсуществующую порчу тул починить не может и не должен пытаться:** `Subsystem.WaveChild` остаётся
неадресуемым (его storage-FQN так и плоский, в корне его нет, down-link родителя — безымянная заглушка).
Лечится вручную: либо вернуть строку в `Configuration.mdo`, либо перенести файл под
`Subsystems/WaveParent/Subsystems/WaveChild/`. Оставлен как есть — эталон дефекта.

## Addendum round 6 — живая валидация round 5 на билде `0.1.7.20260729-0811`

Установлен через `redeploy-1529.ps1`, оба проекта READY, индекс готов. Итог по четырём фиксам round 5:

| Фикс | Вердикт живьём |
|---|---|
| параметр макета (`82d86b5`) | **ЗАКРЫТ.** `inspect_template` на `Catalog.Catalog.Template.WaveTplR5b` вернул `[Название] \| [Сумма]`; в `Template.mxlx` на диске лежат `<parameter>` + `<format><fillType>Parameter</fillType>`. Это факт о writer'е, не о reader'е. Эталон «как было» — `WaveTplR4`, там по-прежнему пусто |
| DCS (`b2066ab`) | **ЗАКРЫТ, и допущение снято фактом.** См. ниже |
| `Configuration.subsystems` (`ce4bf06`) | **НЕ ЗАКРЫТ — фикс неполон и в текущем виде регрессивен.** См. ниже |
| висячая `<subsystems>` при удалении (`ce4bf06`) | **ПРОВАЛ живьём** — тот же корень |
| `get_diagnostics` origins (`fc9861f`) | **НЕИНФОРМАТИВНО.** `origin=review-annotation` даёт 0, но в песочнице нет поставщика review-маркеров ⇒ «корректно исключено» и «их и не было» неразличимы. Живьём подтверждена только проводка (дефолтный фильтр и явный origin работают, проект наводится верно). Остаётся 15 юнит-тестов `07c1049` |

### DCS: диагностическая сборка отработала ровно по назначению (закрыт, `b2066ab`)
Три вызова на `Report.WaveR5Dcs` дали три разных плана в `[dcs]`-строке:
`plan=CREATE sameNameSchemaBound=false templates=0` (свежий) → `plan=NO_OP sameNameSchemaBound=true
existingSchema=true source=main` (здоровое повторно) → **`plan=REBIND_SAME_NAME sameNameSchemaBound=false
existingSchema=false source=templates templates=1`** на висячем состоянии (файл `Template.dcs` удалён, запись
в `.mdo` осталась) — и вылечил: `schemaCreated=true, templateCreated=false, schemaFilePresent=true`.
**Живьём непроверенное допущение round 5 теперь измеренный факт:** висячий `BasicTemplate.template`
читается как ОТСУТСТВУЮЩИЙ, не как прокси, удовлетворяющий `instanceof` (`sameNameSchemaBound=false` плюс
`getTopObjectByFqn(...) -> <null>`). Риск «план посчитает `NO_OP` и лечение не сработает» не реализовался.
Диагностическое логирование можно снимать.

### ЧЕТВЁРТАЯ сторона вложенности подсистем — корень провала `ce4bf06` (НЕ закрыт)
`update_metadata set.parentSubsystem` на `Subsystem.WaveChild` вернул
`[EDT_TRANSACTION_FAILED] Metadata object not found after commit` — **на мутации, чьё состояние файлов ровно
правильное**. Лог: `Subsystem WaveChild dropped from the configuration root: it is nested now` → далее
`resolveByFqn top-level type=Subsystem name=WaveChild found=false`.

Корень прочитан на эталоне AM, а не выведен:
* `Configuration.mdo` в AM — **34** записи `<subsystems>`; в `src/Subsystems` — **34** каталога верхнего
  уровня. **Корневая регистрация ⟺ каталог верхнего уровня.**
* Из 132 `.mdo` подсистем AM **99 лежат по ВЛОЖЕННЫМ путям** `src/Subsystems/<Parent>/Subsystems/<Child>/<Child>.mdo`.
* Родитель перечисляет детей голыми именами, ребёнок ссылается назад плоским FQN — обе стороны как раньше.
* В AM down-links **резолвятся с именами**: `Subsystem.Accounting` → `subsystems = [Subsystem.Accounting.Subsystem.Settlements, …]`,
  и плоский FQN вложенной `Subsystem.Settlements` даёт `exists:true`. Вся резолюция подсистем
  (`SubsystemTree.flatten`, `TopLevelCollections.forKind`, `findSubsystemAnywhere`) **исправна** — она не баг.
* В песочнице тот же рендер даёт `subsystems | [Subsystem, Subsystem]`, а `EdtMetadataInspectorService:240-250`
  печатает голое `eClass().getName()` **только при пустом `name`** ⇒ это наблюдение, а не догадка о прокси.

Итог: `ce4bf06` снимает корневую регистрацию, **не перемещая `.mdo` ребёнка** под вложенный путь. Файл
остаётся на верхнем уровне, поэтому голая ссылка родителя резолвится в безымянную заглушку, обход её не
матчит, и объект становится **неадресуемым ни одним тулом** (`update_metadata` на него → `METADATA_NOT_FOUND`,
`edt_metadata_details` → `exists:false`). Неадресуемость хуже двойной регистрации, которую фикс убирал.
Тот же корень объясняет провал уборки: `delete_metadata force=true` на `Subsystem.WaveChild2` удалил объект,
но `<subsystems>WaveChild2</subsystems>` в `WaveParent.mdo` осталась.

Отдано в работу: фикс должен НИКОГДА не оставлять объект неадресуемым — либо перемещать `.mdo` (как делает
EDT), либо, если перемещение недостижимо через API, сохранять корневую регистрацию и честно об этом
сообщать.

### НОВЫЙ ДЕФЕКТ: advisory о проглоченном ключе не покрывает `get_diagnostics` (закрыт этим раундом)
`get_diagnostics(project="TestConfiguration", scope=project)` вернул 6402 ошибки проекта
**`/Accounting management`** — принимаемое имя `project_name`, ключ отброшен, `resolveDefaultProjectName()`
выбрал другой проект, и **ни слова** об этом. Все прочие тулы в той же сессии пометку выдавали.
Причина: `GetDiagnosticsTool` и `GetDiagnosticsDetailsTool` — единственные два, что `implements ITool`
напрямую, а advisory жил приватным методом `AbstractTool`. При этом javadoc самого `AbstractTool:196`
называет ровно этот случай (`get_diagnostics … project — both without a word`) как мотивацию гарда
`8ed8c67`. **Фикс, который цитирует случай, его не покрывал.** Закрыто переносом в общий `ToolAdvisory` +
`AdvisoryToolWrapper` на точке регистрации (`ToolRegistry`), UI-бандл не тронут.

### Расхождение в имени команды DCS
Тул принимает `create_schema`; артефакт, CHANGELOG и снапшот говорят `create_main_schema`, и сообщение
валидатора тоже («Операция dcs_create_main_schema валидирована»). Внешнее имя одно, внутренние — другое;
при упоминании в документах использовать `create_schema`.

## Addendum round 5 — 2026-07-29 вечер (сборка зелёная; живая валидация ВЫПОЛНЕНА — см. Addendum round 6 выше)

### Параметр макета: направление установлено пробой БЕЗ сборки, фикс однозначен (закрыт, `82d86b5`)

Round 4 оставил развилку «врёт `render_template` или `inspect_template`». Одно чтение отрисованного
`WaveTplR4/Template.mxlx` её сняло: во второй строке `<c><f>0</f></c>` — **ни `<parameter>`, ни `<tl>`**, то
есть не записано ничего и read-путь невиновен. Корень — в `V8MoxelSerializer.writeCell` (`javap -p -c`,
2025.2.3): ветка выбирается по **формату**, а не по содержимому — `<parameter>` пишется только когда
`formats[cell.getFormatIndex()].getFillType() == FillType.PARAMETER`, иначе пишется текст, которого у
параметрической ячейки нет. Все форматы, что строил `render_template`, были пустыми ⇒ каждая
ячейка-параметр сериализовалась в ничто с отчётом об успехе. Таблица форматов теперь несёт параметрический
вариант каждого стиля. Побочно из того же байткода: `detailParameter`/`pictureParameter`/`value` пишутся
**безусловно** (format-гейт только у `parameter`), а `getFormats().get(idx)` — без проверки границ.

### Третья сторона вложенности подсистем (закрыт, `ce4bf06`)

`Configuration.subsystems` — не «ещё одна коллекция», а **список только корней**. Эталон AM: 34 записи
`<subsystems>Subsystem.X</subsystems>` в `Configuration.mdo` и ни одной вложенной (`AccessManagement`,
`Calendar`, `Bonuses` отсутствуют, их корень `StandardSubsystems` присутствует). Корень использует
**квалифицированную** форму, родитель — **голое имя**. В песочнице `Subsystem.WaveChild` числился и у
`WaveParent`, и в корне: `reparentSubsystem` писал две ссылки на стороне подсистем и корень не трогал.
Починено в обе стороны (детач обязан вернуть в корень, иначе подсистема выпадает из конфигурации).
Попутно: `removeTopLevelObjectLinks` подметал только корень ⇒ удаление вложенной EDT-подсистемы оставляло
висячую `<subsystems>` у родителя — теперь подметаются все родители. Ещё наблюдение: `.mdo` вложенной
подсистемы лежит в `src/Subsystems/<Parent>/Subsystems/<Child>/`, то есть **путь отражает вложенность**,
хотя FQN плоский.

### Кластер E-C (`get_diagnostics` / review-маркеры) был закрыт РАНЬШЕ — таблица статусов врала

Отправив субагента «реализовать C», получил ответ: всё четыре пункта уже реализованы коммитом **`fc9861f`**
(`feat(get_diagnostics): label diagnostic origin, exclude review markers`, 28.07 18:28, **предок HEAD** —
проверено `merge-base --is-ancestor`). NUL-байт в `EdtDiagnosticsCollector` тоже уже убран (байт-скан даёт 0).
Раздел «### C)» выше и таблица статусов остались непроапдейченными с той волны. **Перед тем как заводить работу
по этому артефакту — сверяйся с `git log`, а не с таблицей.** Два осознанных отступления от текста разведки в
`fc9861f`: сделан provenance-ярлык, а НЕ allow-list типов маркеров (allow-list молча терял бы новые типы EDT), и
platform task/bookmark-маркеры исключены из правила «textmarker без severity ⇒ review», чтобы не пропали TODO.

Что действительно не было закрыто — **тестируемость**: фильтр и счётчики жили приватными методами
UI-бандла, куда не достаёт ни один тестовый рантайм (`com.codepilot1c.ui.tests` — pom без исходников, без
MANIFEST и вне модулей `bundles/pom.xml`), поэтому покрывались только грепом по исходнику. Правила вынесены в
чистый `core/diagnostics/DiagnosticOriginSelection` (обе функции), collector делегирует. Остаётся
grep-only одно утверждение — рендер секции review в `formatForLlm`; для него нужен живой UI-тест-бандл
(инфра-работа, отдельная задача).

### Дефект процесса: два source-contract утверждения round 4 были красными и этого никто не увидел

`SubsystemNestingSymmetryContractTest` не попал в тестовый фильтр round 4, поэтому два его утверждения,
отставших от кода (`return false;` в `addSubsystemChild`; сравнение имён в `sameSubsystem`, уехавшее в
`SubsystemIdentity`), остались падающими до этого раунда. Прямое подтверждение
[[source_contract_tests_dont_see_behavior]]. Вывод для следующих раундов: если правишь метод, чьё имя
упоминается в `*ContractTest`, прогоняй эти тесты **в том же фильтре**.

## Addendum round 4 — live 2026-07-29 день, билд `0.1.7.20260729-0702`

Раунд начался с установки диагностического билда round 3 и **закрылся его выхлопом с первого вызова** —
метод `edt_diagnostic_build_round_method` сработал ровно как задуман: диагноз не угадан, а прочитан.

### F1 — корень ПРОЧИТАН, фикс построен на наблюдении (закрыт, `a986c6b`)

Повтор `update_metadata set.parentSubsystem` напечатал по каждой записи `WaveParent.subsystems`:

```
parent=WaveParent child=WaveChild childProxy=false childBmFqn=Subsystem.WaveChild
existing=[class=Subsystem impl=SubsystemImpl proxy=true name=null bmFqn=transient
          uri=bm://TestConfiguration/Subsystem.WaveParent.Subsystem.WaveChild#/ | ×4]
```

Из трёх гипотез идентичности round 3 победила первая: записи — **неразрешённые EMF-прокси**. `name=null`
и `bmFqn=transient` (то есть `IBmObject`-путь бесполезен), осмысленный только `uri`, причём он несёт
идентичность **точечной цепочкой вложенности**, а не плоским FQN. Ребёнок при этом приходит живым
(`childProxy=false`, `childBmFqn=Subsystem.WaveChild`) — отсюда и асимметрия, из-за которой сравнение по
`getName()` не матчило никогда.

Фикс: идентичность падает на URI там, где имя не читается; чтение URI **строгое** — предпоследний элемент
точечной цепочки обязан быть буквально `Subsystem`, иначе идентичности НЕТ, и отсутствующая идентичность не
равна другой отсутствующей. Асимметрия намеренная: промах воспроизводит старый дубль, ложное совпадение
молча выкинуло бы чужого ребёнка. Плюс `addSubsystemChild` теперь вычищает повторы (подсистема, перечисленная
у родителя дважды, невалидна и так), не трогая неопознаваемые записи. Отвергнутая round 3 альтернатива
(перестройка коллекции с детской стороны) осталась отвергнутой по той же причине.

### НОВОЕ: артефакты макетов пишутся и читаются под именем, которого EDT не использует (закрыт, `a986c6b`)

Припаркованный в round 3 пункт («сверять не с чем — в песочнице нет макетов») **распаркован и оказался
крупнее заявки**. Второй проект песочницы — реальная конфигурация AM, и в ней **680** артефактов макетов.

Решающая проба, один вызов: `inspect_template` на РЕАЛЬНОМ макете
`Catalog.BusinessCalendars.Template.PF_MXL_BusinessCalendar` →

```
Файл: src/Catalogs/BusinessCalendars/Templates/PF_MXL_BusinessCalendar/Template.mxl
Содержимое: (файл не найден)
```

То есть сломан не только write-путь, как предполагалось, а прежде всего **read**: инструмент ищет
`Template.mxl`, которого в EDT-формате не существует, и потому не видит **ни одного** макета реального
проекта. Срез по AM: 270 spreadsheet-макетов лежат как `Template.mxlx`, `.mxl` — **ноль**. Формат тоже
другой: реальный файл — XML (`<document xmlns="http://v8.1c.ru/8.2/data/spreadsheet">`, 15909 байт), наш —
бинарный MOXCEL (13 байт).

Таблица соответствий **не выведена из среза** — она прочитана из пула констант
`com._1c.g5.v8.dt.ide.QualifiedNameFilePathConverter` (EDT 2025.2.3) и покрывает все десять констант
`TemplateType`; срез лишь подтверждает её:

| `TemplateType` | ext | | `TemplateType` | ext |
|---|---|---|---|---|
| SPREADSHEET_DOCUMENT | `mxlx` | | GRAPHICAL_SCHEMA (EDT: `GraphicalScheme`) | `scheme` |
| TEXT_DOCUMENT | `txt` | | GEOGRAPHICAL_SCHEMA | `geos` |
| BINARY_DATA | `bin` | | DATA_COMPOSITION_SCHEMA | `dcs` |
| HTML_DOCUMENT | `htmldoc` | | DATA_COMPOSITION_APPEARANCE_TEMPLATE | `dcsat` |
| ACTIVE_DOCUMENT | `axdt` | | ADD_IN | `addin` |

Сериализация переехала на `MoxelResourceMxlx` (`AbstractXmlResource`), чтение пробует правильное имя первым
и legacy `Template.mxl` вторым. Типы, которые сервис сериализовать не умеет, теперь оставляют артефакт
**отсутствующим** с честным логом вместо spreadsheet-блоба под чужим расширением; raw-MOXCEL fallback удалён
по той же причине. `Import-Package` пополнился `com._1c.g5.modeling.xml[.serializer]`.

**Живая валидация write-пути (та же сессия, билд `0702`) и второй, более мелкий корень.** Первый прогон
`add_metadata_child` с `template_type=spreadsheet` дал файл под ПРАВИЛЬНЫМ именем `Template.mxlx`, но **0 байт**,
и честный лог `createEmptySpreadsheetArtifact failed: index=0, size=0`. Проба одним вызовом сузила дефект:
`render_template` на тот же макет записал **валидный XML 1758 байт** с тем же namespace-заголовком, что у
реальных макетов AM, и `inspect_template` прочитал его обратно (2×2, именованная область `Header`). Значит
сериализатор ломается только на ПУСТОМ документе, а причина видна в коде рядом: `renderTemplate` кладёт
`sheet.getFormats().add(defaultFormat)`, а пустой артефакт шёл без формата — сериализатор безусловно индексирует
`formats[0]`. Лечение: один дефолтный `Format` в пустой документ + уборка 0-байтового огрызка, который ресурс
успевает создать до падения. Попутно выяснилось, что на СТАРОМ билде этот путь падал так же
(`MoxelResource approach failed, using raw MOXCEL fallback`) — то есть валидный spreadsheet-артефакт не
создавался никогда, просто молча.

### НОВОЕ, НЕ чинилось (round 5): параметры не выживают round-trip в `render_template`

Побочная находка round-trip-проверки. Отрисовали секцию из двух строк:
`[["Товар","Цена"],["[Название]","[Сумма]"]]`. `inspect_template` вернул первую строку верно
(`Товар | Цена`), а вторую — **двумя пустыми ячейками** (` | `), тогда как на реальном макете AM параметры
читаются штатно (`| Количество дней и часов | [NameOfMonth]`). То есть либо `render_template` пишет
ячейку-параметр не так, как EDT, либо `inspect_template` её не распознаёт. Направление не установлено —
разделить можно дешёвой пробой: отрисовать параметр и сравнить XML ячейки с XML параметрической ячейки
реального макета AM (`PF_MXL_BusinessCalendar`, там есть эталон). Не начато под ротацией.

### Композитные тулы: ключ чужой команды больше не проглатывается (закрыт, `3419973`)

Контракт «ключ → команды» **извлекается из схемы**, а не дублируется рядом: описание каждого свойства уже
открывается владельцем (`(upsert_dataset) Dataset name`). Конвенция проверена ключ-за-ключом против
`doExecute` всех трёх тулов. Нетегированный ключ, прозаический тег (`(mutating commands)`) и тег, не
совпавший ни с одной командой, считаются общими — то есть дрейф тега может дать промах, но не ложный отказ.

### Живая валидация отгруженного в round 3 (билд `0702`, всё зелёное)

| Проверка | Результат |
|---|---|
| L1-advisory на неизвестный параметр | сработал **сам, случайно**, на первом же вызове: `get_workspace_state ignored an unknown parameter: 'include_projects' … Accepted parameters: include_bound_infobases` |
| L2-отказ до токена | `type` верхним параметром → отказ, названы оба лишних ключа, подсказка `properties.type`, токен не выдан |
| inline-квалификатор | `properties:{type:"String(100)"}` → `<length>100</length>` |
| composite + квалификатор | `properties:{type:["String(100)","Boolean"]}` → два `<types>` И `<length>100</length>` |
| `create_form` | успех **без** `FORM_MATERIALIZATION_TIMEOUT`, с внятным `storage=embedded-in-owner-mdo` |
| `template_type` кейс-алиас | `"DCS"` → `<templateType>DataCompositionSchema</templateType>` |
| `template_type` мусор | честный отказ со полным списком девяти значений |

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

## Вопросы владельцу — ОТВЕЧЕНЫ 2026-07-29 (round 10)

Все шесть закрыты владельцем; ответы — исполняемые решения, не рекомендации.

1. **Авто-генерация `queryText`** при `customQuery=true` без явного текста → **генерировать**, как редактор EDT,
   и **всегда возвращать сгенерированный текст в summary**. Никакой невидимой магии.
2. **`mainTable`** → **включить, отдельным коммитом**. Владелец принимает цену (новый `Import-Package` +
   зависимость от derived data) при условии, что правка легко откатывается.
3. **`fields` / `calculatedFields` / `parameters` / `listSettings`** → **за скоупом + ЧЕСТНЫЙ ОТКАЗ**
   (не молчаливый игнор). Обоснование принято: платформа выводит поля из запроса при
   `autoFillAvailableFields=true`.
4. **Новый op `set_attribute_props`** в `mutate_form_model` → **добавить**. Снимает корневую ловушку `set_item`,
   который молча не трогает реквизиты.
5. **`add_button` для объектных/общих команд** → **сейчас честный отказ** («только FormCommand»), реализация —
   отдельной фичей позже.
6. **Переиспользование кред из `infobase_connection`** для inline-probe → **можно, но явно сообщать в summary**
   («взяты креды из infobase_connection»). Невидимой авторизации быть не должно.

## Вопрос владельцу по BF-12843 — ОТВЕЧЕН 2026-07-29 (round 10)

Контекст: шинная нота `codepilot1c-feedback/2026-07-29-update-infobase-timeout-s-accepted-retest-and-dynamic-only-masking.md`
(пинги `e0fc4f21` + `d63d5d9d` от `orch-bf12843`). `update_infobase` вернул `updated:true` ВМЕСТЕ с
`dynamic_only:true` — верхнеуровневый признак успеха говорит «готово», а реструктуризация физически не
применена. Агент, гейтящийся на документированном happy-path `updated:true`, примет НЕ schema-ready ИБ за
готовую.

**Решение владельца:** поле `schema_applied` добавляется всегда, И **`dynamic_only:true` ⇒ `updated:false`**.
Верхнеуровневый признак успеха не должен говорить «готово», когда запрошенный полный апдейт применился лишь
частично. Это осознанная **смена контракта** — ломает вызывающих, которых dynamic-only устраивал, поэтому
требует анонса на шину (правило: смена канала анонсируется).
