package com.codepilot1c.core.edt.metadata;

import java.util.List;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;

/**
 * Single source of the {@code MetadataKind} → configuration-collection mapping.
 *
 * <p>The mapping used to exist in four divergent copies: the typed switch behind
 * {@code update_metadata} FQN resolution, the {@code existsTopLevel} pre-check, the
 * {@code Configuration.mdo} tag names used by post-create verification, and — the worst
 * offender — a nine-kind switch in {@code EdtMetadataInspectorService} whose
 * {@code default -> List.of()} made {@code edt_metadata_details} answer {@code exists:false}
 * for Subsystem, Role, ExchangePlan, DefinedType and every register beyond the first two.
 * Anything absent from a copy silently became "does not exist"; folding them together makes
 * a missing kind a compile error instead.</p>
 *
 * <p>{@link #forKind} is a <strong>read-only</strong> accessor. Mutation must keep going
 * through {@code addTopLevelObject}/{@code removeTopLevelObjectLinks}: those need the live
 * typed {@code EList} and the element-type cast, and — see below — the list returned for
 * {@link MetadataKind#SUBSYSTEM} is a computed snapshot, not the configuration's own list.</p>
 *
 * <p><strong>Subsystems are flattened.</strong> {@code Configuration.subsystems} holds only
 * the first level, yet a nested subsystem is its own top object — registered under the chain
 * {@code Subsystem.<Parent>.Subsystem.<Name>}, see {@link SubsystemTree}. Returning the whole
 * forest is what makes the flat two-segment alias ({@code Subsystem.PaymentCalendar}) resolve at
 * any depth.</p>
 */
public final class TopLevelCollections {

    private TopLevelCollections() {
    }

    /**
     * Returns the configuration objects of {@code kind}, for reading only.
     * The list for {@link MetadataKind#SUBSYSTEM} is a depth-first snapshot of the whole
     * subsystem forest; every other kind yields the configuration's own typed collection.
     */
    public static List<? extends MdObject> forKind(Configuration configuration, MetadataKind kind) {
        if (configuration == null || kind == null) {
            return List.of();
        }
        return switch (kind) {
            case CATALOG -> configuration.getCatalogs();
            case DOCUMENT -> configuration.getDocuments();
            case INFORMATION_REGISTER -> configuration.getInformationRegisters();
            case ACCUMULATION_REGISTER -> configuration.getAccumulationRegisters();
            case ACCOUNTING_REGISTER -> configuration.getAccountingRegisters();
            case CALCULATION_REGISTER -> configuration.getCalculationRegisters();
            case COMMON_MODULE -> configuration.getCommonModules();
            case COMMON_ATTRIBUTE -> configuration.getCommonAttributes();
            case ENUM -> configuration.getEnums();
            case REPORT -> configuration.getReports();
            case DATA_PROCESSOR -> configuration.getDataProcessors();
            case CONSTANT -> configuration.getConstants();
            case COMMAND_GROUP -> configuration.getCommandGroups();
            case INTERFACE -> configuration.getInterfaces();
            case LANGUAGE -> configuration.getLanguages();
            case STYLE -> configuration.getStyles();
            case STYLE_ITEM -> configuration.getStyleItems();
            case SESSION_PARAMETER -> configuration.getSessionParameters();
            case SETTINGS_STORAGE -> configuration.getSettingsStorages();
            case XDTO_PACKAGE -> configuration.getXDTOPackages();
            case WS_REFERENCE -> configuration.getWsReferences();
            case ROLE -> configuration.getRoles();
            // Flattened on purpose: nested subsystems are top objects with flat FQNs.
            case SUBSYSTEM -> SubsystemTree.<Subsystem>flatten(
                    configuration.getSubsystems(), Subsystem::getSubsystems);
            case EXCHANGE_PLAN -> configuration.getExchangePlans();
            case CHART_OF_ACCOUNTS -> configuration.getChartsOfAccounts();
            case CHART_OF_CHARACTERISTIC_TYPES -> configuration.getChartsOfCharacteristicTypes();
            case CHART_OF_CALCULATION_TYPES -> configuration.getChartsOfCalculationTypes();
            case BUSINESS_PROCESS -> configuration.getBusinessProcesses();
            case TASK -> configuration.getTasks();
            case COMMON_FORM -> configuration.getCommonForms();
            case COMMON_COMMAND -> configuration.getCommonCommands();
            case COMMON_TEMPLATE -> configuration.getCommonTemplates();
            case COMMON_PICTURE -> configuration.getCommonPictures();
            case SCHEDULED_JOB -> configuration.getScheduledJobs();
            case FILTER_CRITERION -> configuration.getFilterCriteria();
            case DEFINED_TYPE -> configuration.getDefinedTypes();
            case SEQUENCE -> configuration.getSequences();
            case DOCUMENT_JOURNAL -> configuration.getDocumentJournals();
            case DOCUMENT_NUMERATOR -> configuration.getDocumentNumerators();
            case EVENT_SUBSCRIPTION -> configuration.getEventSubscriptions();
            case FUNCTIONAL_OPTION -> configuration.getFunctionalOptions();
            case FUNCTIONAL_OPTIONS_PARAMETER -> configuration.getFunctionalOptionsParameters();
            case WEB_SERVICE -> configuration.getWebServices();
            case HTTP_SERVICE -> configuration.getHttpServices();
            case EXTERNAL_DATA_SOURCE -> configuration.getExternalDataSources();
            case INTEGRATION_SERVICE -> configuration.getIntegrationServices();
            case BOT -> configuration.getBots();
            case WEB_SOCKET_CLIENT -> configuration.getWebSocketClients();
        };
    }

