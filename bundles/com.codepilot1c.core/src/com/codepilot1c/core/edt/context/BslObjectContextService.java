/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.context;

import java.util.List;
import java.util.Optional;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ast.EdtAstService;
import com.codepilot1c.core.edt.ast.EdtMetadataInspectorService;
import com.codepilot1c.core.edt.ast.EdtReferenceService;
import com.codepilot1c.core.edt.ast.EdtServiceGateway;
import com.codepilot1c.core.edt.ast.FindReferencesRequest;
import com.codepilot1c.core.edt.ast.MetadataDetailsRequest;
import com.codepilot1c.core.edt.ast.MetadataDetailsResult;
import com.codepilot1c.core.edt.ast.MetadataNode;
import com.codepilot1c.core.edt.ast.ProjectReadinessChecker;
import com.codepilot1c.core.edt.ast.ReferenceSearchResult;
import com.codepilot1c.core.edt.context.BslObjectContextRequest.FormLayoutInclusion;
import com.codepilot1c.core.edt.context.BslObjectContextRequest.MethodInclusion;
import com.codepilot1c.core.edt.context.ObjectFqnResolver.ParsedFqn;
import com.codepilot1c.core.edt.forms.EdtFormService;
import com.codepilot1c.core.edt.forms.InspectFormLayoutRequest;
import com.codepilot1c.core.edt.forms.InspectFormLayoutResult;
import com.codepilot1c.core.edt.lang.BslModuleContextResult;
import com.codepilot1c.core.edt.lang.BslModuleExportsResult;
import com.codepilot1c.core.edt.lang.BslModuleMethodsRequest;
import com.codepilot1c.core.edt.lang.BslModuleRequest;
import com.codepilot1c.core.edt.lang.BslSemanticService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Orchestrator behind the {@code bsl_object_context} tool. Composes the
 * already-tested primitives ({@link BslSemanticService},
 * {@link EdtAstService}, {@link EdtFormService},
 * {@link EdtReferenceService}) into a single response whose verbosity is
 * dialed by the caller via {@link BslObjectContextRequest}.
 *
 * <p>The service is best-effort: an underlying call that fails (file
 * missing for an object kind, project not ready, etc.) is recorded in an
 * {@code errors[]} entry without aborting the rest of the response.
 * This matches the agent's expectation that an "include the part that
 * works" answer is better than a hard failure.</p>
 */
public class BslObjectContextService {

    private static final Gson GSON = new Gson();

    private final BslSemanticService bslSemanticService;
    private final EdtAstService edtAstService;
    private final EdtFormService edtFormService;
    private final EdtReferenceService edtReferenceService;
    private final ObjectFqnResolver fqnResolver;

    public BslObjectContextService() {
        this(new EdtServiceGateway());
    }

    public BslObjectContextService(EdtServiceGateway gateway) {
        this(new BslSemanticService(gateway),
                new EdtAstService(gateway),
                new EdtFormService(),
                new EdtReferenceService(gateway, new ProjectReadinessChecker(gateway)),
                new ObjectFqnResolver());
    }

    public BslObjectContextService(
            BslSemanticService bslSemanticService,
            EdtAstService edtAstService,
            EdtFormService edtFormService,
            EdtReferenceService edtReferenceService,
            ObjectFqnResolver fqnResolver) {
        this.bslSemanticService = bslSemanticService;
        this.edtAstService = edtAstService;
        this.edtFormService = edtFormService;
        this.edtReferenceService = edtReferenceService;
        this.fqnResolver = fqnResolver;
    }

