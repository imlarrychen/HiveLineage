package com.panfan.hivelineage.v2.metadata;

/** Cache and demand dimensions; task versions are not metadata dimensions. */
public enum MetadataFacet {
    OBJECT_DESCRIPTOR,
    RELATION_SCHEMA,
    RELATION_METADATA,
    DEFINITION,
    STRUCTURE_VERSION
}