    /**
     * The {@code Configuration.mdo} element name (also the EMF feature name) that lists
     * objects of {@code kind}, e.g. {@code <commonModules>CommonModule.X</commonModules>}.
     */
    public static String configurationTag(MetadataKind kind) {
        return switch (kind) {
            case CATALOG -> "catalogs"; //$NON-NLS-1$
            case DOCUMENT -> "documents"; //$NON-NLS-1$
            case INFORMATION_REGISTER -> "informationRegisters"; //$NON-NLS-1$
            case ACCUMULATION_REGISTER -> "accumulationRegisters"; //$NON-NLS-1$
            case ACCOUNTING_REGISTER -> "accountingRegisters"; //$NON-NLS-1$
            case CALCULATION_REGISTER -> "calculationRegisters"; //$NON-NLS-1$
            case COMMON_MODULE -> "commonModules"; //$NON-NLS-1$
            case COMMON_ATTRIBUTE -> "commonAttributes"; //$NON-NLS-1$
            case ENUM -> "enums"; //$NON-NLS-1$
            case REPORT -> "reports"; //$NON-NLS-1$
            case DATA_PROCESSOR -> "dataProcessors"; //$NON-NLS-1$
            case CONSTANT -> "constants"; //$NON-NLS-1$
            case COMMAND_GROUP -> "commandGroups"; //$NON-NLS-1$
            case INTERFACE -> "interfaces"; //$NON-NLS-1$
            case LANGUAGE -> "languages"; //$NON-NLS-1$
            case STYLE -> "styles"; //$NON-NLS-1$
            case STYLE_ITEM -> "styleItems"; //$NON-NLS-1$
            case SESSION_PARAMETER -> "sessionParameters"; //$NON-NLS-1$
            case SETTINGS_STORAGE -> "settingsStorages"; //$NON-NLS-1$
            case XDTO_PACKAGE -> "xdtoPackages"; //$NON-NLS-1$
            case WS_REFERENCE -> "wsReferences"; //$NON-NLS-1$
            case ROLE -> "roles"; //$NON-NLS-1$
            case SUBSYSTEM -> "subsystems"; //$NON-NLS-1$
            case EXCHANGE_PLAN -> "exchangePlans"; //$NON-NLS-1$
            case CHART_OF_ACCOUNTS -> "chartsOfAccounts"; //$NON-NLS-1$
            case CHART_OF_CHARACTERISTIC_TYPES -> "chartsOfCharacteristicTypes"; //$NON-NLS-1$
            case CHART_OF_CALCULATION_TYPES -> "chartsOfCalculationTypes"; //$NON-NLS-1$
            case BUSINESS_PROCESS -> "businessProcesses"; //$NON-NLS-1$
            case TASK -> "tasks"; //$NON-NLS-1$
            case COMMON_FORM -> "commonForms"; //$NON-NLS-1$
            case COMMON_COMMAND -> "commonCommands"; //$NON-NLS-1$
            case COMMON_TEMPLATE -> "commonTemplates"; //$NON-NLS-1$
            case COMMON_PICTURE -> "commonPictures"; //$NON-NLS-1$
            case SCHEDULED_JOB -> "scheduledJobs"; //$NON-NLS-1$
            case FILTER_CRITERION -> "filterCriteria"; //$NON-NLS-1$
            case DEFINED_TYPE -> "definedTypes"; //$NON-NLS-1$
            case SEQUENCE -> "sequences"; //$NON-NLS-1$
            case DOCUMENT_JOURNAL -> "documentJournals"; //$NON-NLS-1$
            case DOCUMENT_NUMERATOR -> "documentNumerators"; //$NON-NLS-1$
            case EVENT_SUBSCRIPTION -> "eventSubscriptions"; //$NON-NLS-1$
            case FUNCTIONAL_OPTION -> "functionalOptions"; //$NON-NLS-1$
            case FUNCTIONAL_OPTIONS_PARAMETER -> "functionalOptionsParameters"; //$NON-NLS-1$
            case WEB_SERVICE -> "webServices"; //$NON-NLS-1$
            case HTTP_SERVICE -> "httpServices"; //$NON-NLS-1$
            case EXTERNAL_DATA_SOURCE -> "externalDataSources"; //$NON-NLS-1$
            case INTEGRATION_SERVICE -> "integrationServices"; //$NON-NLS-1$
            case BOT -> "bots"; //$NON-NLS-1$
            case WEB_SOCKET_CLIENT -> "webSocketClients"; //$NON-NLS-1$
        };
    }

