package com.codepilot1c.core.edt.ast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.codepilot1c.core.edt.BmObjectHelper;
import com.codepilot1c.core.edt.metadata.MetadataKind;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.TopLevelCollections;

/**
 * Metadata inspection service using EDT configuration model and EMF reflection.
 */
public class EdtMetadataInspectorService {

    private final EdtServiceGateway gateway;
    private final ProjectReadinessChecker readinessChecker;

    public EdtMetadataInspectorService(EdtServiceGateway gateway, ProjectReadinessChecker readinessChecker) {
        this.gateway = gateway;
        this.readinessChecker = readinessChecker;
    }

    public MetadataDetailsResult getMetadataDetails(MetadataDetailsRequest req) {
        req.validate();
        return executeRead(req.getProjectName(), () -> doGetMetadataDetails(req));
    }

    MetadataDetailsResult doGetMetadataDetails(MetadataDetailsRequest req) {
        IProject project = gateway.resolveProject(req.getProjectName());
        readinessChecker.ensureReady(project);

        IConfigurationProvider provider = gateway.getConfigurationProvider();
        Configuration config = provider.getConfiguration(project);
        if (config == null) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Configuration is unavailable for project", false); //$NON-NLS-1$
        }

        List<MetadataNode> nodes = new ArrayList<>();
        for (String fqn : req.getObjectFqns()) {
            MdObject obj = findMdObjectByFqn(config, fqn);
            if (obj == null) {
                MetadataNode missing = new MetadataNode()
                        .setType("MdObject") //$NON-NLS-1$
                        .setName(fqn)
                        .setPath(fqn)
                        .setFormatStyle(MetadataNode.FormatStyle.SIMPLE_VALUE)
                        .putProperty("exists", Boolean.FALSE) //$NON-NLS-1$
                        .putProperty("message", notFoundMessage(fqn)); //$NON-NLS-1$
                nodes.add(missing);
                continue;
            }
            nodes.add(inspectEObject(obj, fqn, req.isFull(), 0));
        }