    public JsonObject loadContext(BslObjectContextRequest req) {
        JsonObject out = new JsonObject();
        out.addProperty("project_name", req.projectName());
        out.addProperty("object_fqn", req.objectFqn());

        Optional<ParsedFqn> parsedOpt = fqnResolver.parse(req.objectFqn());
        if (parsedOpt.isEmpty()) {
            out.addProperty("error", "UNPARSEABLE_FQN");
            out.addProperty("message",
                    "Cannot parse '" + req.objectFqn() + "'. Expected 'Type.Name', where Type is one of: "
                            + listKnownKinds());
            return out;
        }
        ParsedFqn parsed = parsedOpt.get();
        out.addProperty("kind", parsed.kind().keyword());
        out.addProperty("name", parsed.name());

        JsonArray errors = new JsonArray();

        if (req.methods() != MethodInclusion.NONE) {
            JsonArray modules = collectModuleSections(req, parsed, errors);
            out.add("modules", modules);
        }

        MetadataDetailsResult details = null;
        if (req.attributes() || req.tabularSections() || req.formLayout() != FormLayoutInclusion.NONE) {
            try {
                details = edtAstService.getMetadataDetails(
                        new MetadataDetailsRequest(req.projectName(), List.of(req.objectFqn()), true, null));
            } catch (EdtAstException e) {
                errors.add(errorEntry("metadata_details", e.getMessage()));
            } catch (Exception e) {
                errors.add(errorEntry("metadata_details", classAndMessage(e)));
            }
        }
        if (details != null && !details.getNodes().isEmpty()) {
            MetadataNode root = details.getNodes().get(0);
            if (req.attributes()) {
                out.add("attributes", extractSubsection(root, "attributes", "standardAttributes"));
            }
            if (req.tabularSections()) {
                out.add("tabular_sections", extractSubsection(root, "tabularSections"));
            }
            if (req.formLayout() != FormLayoutInclusion.NONE) {
                out.add("forms", collectFormSections(req, parsed, root, errors));
            }
        }

        if (req.callers()) {
            out.add("callers", collectCallers(req, errors));
        }

        if (errors.size() > 0) {
            out.add("errors", errors);
        }
        return out;
    }

    // --- module sections (methods / exports / context) -----------------------

    private JsonArray collectModuleSections(
            BslObjectContextRequest req, ParsedFqn parsed, JsonArray errors) {
        JsonArray modules = new JsonArray();
        List<String> paths = fqnResolver.standardModulePaths(parsed);
        for (String relativePath : paths) {
            if (!includesModulePath(req, relativePath)) {
                continue;
            }
            JsonObject moduleEntry = new JsonObject();
            moduleEntry.addProperty("path", relativePath);
            try {
                BslModuleRequest moduleReq = new BslModuleRequest(req.projectName(), "src/" + relativePath);
                BslModuleContextResult ctx = bslSemanticService.getModuleContext(moduleReq);
                moduleEntry.add("context", GSON.toJsonTree(ctx));
                if (req.methods() != MethodInclusion.NONE) {
                    BslModuleMethodsRequest exportsReq = new BslModuleMethodsRequest(
                            req.projectName(), "src/" + relativePath, null, null, 200, 0);
                    BslModuleExportsResult exports = bslSemanticService.getModuleExports(exportsReq);
                    if (req.exportsOnly()) {
                        moduleEntry.add("exports", GSON.toJsonTree(exports));
                    } else {
                        moduleEntry.add("methods", GSON.toJsonTree(exports));
                    }
                }
                modules.add(moduleEntry);
            } catch (EdtAstException e) {
                // File simply absent for this object — record as info, not error.
                moduleEntry.addProperty("status", "missing");
                moduleEntry.addProperty("reason", e.getMessage());
                modules.add(moduleEntry);
            } catch (Exception e) {
                errors.add(errorEntry("module:" + relativePath, classAndMessage(e)));
            }
        }
        return modules;
    }

    private boolean includesModulePath(BslObjectContextRequest req, String relativePath) {
        String lower = relativePath.toLowerCase();
        if (lower.contains("/managermodule.bsl")) {
            return req.managerModuleMethods();
        }
        if (lower.contains("/objectmodule.bsl") || lower.contains("/recordsetmodule.bsl")
                || lower.contains("/valuemanagermodule.bsl")) {
            return req.objectModuleMethods();
        }
        return true;
    }

