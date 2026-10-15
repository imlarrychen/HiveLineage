package com.panfan.hivelineage.v2.common;

/**
 * Explicit request limit failure; infrastructure must not reclassify it as access failure.
 */
public final class ResourceLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ResourceLimitException(String message) {
        super(message);
    }
}