        return new MetadataDetailsResult(req.getProjectName(), "edt_emf", nodes); //$NON-NLS-1$
    }

    private <T> T executeRead(String projectName, ReadOnlyTask<T> task) {
        IProject project = gateway.resolveProject(projectName);
        if (project == null) {
            return task.execute();
        }
        try {
            return gateway.getBmModelManager().executeReadOnlyTask(project, tx -> task.execute());
        } catch (EdtAstException e) {
            if (e.getCode() == EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE) {
                return task.execute();
            }
            throw e;
        } catch (RuntimeException e) {
            throw new EdtAstException(
                    EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to execute metadata inspection read transaction: " + e.getMessage(), //$NON-NLS-1$
                    true,
                    e);
        }
    }

    private MetadataNode inspectEObject(EObject object, String path, boolean full, int depth) {
        MetadataNode node = new MetadataNode()
                .setType(object.eClass().getName())
                .setName(getObjectName(object))
                .setPath(path);

        for (EStructuralFeature feature : object.eClass().getEAllStructuralFeatures()) {
            if (feature.isDerived() || feature.isTransient() || feature.isVolatile()) {
                continue;
            }
            Object value = object.eGet(feature);
            if (value == null) {
                continue;
            }

            if (feature instanceof EReference ref && ref.isContainment()) {
                if (isStringMapContainment(ref) && value instanceof Collection<?> collection) {
                    Map<String, String> localized = extractStringMapEntries(collection);
                    if (!localized.isEmpty()) {
                        node.putProperty(feature.getName(), localized);
                    }
                    continue;
                }
                if (!full || depth >= 2) {
                    continue;
                }
                if (ref.isMany() && value instanceof Collection<?> collection) {
                    for (Object item : collection) {
                        if (item instanceof EObject child) {
                            node.addChild(inspectEObject(child, path + "." + feature.getName(), full, depth + 1)); //$NON-NLS-1$
                        }
                    }
                } else if (value instanceof EObject child) {
                    node.addChild(inspectEObject(child, path + "." + feature.getName(), full, depth + 1)); //$NON-NLS-1$
                }
                continue;
            }

            if (value instanceof Collection<?> collection) {
                node.putProperty(feature.getName(), formatCollectionValue(collection));
            } else {
                node.putProperty(feature.getName(), formatScalarValue(value));
            }
        }

        node.setFormatStyle(EObjectInspector.chooseFormatStyle(node));
        return node;
    }

    private String getObjectName(EObject object) {
        try {
            EStructuralFeature nameFeature = object.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
            if (nameFeature != null) {
                Object value = object.eGet(nameFeature);
                if (value != null && !String.valueOf(value).isBlank()) {
                    return String.valueOf(value);
                }
            }
        } catch (Exception e) {
            // Ignore and fallback to class name.
        }
        return object.eClass().getName();
    }

    /**
     * Resolves the top-level object addressed by {@code <Type>.<Name>}.
     *
     * <p>Used to carry a hardcoded nine-kind switch with {@code default -> List.of()}, so
     * {@code edt_metadata_details} answered {@code exists:false} for Subsystem, Role,
     * ExchangePlan, DefinedType and every register beyond information/accumulation — objects
     * that plainly existed. Both the type-token aliases (plural, Russian) and the
     * kind→collection mapping are now the shared ones, which also makes nested subsystems
     * resolvable by the flat alias this tool accepts.</p>
     */
    private MdObject findMdObjectByFqn(Configuration config, String fqn) {
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length != 2) {
            // Only the leading <Type>.<Name> pair is resolved here. Reading it out of a longer FQN and
            // returning that top object was WORSE than a false negative: asking for
            // Subsystem.<Parent>.<Child> answered with the PARENT's properties under the requested path,
            // so the caller believed it had the child (live 2026-07-28). Refuse and say why instead.
            return null;
        }
        String name = parts[1];

        MetadataKind kind;
        try {
            kind = MetadataKind.fromString(parts[0]);
        } catch (MetadataOperationException e) {
            // Not a top-level kind token — report as not found rather than failing the request.
            return null;
        }

        for (MdObject obj : TopLevelCollections.forKind(config, kind)) {
            if (obj != null && name.equalsIgnoreCase(obj.getName())) {
                return obj;
            }
        }
        return null;
    }

    /**
     * Explains a miss instead of flatly denying it. A dotted FQN longer than {@code <Type>.<Name>} is the
     * common caller mistake — most of all for subsystems, where the flat {@code Subsystem.<Name>} is the
     * only form THIS tool resolves (the paired chain {@code Subsystem.<Parent>.Subsystem.<Name>} is the
     * storage FQN and the mutating tools accept it, but nothing walks it here) — so name the supported form
     * rather than leaving "Object not found" to be read as "does not exist".
     */
    static String notFoundMessage(String fqn) {
        if (fqn != null && fqn.split("\\.").length > 2) { //$NON-NLS-1$
            return "Object not found: only a top-level <Type>.<Name> FQN is inspected here, and this FQN " //$NON-NLS-1$
                    + "carries extra segments. Pass a nested subsystem as the flat Subsystem.<Name> — the " //$NON-NLS-1$
                    + "only form this tool resolves, since each subsystem is its own top object (the paired " //$NON-NLS-1$
                    + "chain Subsystem.<Parent>.Subsystem.<Name> stays valid for the mutating tools). Child " //$NON-NLS-1$
                    + "objects (attributes, forms, templates) are not addressable through this tool."; //$NON-NLS-1$
        }
        return "Object not found"; //$NON-NLS-1$
    }

    private Object formatCollectionValue(Collection<?> collection) {
        if (collection == null || collection.isEmpty()) {
            return List.of();
        }
        List<Object> formatted = new ArrayList<>();
        for (Object entry : collection) {
            formatted.add(formatScalarValue(entry));
        }
        return formatted;
    }

    private Object formatScalarValue(Object value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        if (value instanceof EObject eObject) {
            return formatEObjectReference(eObject);
        }
        return String.valueOf(value);
    }

    private String formatEObjectReference(EObject object) {
        if (object == null) {
            return ""; //$NON-NLS-1$
        }
        String fqn = resolveFqn(object);
        if (fqn != null && !fqn.isBlank()) {
            return fqn;
        }
        EStructuralFeature nameFeature = object.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
        if (nameFeature != null) {
            Object rawName = object.eGet(nameFeature);
            if (rawName != null) {
                String name = String.valueOf(rawName).trim();
                if (!name.isBlank()) {
                    return object.eClass().getName() + "." + name; //$NON-NLS-1$
                }
            }
        }
        return object.eClass().getName();
    }

    private String resolveFqn(EObject object) {
        if (!(object instanceof IBmObject bmObject)) {
            return null;
        }
        String fqn = BmObjectHelper.safeTopFqn(bmObject);
        return fqn.isBlank() ? null : fqn;
    }

    private boolean isStringMapContainment(EReference reference) {
        if (reference == null || !reference.isContainment() || !reference.isMany()) {
            return false;
        }
        var entryType = reference.getEReferenceType();
        if (entryType == null) {
            return false;
        }
        EStructuralFeature keyFeature = entryType.getEStructuralFeature("key"); //$NON-NLS-1$
        EStructuralFeature valueFeature = entryType.getEStructuralFeature("value"); //$NON-NLS-1$
        if (!(keyFeature instanceof EAttribute keyAttr) || !(valueFeature instanceof EAttribute valueAttr)) {
            return false;
        }
        return isStringDataType(keyAttr.getEAttributeType()) && isStringDataType(valueAttr.getEAttributeType());
    }

    private boolean isStringDataType(EDataType dataType) {
        if (dataType == null) {
            return false;
        }
        Class<?> instanceClass = dataType.getInstanceClass();
        if (instanceClass == String.class) {
            return true;
        }
        String className = dataType.getInstanceClassName();
        return "java.lang.String".equals(className); //$NON-NLS-1$
    }

    private Map<String, String> extractStringMapEntries(Collection<?> collection) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Object item : collection) {
            if (!(item instanceof EObject entry)) {
                continue;
            }
            EStructuralFeature keyFeature = entry.eClass().getEStructuralFeature("key"); //$NON-NLS-1$
            EStructuralFeature valueFeature = entry.eClass().getEStructuralFeature("value"); //$NON-NLS-1$
            if (keyFeature == null || valueFeature == null) {
                continue;
            }
            Object rawKey = entry.eGet(keyFeature);
            Object rawValue = entry.eGet(valueFeature);
            if (rawKey == null || rawValue == null) {
                continue;
            }
            String key = String.valueOf(rawKey).trim();
            String value = String.valueOf(rawValue);
            if (!key.isBlank() && !value.isBlank()) {
                map.put(key, value);
            }
        }
        return map;
    }

    @FunctionalInterface
    private interface ReadOnlyTask<T> {
        T execute();
    }
}
