package com.panfan.hivelineage.v2.syntax;

public enum ExpressionKind {
    NAME_REF,
    STAR,
    LITERAL,
    CALL,
    UNARY,
    BINARY,
    CASE,
    CAST,
    FIELD_ACCESS,
    SUBSCRIPT,
    SUBQUERY,
    WINDOW_CALL,
    IN_LIST,
    BETWEEN,
    UNKNOWN
}
