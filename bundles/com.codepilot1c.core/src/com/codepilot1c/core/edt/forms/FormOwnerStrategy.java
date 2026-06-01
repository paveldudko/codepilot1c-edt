package com.codepilot1c.core.edt.forms;

import java.util.LinkedHashMap;
import java.util.Map;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Owner strategy matrix for form creation and default form binding.
 */
public final class FormOwnerStrategy {

    private final Map<String, String> ownerToFactoryMethod;

    private FormOwnerStrategy(Map<String, String> ownerToFactoryMethod) {
        this.ownerToFactoryMethod = ownerToFactoryMethod;
    }

    public static FormOwnerStrategy defaultStrategy() {
        Map<String, String> matrix = new LinkedHashMap<>();
        matrix.put("Catalog", "createCatalogForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("Document", "createDocumentForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("InformationRegister", "createInformationRegisterForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("AccumulationRegister", "createAccumulationRegisterForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("Report", "createReportForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("DataProcessor", "createDataProcessorForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("Enum", "createEnumForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("Task", "createTaskForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("BusinessProcess", "createBusinessProcessForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("ChartOfAccounts", "createChartOfAccountsForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("ChartOfCalculationTypes", "createChartOfCalculationTypesForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("ChartOfCharacteristicTypes", "createChartOfCharacteristicTypesForm"); //$NON-NLS-1$ //$NON-NLS-2$
        return new FormOwnerStrategy(matrix);
    }

    public String resolveFactoryMethod(String ownerClass) {
        String method = ownerToFactoryMethod.get(ownerClass);
        if (method == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Unsupported parent for Form: " + ownerClass, false); //$NON-NLS-1$
        }
        return method;
    }

    /**
     * Resolves the owner-level default-form setter for the given usage and owner kind.
     *
     * <p>The notion of an "object form" is owner-specific: catalogs/documents expose
     * {@code setDefaultObjectForm}, but registers do not. An InformationRegister's object form is its
     * <em>record</em> form ({@code setDefaultRecordForm}); record-set registers
     * (Accumulation/Accounting/Calculation) have no default object-form slot at all and return {@code null}
     * so the caller skips the binding gracefully.
     *
     * @param ownerClass the owner {@code eClass} name (e.g. {@code "InformationRegister"}); may be {@code null}
     * @return the setter method name, or {@code null} when the owner/usage combination has no default slot
     */
    public String resolveDefaultSetter(FormUsage usage, String ownerClass) {
        if (usage == null || usage == FormUsage.AUXILIARY) {
            return null;
        }
        return switch (usage) {
            case OBJECT -> resolveDefaultObjectSetter(ownerClass);
            case LIST -> "setDefaultListForm"; //$NON-NLS-1$
            case CHOICE -> "setDefaultChoiceForm"; //$NON-NLS-1$
            case AUXILIARY -> null;
        };
    }

    private String resolveDefaultObjectSetter(String ownerClass) {
        return switch (ownerClass == null ? "" : ownerClass) { //$NON-NLS-1$
            case "InformationRegister" -> "setDefaultRecordForm"; //$NON-NLS-1$ //$NON-NLS-2$
            // Accumulation/Accounting/Calculation registers expose only a default list form;
            // their record-set forms have no default slot.
            case "AccumulationRegister", "AccountingRegister", "CalculationRegister" -> null; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> "setDefaultObjectForm"; //$NON-NLS-1$
        };
    }

    /**
     * Resolves the EDT {@code IFormGenerator} {@code FormType} enum constant name for an OBJECT-usage form.
     *
     * <p>The generic {@code OBJECT} form type only applies to object-bearing owners (catalogs, documents,
     * data processors, …). Registers have no object form: an InformationRegister generates a {@code RECORD}
     * form, and Accumulation/Accounting/Calculation registers generate a {@code RECORD_SET} form. Passing
     * {@code OBJECT} to the generator for a register yields a NullPointerException, which historically
     * surfaced as a misleading {@code EDT_SERVICE_UNAVAILABLE}.
     *
     * @param ownerClass the owner {@code eClass} name; may be {@code null}
     * @return the generator {@code FormType} constant name for an OBJECT-usage form
     */
    public String objectFormGeneratorType(String ownerClass) {
        return switch (ownerClass == null ? "" : ownerClass) { //$NON-NLS-1$
            case "InformationRegister" -> "RECORD"; //$NON-NLS-1$ //$NON-NLS-2$
            case "AccumulationRegister", "AccountingRegister", "CalculationRegister" -> "RECORD_SET"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            default -> "OBJECT"; //$NON-NLS-1$
        };
    }
}
