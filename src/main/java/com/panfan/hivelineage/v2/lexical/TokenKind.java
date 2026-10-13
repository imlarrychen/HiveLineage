package com.panfan.hivelineage.v2.lexical;

public enum TokenKind {
    IDENTIFIER,
    KEYWORD,
    STRING_LITERAL,
    NUMBER_LITERAL,
    BOOLEAN_LITERAL,
    NULL_LITERAL,
    OPERATOR,
    PUNCTUATION,
    PARAMETER,
    END_OF_INPUT,
    UNKNOWN
}