    // --- attributes / tabular_sections extraction ----------------------------

    private JsonElement extractSubsection(MetadataNode root, String... subsectionNames) {
        JsonObject details = GSON.toJsonTree(root).getAsJsonObject();
        JsonObject filtered = new JsonObject();
        JsonObject properties = details.has("properties") ? details.getAsJsonObject("properties") : null;
        for (String name : subsectionNames) {
            if (properties != null && properties.has(name)) {
                filtered.add(name, properties.get(name));
            } else if (details.has(name)) {
                filtered.add(name, details.get(name));
            }
        }
        return filtered;
    }

    // --- form layout ---------------------------------------------------------

    private JsonArray collectFormSections(
            BslObjectContextRequest req, ParsedFqn parsed, MetadataNode root, JsonArray errors) {
        JsonArray formsOut = new JsonArray();
        List<String> formNames = extractFormNames(root);
        boolean shallow = req.formLayout() == FormLayoutInclusion.SHALLOW;
        for (String formName : formNames) {
            JsonObject entry = new JsonObject();
            String formFqn = req.objectFqn() + ".Form." + formName;
            entry.addProperty("form_fqn", formFqn);
            entry.addProperty("module_path", fqnResolver.formModulePath(parsed, formName));
            try {
                InspectFormLayoutRequest formReq = new InspectFormLayoutRequest(
                        req.projectName(), formFqn, true, true, false, shallow ? 2 : 12, 500);
                InspectFormLayoutResult layout = edtFormService.inspectFormLayout(formReq);
                entry.add("layout", GSON.toJsonTree(layout));
            } catch (EdtAstException e) {
                entry.addProperty("status", "missing");
                entry.addProperty("reason", e.getMessage());
            } catch (Exception e) {
                errors.add(errorEntry("form:" + formName, classAndMessage(e)));
                continue;
            }
            formsOut.add(entry);
        }
        return formsOut;
    }

    private List<String> extractFormNames(MetadataNode root) {
        JsonObject details = GSON.toJsonTree(root).getAsJsonObject();
        JsonObject properties = details.has("properties") ? details.getAsJsonObject("properties") : null;
        JsonElement formsElement = null;
        if (properties != null && properties.has("forms")) {
            formsElement = properties.get("forms");
        } else if (details.has("forms")) {
            formsElement = details.get("forms");
        }
        if (formsElement == null || !formsElement.isJsonArray()) {
            return List.of();
        }
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (JsonElement el : formsElement.getAsJsonArray()) {
            if (el.isJsonPrimitive()) {
                names.add(el.getAsString());
            } else if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("name")) {
                    names.add(obj.get("name").getAsString());
                }
            }
        }
        return names;
    }

    // --- callers -------------------------------------------------------------

    private JsonObject collectCallers(BslObjectContextRequest req, JsonArray errors) {
        JsonObject out = new JsonObject();
        try {
            FindReferencesRequest refReq = new FindReferencesRequest(
                    req.projectName(), req.objectFqn(), req.callersMaxPerMethod());
            ReferenceSearchResult result = edtReferenceService.findReferences(refReq);
            out.add("references", GSON.toJsonTree(result));
        } catch (EdtAstException e) {
            errors.add(errorEntry("callers", e.getMessage()));
        } catch (Exception e) {
            errors.add(errorEntry("callers", classAndMessage(e)));
        }
        return out;
    }

    // --- diagnostics helpers -------------------------------------------------

    private static JsonObject errorEntry(String scope, String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("scope", scope);
        obj.addProperty("message", message);
        return obj;
    }

    private static String classAndMessage(Throwable t) {
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static String listKnownKinds() {
        StringBuilder sb = new StringBuilder();
        for (ObjectFqnResolver.ObjectKind kind : ObjectFqnResolver.ObjectKind.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(kind.keyword());
        }
        return sb.toString();
    }

    /** Test seam — used by source-grep contract tests to discover wiring. */
    BslSemanticService getBslSemanticService() {
        return bslSemanticService;
    }
}
