package com.panfan.hivelineage.v2.metadata;

/**
 * A missing value alone never proves that an object does not exist.
 */
public enum LookupStatus {
    FOUND,
    UNKNOWN,
    NOT_FOUND,
    CAPABILITY_MISSING,
    ACCESS_ERROR
}

