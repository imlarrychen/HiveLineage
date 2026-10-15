package com.panfan.hivelineage.v2.export;

public enum ColumnDerivationKind {
    /**
     * One copied field value; aliases and multiple proof boundaries do not change its shape.
     */
    DIRECT,
    /**
     * A computed value, including calls whose arguments contain no source fields.
     */
    DERIVED,
    /**
     * A literal, static partition value, or set made entirely from literal values.
     */
    CONSTANT,
    /**
     * The available evidence cannot establish a complete classification.
     */
    UNKNOWN
}
