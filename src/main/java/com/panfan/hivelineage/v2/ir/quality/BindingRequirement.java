package com.panfan.hivelineage.v2.ir.quality;

/**
 * Binding work requested before analysis; unrequested regions are not failures.
 */
public enum BindingRequirement {
    OBJECTS, CONDITION_FIELDS, OUTPUT_SCHEMA, SINK_MAPPING, DEFINITION_EXPANSION, SESSION_EFFECTS, DATASET_VERSIONS
}
