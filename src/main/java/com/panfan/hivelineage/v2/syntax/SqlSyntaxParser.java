package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.lexical.LexedUnit;

/**
 * Consumes an explicit lexed buffer; must not re-tokenize SQL text or consult metadata.
 */
public interface SqlSyntaxParser<P> {
    ParseResult<P> parse(LexedUnit unit, SyntaxOptions options);
}
