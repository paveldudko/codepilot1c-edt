package com.codepilot1c.core.edt.dcs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSource;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsVariant;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.Template;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * DCS projections and mutations over EDT metadata model.
 */
public class EdtDcsService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtDcsService.class);

    /** Name of the single data source every real {@code .dcs} on disk carries. */
    private static final String DEFAULT_DATA_SOURCE_NAME = "DataSource1"; //$NON-NLS-1$

    /**
     * {@code dataSourceType} of the default data source. It is a plain string in the DCS model
     * (verified against {@code DataCompositionSchemaDataSource}), NOT an enum.
     */
    private static final String DEFAULT_DATA_SOURCE_TYPE = "Local"; //$NON-NLS-1$

    private final EdtMetadataGateway gateway;
    private final DcsExportSupport exportSupport;

    public EdtDcsService() {
        this(new EdtMetadataGateway());
    }

    EdtDcsService(EdtMetadataGateway gateway) {
        this.gateway = gateway;
        this.exportSupport = new DcsExportSupport(gateway);
    }

    public DcsSummaryResult getSummary(DcsGetSummaryRequest request) {
        request.validate();
        gateway.ensureValidationRuntimeAvailable();

        MdObject owner = resolveOwner(request.normalizedProjectName(), request.normalizedOwnerFqn());
        SchemaResolution schemaResolution = resolveSchema(owner);
        DataCompositionSchema schema = schemaResolution.schema();
        int templateCount = countDcsTemplates(owner);

        return new DcsSummaryResult(
                request.normalizedProjectName(),
                request.normalizedOwnerFqn(),
                owner.eClass().getName(),
                schema != null,
                schemaResolution.source(),
                schema != null ? schema.getDataSets().size() : 0,
                schema != null ? schema.getParameters().size() : 0,
                schema != null ? schema.getCalculatedFields().size() : 0,
                schema != null ? schema.getSettingsVariants().size() : 0,
                templateCount);
    }

    public DcsListNodesResult listNodes(DcsListNodesRequest request) {
        request.validate();
        gateway.ensureValidationRuntimeAvailable();

        MdObject owner = resolveOwner(request.normalizedProjectName(), request.normalizedOwnerFqn());
        SchemaResolution schemaResolution = resolveSchema(owner);
        DataCompositionSchema schema = schemaResolution.schema();
        if (schema == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.DCS_SCHEMA_NOT_FOUND,
                    "DCS schema is not configured for owner: " + request.normalizedOwnerFqn(),
                    false); //$NON-NLS-1$
        }

        String nodeKind = request.normalizedNodeKind();
        String nameFilter = request.normalizedNameContains();
        List<DcsNodeItem> all = new ArrayList<>();
        if ("all".equals(nodeKind) || "dataset".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
            for (DataSet dataSet : schema.getDataSets()) {
                if (dataSet == null) {
                    continue;
                }
                String name = safe(dataSet.getName());
                String details = dataSet.eClass().getName();
                if (dataSet instanceof DataCompositionSchemaDataSetQuery queryDataSet) {
                    details = details + " query=" + compact(queryDataSet.getQuery(), 120); //$NON-NLS-1$
                }
                all.add(new DcsNodeItem("dataset", name, details)); //$NON-NLS-1$
            }
        }
        if ("all".equals(nodeKind) || "parameter".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
            for (DataCompositionSchemaParameter parameter : schema.getParameters()) {
                if (parameter == null) {
                    continue;
                }
                String name = safe(parameter.getName());
                String details = "expression=" + compact(parameter.getExpression(), 100); //$NON-NLS-1$
                all.add(new DcsNodeItem("parameter", name, details)); //$NON-NLS-1$
            }
        }
        if ("all".equals(nodeKind) || "calculated".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
            for (DataCompositionSchemaCalculatedField field : schema.getCalculatedFields()) {
                if (field == null) {
                    continue;
                }
                String name = safe(field.getDataPath());
                String details = "expression=" + compact(field.getExpression(), 100); //$NON-NLS-1$
                all.add(new DcsNodeItem("calculated", name, details)); //$NON-NLS-1$
            }
        }
        if ("all".equals(nodeKind) || "variant".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
            for (SettingsVariant variant : schema.getSettingsVariants()) {
                if (variant == null) {
                    continue;
                }
                String name = safe(variant.getName());
                String details = variant.getSettings() != null ? "has_settings=true" : "has_settings=false"; //$NON-NLS-1$ //$NON-NLS-2$
                all.add(new DcsNodeItem("variant", name, details)); //$NON-NLS-1$
            }
        }

        if (nameFilter != null) {
            all = all.stream()
                    .filter(item -> normalize(item.name()).contains(nameFilter))
                    .toList();
        }
        all.sort(Comparator
                .comparing(DcsNodeItem::nodeKind, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(DcsNodeItem::name, String.CASE_INSENSITIVE_ORDER));

        int total = all.size();
        int offset = request.effectiveOffset();
        int limit = request.effectiveLimit();
        int start = Math.min(offset, total);
        int end = Math.min(start + limit, total);
        List<DcsNodeItem> page = start >= end ? List.of() : new ArrayList<>(all.subList(start, end));

        return new DcsListNodesResult(
                request.normalizedProjectName(),
                request.normalizedOwnerFqn(),
                nodeKind,
                total,
                page.size(),
                start,
                limit,
                end < total,
                page);
    }

    /**
     * Creates (or repairs) the owner's main data composition schema.
     *
     * <p>The schema is NOT a contained child of its {@code Template}: {@code BasicTemplate.template}
     * is a <b>transient, non-containment</b> reference (same flags as {@code Role.rights} and
     * {@code BasicForm.form}), and the schema itself is a separate top-object serialized into
     * {@code Templates/&lt;name&gt;/Template.dcs}. A bare {@code template.setTemplate(factory.create())}
     * therefore points at an orphan that no exporter can find — the model accepted the write and
     * nothing ever reached disk.</p>
     *
     * <p><b>Operation order is load-bearing.</b> The external FQN is derived from the container
     * chain, so the {@code Template} must carry its name/type and be inside the owner's
     * {@code templates} list BEFORE the FQN is generated — otherwise
     * {@code generateExternalPropertyFqn} raises a raw {@code AssertionFailedException}. Only then:
     * namespace → defensive {@code getTopObjectByFqn} reuse → {@code attachTopObject} → re-read the
     * attached object from the transaction → write THAT into the reference → seed the default
     * {@code dataSource}.</p>
     */
    public DcsCreateMainSchemaResult createMainSchema(DcsCreateMainSchemaRequest request) {
        request.validate();
        gateway.ensureMutationRuntimeAvailable();

        String opId = LogSanitizer.newId("dcs-schema"); //$NON-NLS-1$
        IProject project = resolveProject(request.normalizedProjectName());
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        String ownerFqn = request.normalizedOwnerFqn();
        String requestedName = request.effectiveTemplateName();
        String ownerTopLevelFqn = ownerTopLevelFqn(ownerFqn);
        // templateSource is the honest half of template=: the default name and a caller who typed
        // exactly that name produce the same value, and only the source tells them apart afterwards.
        LOG.info("[dcs][%s] createMainSchema START project=%s owner=%s template=%s templateSource=%s force=%s", //$NON-NLS-1$
                opId, request.normalizedProjectName(), ownerFqn, requestedName,
                request.hasExplicitTemplateName() ? "caller" : "default", //$NON-NLS-1$ //$NON-NLS-2$
                Boolean.valueOf(request.shouldForceReplace()));

        // Snapshot EOL BEFORE the mutation: the BM serializer rewrites .mdo/.dcs as CRLF regardless
        // of the file's existing convention, turning a 1-line change into a whole-file diff on LF repos.
        DcsExportSupport.EolGuard eolGuard = exportSupport.beginEolGuard(project, ownerTopLevelFqn, opId);

        SchemaMutation state = new SchemaMutation();
        executeWrite(project, transaction -> {
            applySchemaMutation(transaction, project, configuration, request, opId, state);
            return null;
        });
        if (state.templateName == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to create DCS schema",
                    false); //$NON-NLS-1$
        }

        String relativePath = DcsSchemaSupport.schemaFileRelativePath(ownerFqn, state.templateName);
        boolean present = probeSchemaFile(project, relativePath);
        // Export when we changed something, and ALSO when the schema exists in the model but its
        // file is missing on disk — that is exactly the reported broken state, and re-exporting it
        // is the repair rather than another "success" without an artifact.
        if (state.mutated() || (relativePath != null && !present)) {
            exportSupport.forceExport(project, ownerTopLevelFqn, state.externalFqn, opId);
            eolGuard.restore();
            exportSupport.refreshProjectSafely(project);
            present = probeSchemaFile(project, relativePath);
        }
        String stateMessage = describeSchemaFileState(relativePath, present, opId);
        LOG.info("[dcs][%s] createMainSchema DONE template=%s created=%s file=%s present=%s", //$NON-NLS-1$
                opId, state.templateName, Boolean.valueOf(state.schemaCreated),
                relativePath == null ? "<n/a>" : relativePath, Boolean.valueOf(present)); //$NON-NLS-1$

        return new DcsCreateMainSchemaResult(
                request.normalizedProjectName(),
                ownerFqn,
                safe(state.ownerKind),
                state.templateName,
                state.schemaCreated,
                state.templateCreated,
                state.mainBindingUpdated,
                safe(state.schemaSource),
                safe(DcsSchemaSupport.templateFqn(ownerFqn, state.templateName)),
                safe(state.externalFqn),
                relativePath == null ? "" : relativePath, //$NON-NLS-1$
                present,
                stateMessage);
    }

    private void applySchemaMutation(
            IBmPlatformTransaction transaction,
            IProject project,
            Configuration configuration,
            DcsCreateMainSchemaRequest request,
            String opId,
            SchemaMutation state
    ) {
        String ownerFqn = request.normalizedOwnerFqn();
        String requestedName = request.effectiveTemplateName();
        MdObject owner = resolveOwnerInTransaction(transaction, configuration, ownerFqn);
        OwnerTemplates templates = resolveOwnerTemplates(owner);
        if (templates == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.DCS_OWNER_KIND_UNSUPPORTED,
                    "Owner does not support DCS templates: " + owner.eClass().getName(),
                    false); //$NON-NLS-1$
        }
        state.ownerKind = owner.eClass().getName();

        Template sameName = findTemplateByName(templates.templates(), requestedName);
        DcsSchemaSupport.NameSlotState slot = DcsSchemaSupport.classifyNameSlot(
                sameName == null ? null : sameName.getName(),
                sameName != null && sameName.getTemplateType() == TemplateType.DATA_COMPOSITION_SCHEMA,
                requestedName);
        SchemaResolution existing = resolveSchema(owner);
        LOG.info("[dcs][%s] name-slot=%s existingSchema=%s source=%s templates=%d", //$NON-NLS-1$
                opId, slot, Boolean.valueOf(existing.schema() != null), existing.source(),
                Integer.valueOf(templates.templates().size()));

        if (!request.shouldForceReplace()) {
            if (slot == DcsSchemaSupport.NameSlotState.OCCUPIED_OTHER_TYPE) {
                throw new MetadataOperationException(
                        MetadataOperationCode.METADATA_ALREADY_EXISTS,
                        "Template " + safe(sameName.getName()) + " already exists on " + ownerFqn //$NON-NLS-1$
                                + " with templateType=" + templateTypeName(sameName) //$NON-NLS-1$
                                + ". Pass force_replace=true to convert it into a data composition " //$NON-NLS-1$
                                + "schema, or choose another template_name.", //$NON-NLS-1$
                        false);
            }
            if (slot == DcsSchemaSupport.NameSlotState.REUSABLE_DCS || existing.schema() != null) {
                // Idempotent no-op: report the template that already carries the schema.
                Template bound = slot == DcsSchemaSupport.NameSlotState.REUSABLE_DCS
                        ? sameName
                        : findDcsTemplate(templates.templates(), existing.schema());
                state.templateName = bound != null ? safe(bound.getName())
                        : findTemplateName(existing.schema(), templates.templates());
                state.schemaSource = existing.schema() != null ? existing.source() : "templates"; //$NON-NLS-1$
                // Best-effort FQN so the disk-state repair below can target the schema fragment.
                state.externalFqn = externalSchemaFqnQuietly(bound, opId);
                return;
            }
        }

        // ----- mutating path -----
        Template target = sameName;
        if (target == null && request.shouldForceReplace()) {
            // force_replace means REPLACE: rebind the DCS template the owner already has instead of
            // appending a second one. The name that lands is then the EXISTING one, not the requested
            // one — dropping a name the caller typed is worth a warning, dropping the default nobody
            // asked for is routine. The request keeps that difference (the validated payload carries
            // template_name only when it was explicit), so the log can be honest about which it was.
            target = findDcsTemplate(templates.templates(), existing.schema());
            if (target != null) {
                if (request.hasExplicitTemplateName()) {
                    LOG.warn("[dcs][%s] force_replace reuses existing DCS template '%s' and IGNORES the explicitly requested '%s'", //$NON-NLS-1$
                            opId, safe(target.getName()), requestedName);
                } else {
                    LOG.info("[dcs][%s] force_replace reuses existing DCS template '%s' instead of creating the default '%s'", //$NON-NLS-1$
                            opId, safe(target.getName()), requestedName);
                }
            }
        }
        if (target == null) {
            target = MdClassFactory.eINSTANCE.createTemplate();
            target.setName(requestedName);
            target.setTemplateType(TemplateType.DATA_COMPOSITION_SCHEMA);
            // Containment FIRST — the external FQN below is computed from the container chain, so a
            // Template outside the owner's list has no computable FQN (AssertionFailedException).
            templates.templates().add(target);
            state.templateCreated = true;
        } else {
            target.setTemplateType(TemplateType.DATA_COMPOSITION_SCHEMA);
        }
        state.templateName = safe(target.getName());

        String externalFqn = generateSchemaExternalFqn(target, ownerFqn, state.templateName);
        state.externalFqn = externalFqn;
        String expectedFqn = DcsSchemaSupport.expectedExternalSchemaFqn(ownerFqn, state.templateName);
        LOG.info("[dcs][%s] schema externalFqn=%s expected=%s match=%s", //$NON-NLS-1$
                opId, externalFqn, expectedFqn, Boolean.valueOf(externalFqn.equals(expectedFqn)));

        IBmNamespace namespace = requireNamespace(project);
        Object preexisting = transaction.getTopObjectByFqn(namespace, externalFqn);
        LOG.info("[dcs][%s] getTopObjectByFqn(%s) -> %s", opId, externalFqn, //$NON-NLS-1$
                preexisting == null ? "<null>" : preexisting.getClass().getName()); //$NON-NLS-1$

        DataCompositionSchema schema;
        if (preexisting instanceof DataCompositionSchema existingSchema) {
            // Defensive reuse: attaching twice under the same FQN raises BmNameAlreadyInUse.
            // force_replace resets the content so the result is a schema, not a merge.
            schema = existingSchema;
            resetSchemaContent(schema);
        } else {
            if (preexisting instanceof IBmObject stale) {
                // The name slot is held by a top-object of another type (e.g. the spreadsheet
                // document of a template that was created with the wrong templateType). Converting
                // is what force_replace asks for, so drop the stale fragment first.
                LOG.warn("[dcs][%s] detaching stale top-object of type %s at %s before attaching the DCS schema", //$NON-NLS-1$
                        opId, preexisting.getClass().getName(), externalFqn);
                transaction.detachTopObject(stale);
            }
            DataCompositionSchema created = DcsFactory.eINSTANCE.createDataCompositionSchema();
            if (!(created instanceof IBmObject createdBm)) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Created DataCompositionSchema is not a BM object: " //$NON-NLS-1$
                                + created.getClass().getName(), false);
            }
            transaction.attachTopObject(namespace, createdBm, externalFqn);
            Object attached = transaction.getTopObjectByFqn(namespace, externalFqn);
            if (!(attached instanceof DataCompositionSchema txSchema)) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Cannot resolve the attached DCS schema in the transaction by FQN: " + externalFqn, //$NON-NLS-1$
                        false);
            }
            schema = txSchema;
            state.schemaCreated = true;
        }
        // Write ONLY the object re-read from the transaction into the reference — assigning the
        // pre-attach instance commits to "Failed to persist reference value".
        target.setTemplate(schema);
        boolean dataSourceAdded = ensureDefaultDataSource(schema);
        LOG.info("[dcs][%s] schema attached=%s dataSourceSeeded=%s dataSources=%d", //$NON-NLS-1$
                opId, Boolean.valueOf(state.schemaCreated), Boolean.valueOf(dataSourceAdded),
                Integer.valueOf(schema.getDataSources().size()));

        if (owner instanceof Report report) {
            report.setMainDataCompositionSchema(target);
            state.mainBindingUpdated = true;
        } else if (owner instanceof ExternalReport report) {
            report.setMainDataCompositionSchema(target);
            state.mainBindingUpdated = true;
        }
        state.schemaSource = state.mainBindingUpdated ? "main" : "templates"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    public DcsUpsertQueryDatasetResult upsertQueryDataset(DcsUpsertQueryDatasetRequest request) {
        request.validate();
        gateway.ensureMutationRuntimeAvailable();

        String opId = LogSanitizer.newId("dcs-dataset"); //$NON-NLS-1$
        IProject project = resolveProject(request.normalizedProjectName());
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        String ownerTopLevelFqn = ownerTopLevelFqn(request.normalizedOwnerFqn());
        DcsExportSupport.EolGuard eolGuard = exportSupport.beginEolGuard(project, ownerTopLevelFqn, opId);
        Holder<DcsUpsertQueryDatasetResult> holder = new Holder<>();
        Holder<String> schemaFqn = new Holder<>();
        executeWrite(project, transaction -> {
            MdObject owner = resolveOwnerInTransaction(
                    transaction,
                    configuration,
                    request.normalizedOwnerFqn());
            DataCompositionSchema schema = requireSchema(owner, request.normalizedOwnerFqn());
            schemaFqn.value = bmFqnOf(schema);

            DataCompositionSchemaDataSetQuery dataset = findQueryDataset(schema, request.normalizedDatasetName());
            boolean created = false;
            if (dataset == null) {
                dataset = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetQuery();
                dataset.setName(request.normalizedDatasetName());
                schema.getDataSets().add(dataset);
                created = true;
            }
            if (request.normalizedQuery() != null) {
                dataset.setQuery(request.normalizedQuery());
            }
            if (request.normalizedDataSource() != null) {
                dataset.setDataSource(request.normalizedDataSource());
            }
            if (request.autoFillAvailableFields() != null) {
                dataset.setAutoFillAvailableFields(request.autoFillAvailableFields().booleanValue());
            }
            if (request.useQueryGroupIfPossible() != null) {
                dataset.setUseQueryGroupIfPossible(request.useQueryGroupIfPossible().booleanValue());
            }

            holder.value = new DcsUpsertQueryDatasetResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    safe(dataset.getName()),
                    created,
                    safe(dataset.getQuery()),
                    safe(dataset.getDataSource()),
                    dataset.isAutoFillAvailableFields(),
                    dataset.isUseQueryGroupIfPossible());
            return null;
        });

        if (holder.value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to upsert DCS query dataset",
                    false); //$NON-NLS-1$
        }
        // The schema lives in its own top-object file, so exporting only the owner writes the .mdo
        // and leaves the dataset unserialized.
        exportSupport.forceExport(project, ownerTopLevelFqn, schemaFqn.value, opId);
        eolGuard.restore();
        exportSupport.refreshProjectSafely(project);
        return holder.value;
    }

    public DcsUpsertParameterResult upsertParameter(DcsUpsertParameterRequest request) {
        request.validate();
        gateway.ensureMutationRuntimeAvailable();

        String opId = LogSanitizer.newId("dcs-param"); //$NON-NLS-1$
        IProject project = resolveProject(request.normalizedProjectName());
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        String ownerTopLevelFqn = ownerTopLevelFqn(request.normalizedOwnerFqn());
        DcsExportSupport.EolGuard eolGuard = exportSupport.beginEolGuard(project, ownerTopLevelFqn, opId);
        Holder<DcsUpsertParameterResult> holder = new Holder<>();
        Holder<String> schemaFqn = new Holder<>();
        executeWrite(project, transaction -> {
            MdObject owner = resolveOwnerInTransaction(
                    transaction,
                    configuration,
                    request.normalizedOwnerFqn());
            DataCompositionSchema schema = requireSchema(owner, request.normalizedOwnerFqn());
            schemaFqn.value = bmFqnOf(schema);

            DataCompositionSchemaParameter parameter = findParameter(schema, request.normalizedParameterName());
            boolean created = false;
            if (parameter == null) {
                parameter = DcsFactory.eINSTANCE.createDataCompositionSchemaParameter();
                parameter.setName(request.normalizedParameterName());
                schema.getParameters().add(parameter);
                created = true;
            }
            if (request.normalizedExpression() != null) {
                parameter.setExpression(request.normalizedExpression());
            }
            if (request.availableAsField() != null) {
                parameter.setAvailableAsField(request.availableAsField().booleanValue());
            }
            if (request.valueListAllowed() != null) {
                parameter.setValueListAllowed(request.valueListAllowed().booleanValue());
            }
            if (request.denyIncompleteValues() != null) {
                parameter.setDenyIncompleteValues(request.denyIncompleteValues().booleanValue());
            }
            if (request.useRestriction() != null) {
                parameter.setUseRestriction(request.useRestriction().booleanValue());
            }

            holder.value = new DcsUpsertParameterResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    safe(parameter.getName()),
                    created,
                    safe(parameter.getExpression()),
                    parameter.isAvailableAsField(),
                    parameter.isValueListAllowed(),
                    parameter.isDenyIncompleteValues(),
                    parameter.isUseRestriction());
            return null;
        });

        if (holder.value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to upsert DCS parameter",
                    false); //$NON-NLS-1$
        }
        exportSupport.forceExport(project, ownerTopLevelFqn, schemaFqn.value, opId);
        eolGuard.restore();
        exportSupport.refreshProjectSafely(project);
        return holder.value;
    }

    public DcsUpsertCalculatedFieldResult upsertCalculatedField(DcsUpsertCalculatedFieldRequest request) {
        request.validate();
        gateway.ensureMutationRuntimeAvailable();

        String opId = LogSanitizer.newId("dcs-field"); //$NON-NLS-1$
        IProject project = resolveProject(request.normalizedProjectName());
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        String ownerTopLevelFqn = ownerTopLevelFqn(request.normalizedOwnerFqn());
        DcsExportSupport.EolGuard eolGuard = exportSupport.beginEolGuard(project, ownerTopLevelFqn, opId);
        Holder<DcsUpsertCalculatedFieldResult> holder = new Holder<>();
        Holder<String> schemaFqn = new Holder<>();
        executeWrite(project, transaction -> {
            MdObject owner = resolveOwnerInTransaction(
                    transaction,
                    configuration,
                    request.normalizedOwnerFqn());
            DataCompositionSchema schema = requireSchema(owner, request.normalizedOwnerFqn());
            schemaFqn.value = bmFqnOf(schema);

            DataCompositionSchemaCalculatedField field = findCalculatedField(schema, request.normalizedDataPath());
            boolean created = false;
            if (field == null) {
                field = DcsFactory.eINSTANCE.createDataCompositionSchemaCalculatedField();
                field.setDataPath(request.normalizedDataPath());
                schema.getCalculatedFields().add(field);
                created = true;
            }
            if (request.normalizedExpression() != null) {
                field.setExpression(request.normalizedExpression());
            }
            if (request.normalizedPresentationExpression() != null) {
                field.setPresentationExpression(request.normalizedPresentationExpression());
            }

            holder.value = new DcsUpsertCalculatedFieldResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    safe(field.getDataPath()),
                    created,
                    safe(field.getExpression()),
                    safe(field.getPresentationExpression()));
            return null;
        });

        if (holder.value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to upsert DCS calculated field",
                    false); //$NON-NLS-1$
        }
        exportSupport.forceExport(project, ownerTopLevelFqn, schemaFqn.value, opId);
        eolGuard.restore();
        exportSupport.refreshProjectSafely(project);
        return holder.value;
    }

    private IProject resolveProject(String projectName) {
        IProject project = gateway.resolveProject(projectName);
        if (project == null || !project.exists()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "Project not found: " + projectName,
                    false); //$NON-NLS-1$
        }
        return project;
    }

    private MdObject resolveOwnerInTransaction(
            IBmPlatformTransaction transaction,
            Configuration configuration,
            String ownerFqn
    ) {
        // First try to resolve within the transaction context (reliable for new objects)
        if (configuration != null) {
            try {
                Configuration txConfiguration = transaction.toTransactionObject(configuration);
                if (txConfiguration != null) {
                    MdObject txOwner = findInConfiguration(txConfiguration, ownerFqn);
                    if (txOwner != null) {
                        return txOwner;
                    }
                }
            } catch (RuntimeException e) {
                // Fall through to non-transaction resolution
            }
        }
        // Fallback: resolve outside transaction and map
        MdObject owner = resolveOwner(configuration, ownerFqn);
        MdObject txOwner = castMdObject(transaction.toTransactionObject(owner));
        if (txOwner == null) {
            txOwner = resolveOwnerByUri(transaction, owner);
        }
        if (txOwner == null && isExternalOwner(owner)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.DCS_OWNER_KIND_UNSUPPORTED,
                    "External owner is not attached to BM transaction context: "
                            + owner.eClass().getName()
                            + "." + safe(owner.getName())
                            + " bmObject=" + (owner instanceof IBmObject), //$NON-NLS-1$
                    false);
        }
        return txOwner != null ? txOwner : owner;
    }

    private MdObject resolveOwnerByUri(IBmPlatformTransaction transaction, MdObject owner) {
        if (transaction == null || owner == null) {
            return null;
        }
        try {
            EObject byUri = transaction.getObjectByUri(EcoreUtil.getURI(owner));
            return castMdObject(byUri);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private MdObject castMdObject(EObject object) {
        return object instanceof MdObject mdObject ? mdObject : null;
    }

    private MdObject resolveOwner(String projectName, String ownerFqn) {
        IProject project = resolveProject(projectName);
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        return resolveOwner(project, configuration, ownerFqn);
    }

    private MdObject resolveOwner(Configuration configuration, String ownerFqn) {
        if (configuration == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Configuration is unavailable",
                    false); //$NON-NLS-1$
        }
        MdObject object = findInConfiguration(configuration, ownerFqn);
        if (object == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Owner object not found: " + ownerFqn,
                    false); //$NON-NLS-1$
        }
        return object;
    }

    private MdObject resolveOwner(IProject project, Configuration configuration, String ownerFqn) {
        IExternalObjectProject externalProject = asExternalProject(project);
        if (externalProject != null) {
            MdObject external = findInExternalProject(externalProject, ownerFqn);
            if (external != null) {
                return external;
            }
        }

        if (configuration == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Configuration is unavailable",
                    false); //$NON-NLS-1$
        }
        MdObject object = findInConfiguration(configuration, ownerFqn);
        if (object == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Owner object not found: " + ownerFqn,
                    false); //$NON-NLS-1$
        }
        return object;
    }

    private IExternalObjectProject asExternalProject(IProject project) {
        try {
            var v8Project = gateway.getV8ProjectManager().getProject(project);
            if (v8Project instanceof IExternalObjectProject externalProject) {
                return externalProject;
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private MdObject findInExternalProject(IExternalObjectProject project, String ownerFqn) {
        String normalizedRef = normalize(ownerFqn);
        for (MdObject object : project.getExternalObjects(MdObject.class)) {
            if (object == null) {
                continue;
            }
            String shortRef = object.eClass().getName() + "." + safe(object.getName()); //$NON-NLS-1$
            if (normalize(shortRef).equals(normalizedRef) || normalize(object.getName()).equals(normalizedRef)) {
                return object;
            }
        }
        return null;
    }

    private MdObject findInConfiguration(Configuration configuration, String ownerFqn) {
        String[] parts = ownerFqn != null ? ownerFqn.split("\\.") : new String[0]; //$NON-NLS-1$
        if (parts.length < 2) {
            return null;
        }
        String type = parts[0].trim().toLowerCase(Locale.ROOT);
        String name = parts[1].trim();
        List<? extends MdObject> topLevel = switch (type) {
            case "report", "отчет", "отчёт" -> configuration.getReports(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "dataprocessor", "обработка" -> configuration.getDataProcessors(); //$NON-NLS-1$ //$NON-NLS-2$
            default -> Collections.emptyList();
        };
        for (MdObject object : topLevel) {
            if (name.equalsIgnoreCase(object.getName())) {
                return object;
            }
        }
        return null;
    }

    private SchemaResolution resolveSchema(MdObject owner) {
        if (owner instanceof Report report) {
            DataCompositionSchema schema = extractSchema(report.getMainDataCompositionSchema());
            if (schema != null) {
                return new SchemaResolution(schema, "main"); //$NON-NLS-1$
            }
            return new SchemaResolution(findInTemplates(report.getTemplates()), "templates"); //$NON-NLS-1$
        }
        if (owner instanceof ExternalReport report) {
            DataCompositionSchema schema = extractSchema(report.getMainDataCompositionSchema());
            if (schema != null) {
                return new SchemaResolution(schema, "main"); //$NON-NLS-1$
            }
            return new SchemaResolution(findInTemplates(report.getTemplates()), "templates"); //$NON-NLS-1$
        }
        if (owner instanceof DataProcessor dataProcessor) {
            return new SchemaResolution(findInTemplates(dataProcessor.getTemplates()), "templates"); //$NON-NLS-1$
        }
        if (owner instanceof ExternalDataProcessor dataProcessor) {
            return new SchemaResolution(findInTemplates(dataProcessor.getTemplates()), "templates"); //$NON-NLS-1$
        }
        return new SchemaResolution(null, "none"); //$NON-NLS-1$
    }

    private OwnerTemplates resolveOwnerTemplates(MdObject owner) {
        if (owner instanceof Report report) {
            return new OwnerTemplates(report.getTemplates());
        }
        if (owner instanceof ExternalReport report) {
            return new OwnerTemplates(report.getTemplates());
        }
        if (owner instanceof DataProcessor dataProcessor) {
            return new OwnerTemplates(dataProcessor.getTemplates());
        }
        if (owner instanceof ExternalDataProcessor dataProcessor) {
            return new OwnerTemplates(dataProcessor.getTemplates());
        }
        return null;
    }

    private int countDcsTemplates(MdObject owner) {
        OwnerTemplates templates = resolveOwnerTemplates(owner);
        return templates == null ? 0 : countInTemplates(templates.templates());
    }

    private int countInTemplates(List<? extends Template> templates) {
        int count = 0;
        for (Template template : templates) {
            if (extractSchema(template) != null) {
                count++;
            }
        }
        return count;
    }

    private DataCompositionSchema requireSchema(MdObject owner, String ownerFqn) {
        SchemaResolution resolution = resolveSchema(owner);
        if (resolution.schema() == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.DCS_SCHEMA_NOT_FOUND,
                    "DCS schema is not configured for owner: " + ownerFqn,
                    false); //$NON-NLS-1$
        }
        return resolution.schema();
    }

    private String findTemplateName(DataCompositionSchema schema, List<Template> templates) {
        for (Template template : templates) {
            if (template != null && template.getTemplate() == schema) {
                return safe(template.getName());
            }
        }
        return ""; //$NON-NLS-1$
    }

    // ===== main-schema mutation helpers =====

    /** Template whose name matches {@code wanted} case-insensitively, regardless of its type. */
    private Template findTemplateByName(List<Template> templates, String wanted) {
        for (Template template : templates) {
            if (template != null && DcsSchemaSupport.nameMatches(template.getName(), wanted)) {
                return template;
            }
        }
        return null;
    }

    /**
     * The owner's DCS template: the one carrying {@code boundSchema} when known, else the first
     * template typed as a data composition schema.
     */
    private Template findDcsTemplate(List<Template> templates, DataCompositionSchema boundSchema) {
        if (boundSchema != null) {
            for (Template template : templates) {
                if (template != null && template.getTemplate() == boundSchema) {
                    return template;
                }
            }
        }
        for (Template template : templates) {
            if (template != null && template.getTemplateType() == TemplateType.DATA_COMPOSITION_SCHEMA) {
                return template;
            }
        }
        return null;
    }

    private String templateTypeName(Template template) {
        TemplateType type = template == null ? null : template.getTemplateType();
        return type == null ? "<null>" : type.getName(); //$NON-NLS-1$
    }

    /**
     * External-property FQN of the schema top-object. MUST be called only after {@code template} is
     * inside the owner's {@code templates} list — the FQN is derived from the container chain and
     * the generator raises a raw {@code AssertionFailedException} otherwise, which would surface as
     * an unreadable multi-line dump instead of an actionable error.
     */
    private String generateSchemaExternalFqn(Template template, String ownerFqn, String templateName) {
        try {
            String fqn = gateway.getTopObjectFqnGenerator()
                    .generateExternalPropertyFqn(template, MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE);
            if (fqn == null || fqn.isBlank()) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Cannot generate the external FQN of the DCS schema for template '" //$NON-NLS-1$
                                + templateName + "' on " + ownerFqn, false); //$NON-NLS-1$
            }
            return fqn;
        } catch (MetadataOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Cannot generate the external FQN of the DCS schema for template '" + templateName //$NON-NLS-1$
                            + "' on " + ownerFqn + " — the template must already be linked into the " //$NON-NLS-1$ //$NON-NLS-2$
                            + "owner's templates list before its FQN can be computed: " + e.getMessage(), //$NON-NLS-1$
                    false, e);
        }
    }

    /** Best-effort external FQN for the read-only/no-op path; never throws. */
    private String externalSchemaFqnQuietly(Template template, String opId) {
        if (template == null) {
            return null;
        }
        EObject schema = template.getTemplate();
        String bmFqn = bmFqnOf(schema);
        if (bmFqn != null) {
            return bmFqn;
        }
        try {
            return gateway.getTopObjectFqnGenerator()
                    .generateExternalPropertyFqn(template, MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE);
        } catch (RuntimeException e) {
            LOG.warn("[dcs][%s] could not compute the schema external FQN: %s", opId, e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /** FQN under which {@code object} is registered in the BM, or {@code null} when unattached. */
    private String bmFqnOf(EObject object) {
        if (!(object instanceof IBmObject bmObject)) {
            return null;
        }
        try {
            String fqn = bmObject.bmGetFqn();
            return fqn == null || fqn.isBlank() ? null : fqn;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private IBmNamespace requireNamespace(IProject project) {
        IBmNamespace namespace = gateway.getBmModelManager().getBmNamespace(project);
        if (namespace == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve BM namespace for project: " + project.getName(), false); //$NON-NLS-1$
        }
        return namespace;
    }

    /**
     * Resets a reused schema to a bare state. {@code force_replace} means REPLACE the schema
     * content — the previous behaviour appended a second {@code <templates>} entry instead, leaving
     * two templates with one name.
     */
    private void resetSchemaContent(DataCompositionSchema schema) {
        schema.getDataSources().clear();
        schema.getDataSets().clear();
        schema.getDataSetLinks().clear();
        schema.getCalculatedFields().clear();
        schema.getTotalFields().clear();
        schema.getParameters().clear();
        schema.getNestedSchemas().clear();
        schema.getTemplates().clear();
        schema.getFieldTemplates().clear();
        schema.getGroupTemplates().clear();
        schema.getGroupHeaderTemplates().clear();
        schema.getTotalFieldsTemplates().clear();
        schema.getSettingsVariants().clear();
        schema.setDefaultSettings(null);
    }

    /**
     * Seeds the default data source. An empty schema is not viable: every real {@code .dcs} carries
     * {@code <dataSource><name>DataSource1</name><dataSourceType>Local</dataSourceType></dataSource>},
     * and {@code createDataCompositionSchema()} produces none.
     */
    private boolean ensureDefaultDataSource(DataCompositionSchema schema) {
        if (!schema.getDataSources().isEmpty()) {
            return false;
        }
        DataCompositionSchemaDataSource dataSource = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSource();
        dataSource.setName(DEFAULT_DATA_SOURCE_NAME);
        dataSource.setDataSourceType(DEFAULT_DATA_SOURCE_TYPE);
        schema.getDataSources().add(dataSource);
        return true;
    }

    private boolean probeSchemaFile(IProject project, String relativePath) {
        if (project == null || !project.exists() || relativePath == null
                || exportSupport.isExternalProject(project)) {
            return false;
        }
        try {
            return project.getFile(relativePath).exists();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Honest post-write state: whether the schema's own file is really on disk. A DCS schema is a
     * separate top-object, so "the transaction committed" is not evidence — the tool used to report
     * success with no artifact at all.
     */
    private String describeSchemaFileState(String relativePath, boolean present, String opId) {
        if (relativePath == null) {
            return ""; //$NON-NLS-1$
        }
        LOG.info("[dcs][%s] schema-file probe %s present=%s", opId, relativePath, Boolean.valueOf(present)); //$NON-NLS-1$
        if (present) {
            return "DCS schema present on disk: " + relativePath + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "⚠️ WARNING: expected DCS schema file " + relativePath //$NON-NLS-1$
                + " was NOT found on disk after export — the schema may exist in the in-memory model " //$NON-NLS-1$
                + "only. A data composition schema is a separate top-object with its own file; " //$NON-NLS-1$
                + "reload the project and retry, then confirm the file exists."; //$NON-NLS-1$
    }

    /**
     * Top-level FQN of the owner in its BM (English) form. Falls back to the caller's first two
     * segments for owner kinds outside the canonical map.
     */
    private String ownerTopLevelFqn(String ownerFqn) {
        String canonical = DcsSchemaSupport.topLevelFqn(ownerFqn);
        if (canonical != null) {
            return canonical;
        }
        String[] parts = ownerFqn == null ? new String[0] : ownerFqn.trim().split("\\."); //$NON-NLS-1$
        return parts.length >= 2 ? parts[0] + "." + parts[1] : ownerFqn; //$NON-NLS-1$
    }

    private DataCompositionSchemaDataSetQuery findQueryDataset(DataCompositionSchema schema, String name) {
        String token = normalize(name);
        for (DataSet dataSet : schema.getDataSets()) {
            if (dataSet instanceof DataCompositionSchemaDataSetQuery query
                    && normalize(query.getName()).equals(token)) {
                return query;
            }
        }
        return null;
    }

    private DataCompositionSchemaParameter findParameter(DataCompositionSchema schema, String name) {
        String token = normalize(name);
        for (DataCompositionSchemaParameter parameter : schema.getParameters()) {
            if (parameter != null && normalize(parameter.getName()).equals(token)) {
                return parameter;
            }
        }
        return null;
    }

    private DataCompositionSchemaCalculatedField findCalculatedField(DataCompositionSchema schema, String dataPath) {
        String token = normalize(dataPath);
        for (DataCompositionSchemaCalculatedField field : schema.getCalculatedFields()) {
            if (field != null && normalize(field.getDataPath()).equals(token)) {
                return field;
            }
        }
        return null;
    }

    private DataCompositionSchema findInTemplates(List<? extends Template> templates) {
        for (Template template : templates) {
            DataCompositionSchema schema = extractSchema(template);
            if (schema != null) {
                return schema;
            }
        }
        return null;
    }

    private DataCompositionSchema extractSchema(BasicTemplate template) {
        if (template == null) {
            return null;
        }
        TemplateType templateType = template.getTemplateType();
        if (templateType != TemplateType.DATA_COMPOSITION_SCHEMA) {
            return null;
        }
        EObject templateObject = template.getTemplate();
        if (templateObject instanceof DataCompositionSchema schema) {
            return schema;
        }
        return null;
    }

    private <T> T executeWrite(IProject project, PlatformTransactionTask<T> task) {
        try {
            return gateway.getGlobalEditingContext().execute(
                    "CodePilot1C.DcsWrite", //$NON-NLS-1$
                    project,
                    this,
                    task::execute);
        } catch (MetadataOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "DCS transaction failed: " + e.getMessage(),
                    false,
                    e); //$NON-NLS-1$
        }
    }

    private String compact(String value, int max) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max) + "..."; //$NON-NLS-1$
    }

    private String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }

    private String normalize(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isExternalOwner(MdObject owner) {
        return owner instanceof ExternalReport || owner instanceof ExternalDataProcessor;
    }

    private record SchemaResolution(DataCompositionSchema schema, String source) {
    }

    /**
     * What the main-schema transaction did. Mutable because the result record is only assembled
     * AFTER the export + on-disk probe, which cannot run inside the BM transaction.
     */
    private static final class SchemaMutation {
        private String ownerKind;
        private String templateName;
        private String schemaSource;
        private String externalFqn;
        private boolean schemaCreated;
        private boolean templateCreated;
        private boolean mainBindingUpdated;

        boolean mutated() {
            return schemaCreated || templateCreated || mainBindingUpdated;
        }
    }

    private record OwnerTemplates(List<Template> templates) {
    }

    @FunctionalInterface
    private interface PlatformTransactionTask<T> {
        T execute(IBmPlatformTransaction transaction);
    }

    private static final class Holder<T> {
        private T value;
    }
}
