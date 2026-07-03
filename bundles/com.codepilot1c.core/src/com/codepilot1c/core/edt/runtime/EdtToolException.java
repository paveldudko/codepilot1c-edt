package com.codepilot1c.core.edt.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured runtime tool error with a stable code and an optional flat map of machine-readable
 * detail fields (e.g. the lease holder for {@code EDT_LEASE_HELD}) that tool renderers can attach
 * to the error payload verbatim, so callers never have to parse them back out of the message.
 */
public class EdtToolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final EdtToolErrorCode code;
    private final Map<String, String> details;

    public EdtToolException(EdtToolErrorCode code, String message) {
        this(code, message, (Map<String, String>) null);
    }

    public EdtToolException(EdtToolErrorCode code, String message, Map<String, String> details) {
        super(message);
        this.code = code;
        this.details = copyDetails(details);
    }

    public EdtToolException(EdtToolErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = Collections.emptyMap();
    }

    public EdtToolErrorCode getCode() {
        return code;
    }

    /** Machine-readable detail fields for the error payload; empty when none were supplied. */
    public Map<String, String> getDetails() {
        return details;
    }

    private static Map<String, String> copyDetails(Map<String, String> src) {
        if (src == null || src.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : src.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                copy.put(e.getKey(), e.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
    }
}
