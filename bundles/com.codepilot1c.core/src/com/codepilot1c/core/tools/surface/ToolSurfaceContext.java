/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

/**
 * Immutable per-tool surface context passed to contributors.
 */
public final class ToolSurfaceContext {

    private final ToolCategory category;
    private final boolean builtIn;

    private ToolSurfaceContext(Builder builder) {
        this.category = builder.category != null ? builder.category : ToolCategory.DYNAMIC;
        this.builtIn = builder.builtIn;
    }

    public static ToolSurfaceContext passthrough() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return builder()
                .category(category)
                .builtIn(builtIn);
    }

    public ToolCategory getCategory() {
        return category;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public static final class Builder {
        private ToolCategory category;
        private boolean builtIn;

        public Builder category(ToolCategory category) {
            this.category = category;
            return this;
        }

        public Builder builtIn(boolean builtIn) {
            this.builtIn = builtIn;
            return this;
        }

        public ToolSurfaceContext build() {
            return new ToolSurfaceContext(this);
        }
    }
}
