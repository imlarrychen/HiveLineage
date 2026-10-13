package com.panfan.hivelineage.v2.analysis;

public enum JoinKind {
    UNKNOWN,
    INNER,
    LEFT,
    RIGHT,
    FULL,
    LEFT_SEMI,
    LEFT_ANTI,
    CROSS,
    IMPLICIT
}