    /**
     * The canonical lowercase collection token {@code scan_metadata_index} reports and
     * filters by. Deliberately NOT derived from {@link #configurationTag}: the index has
     * historically used {@code chartofaccounts} (singular "chart"), and its scope-alias
     * table keys off exactly these spellings.
     */
    public static String indexScopeToken(MetadataKind kind) {
        return switch (kind) {
            case CATALOG -> "catalogs"; //$NON-NLS-1$
            case DOCUMENT -> "documents"; //$NON-NLS-1$
            case INFORMATION_REGISTER -> "informationregisters"; //$NON-NLS-1$
            case ACCUMULATION_REGISTER -> "accumulationregisters"; //$NON-NLS-1$
            case ACCOUNTING_REGISTER -> "accountingregisters"; //$NON-NLS-1$
            case CALCULATION_REGISTER -> "calculationregisters"; //$NON-NLS-1$
            case COMMON_MODULE -> "commonmodules"; //$NON-NLS-1$
            case COMMON_ATTRIBUTE -> "commonattributes"; //$NON-NLS-1$
            case ENUM -> "enums"; //$NON-NLS-1$
            case REPORT -> "reports"; //$NON-NLS-1$
            case DATA_PROCESSOR -> "dataprocessors"; //$NON-NLS-1$
            case CONSTANT -> "constants"; //$NON-NLS-1$
            case COMMAND_GROUP -> "commandgroups"; //$NON-NLS-1$
            case INTERFACE -> "interfaces"; //$NON-NLS-1$
            case LANGUAGE -> "languages"; //$NON-NLS-1$
            case STYLE -> "styles"; //$NON-NLS-1$
            case STYLE_ITEM -> "styleitems"; //$NON-NLS-1$
            case SESSION_PARAMETER -> "sessionparameters"; //$NON-NLS-1$
            case SETTINGS_STORAGE -> "settingsstorages"; //$NON-NLS-1$
            case XDTO_PACKAGE -> "xdtopackages"; //$NON-NLS-1$
            case WS_REFERENCE -> "wsreferences"; //$NON-NLS-1$
            case ROLE -> "roles"; //$NON-NLS-1$
            case SUBSYSTEM -> "subsystems"; //$NON-NLS-1$
            case EXCHANGE_PLAN -> "exchangeplans"; //$NON-NLS-1$
            case CHART_OF_ACCOUNTS -> "chartofaccounts"; //$NON-NLS-1$
            case CHART_OF_CHARACTERISTIC_TYPES -> "chartofcharacteristictypes"; //$NON-NLS-1$
            case CHART_OF_CALCULATION_TYPES -> "chartofcalculationtypes"; //$NON-NLS-1$
            case BUSINESS_PROCESS -> "businessprocesses"; //$NON-NLS-1$
            case TASK -> "tasks"; //$NON-NLS-1$
            case COMMON_FORM -> "commonforms"; //$NON-NLS-1$
            case COMMON_COMMAND -> "commoncommands"; //$NON-NLS-1$
            case COMMON_TEMPLATE -> "commontemplates"; //$NON-NLS-1$
            case COMMON_PICTURE -> "commonpictures"; //$NON-NLS-1$
            case SCHEDULED_JOB -> "scheduledjobs"; //$NON-NLS-1$
            case FILTER_CRITERION -> "filtercriteria"; //$NON-NLS-1$
            case DEFINED_TYPE -> "definedtypes"; //$NON-NLS-1$
            case SEQUENCE -> "sequences"; //$NON-NLS-1$
            case DOCUMENT_JOURNAL -> "documentjournals"; //$NON-NLS-1$
            case DOCUMENT_NUMERATOR -> "documentnumerators"; //$NON-NLS-1$
            case EVENT_SUBSCRIPTION -> "eventsubscriptions"; //$NON-NLS-1$
            case FUNCTIONAL_OPTION -> "functionaloptions"; //$NON-NLS-1$
            case FUNCTIONAL_OPTIONS_PARAMETER -> "functionaloptionsparameters"; //$NON-NLS-1$
            case WEB_SERVICE -> "webservices"; //$NON-NLS-1$
            case HTTP_SERVICE -> "httpservices"; //$NON-NLS-1$
            case EXTERNAL_DATA_SOURCE -> "externaldatasources"; //$NON-NLS-1$
            case INTEGRATION_SERVICE -> "integrationservices"; //$NON-NLS-1$
            case BOT -> "bots"; //$NON-NLS-1$
            case WEB_SOCKET_CLIENT -> "websocketclients"; //$NON-NLS-1$
        };
    }
}
