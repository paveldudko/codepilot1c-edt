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

    private final String activeProviderId;
    private final ToolCategory category;
    private final boolean builtIn;
    private final boolean backendSelectedInUi;

    private ToolSurfaceContext(Builder builder) {
        this.activeProviderId = builder.activeProviderId;
        this.category = builder.category != null ? builder.category : ToolCategory.DYNAMIC;
        this.builtIn = builder.builtIn;
        this.backendSelectedInUi = builder.backendSelectedInUi;
    }

    public static ToolSurfaceContext passthrough() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return builder()
                .activeProviderId(activeProviderId)
                .category(category)
                .builtIn(builtIn)
                .backendSelectedInUi(backendSelectedInUi);
    }

    public String getActiveProviderId() {
        return activeProviderId;
    }

    public ToolCategory getCategory() {
        return category;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public boolean isBackendSelectedInUi() {
        return backendSelectedInUi;
    }

    public static final class Builder {
        private String activeProviderId;
        private ToolCategory category;
        private boolean builtIn;
        private boolean backendSelectedInUi;

        public Builder activeProviderId(String activeProviderId) {
            this.activeProviderId = activeProviderId;
            return this;
        }

        public Builder category(ToolCategory category) {
            this.category = category;
            return this;
        }

        public Builder builtIn(boolean builtIn) {
            this.builtIn = builtIn;
            return this;
        }

        public Builder backendSelectedInUi(boolean backendSelectedInUi) {
            this.backendSelectedInUi = backendSelectedInUi;
            return this;
        }

        public ToolSurfaceContext build() {
            return new ToolSurfaceContext(this);
        }
    }
}
