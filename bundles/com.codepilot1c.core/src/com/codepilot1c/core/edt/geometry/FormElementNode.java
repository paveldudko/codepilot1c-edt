/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.geometry;

import java.util.List;

/**
 * EMF-decoupled tree node used by the geometry engine. The EMF Form is
 * walked once into a {@link FormElementNode} tree; the engine then runs
 * a pure-Java layout pass on that tree.
 *
 * <p>Splitting the EMF walk from the geometry math lets us unit-test
 * layout independently of any Eclipse runtime.</p>
 *
 * @param name            item identifier (the BSL FormItem name)
 * @param kind            element kind (see {@link Kind})
 * @param visible         true if the item is visible (invisible items
 *                        are skipped by the engine and never receive a
 *                        bbox in the layout result)
 * @param enabled         enabled flag, propagated to the result
 * @param horizontalGroup for GROUP-kind nodes only: if true, children
 *                        lay out side-by-side instead of stacked
 * @param dataPath        optional BSL data path (e.g. {@code Object.LastName})
 * @param children        ordered child nodes
 */
public record FormElementNode(
        String name,
        Kind kind,
        boolean visible,
        boolean enabled,
        boolean horizontalGroup,
        String dataPath,
        List<FormElementNode> children) {

    public enum Kind { FORM, GROUP, PAGES, PAGE, FIELD, BUTTON, TABLE, DECORATION, COMMAND_BAR, COLUMN }

    public FormElementNode {
        children = List.copyOf(children == null ? List.of() : children);
    }
}
