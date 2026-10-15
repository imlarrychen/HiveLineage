package com.panfan.hivelineage.v2.dialect;

import com.panfan.hivelineage.v2.lexical.SqlLexer;
import com.panfan.hivelineage.v2.syntax.SqlSyntaxParser;
import com.panfan.hivelineage.v2.syntax.SyntaxNormalizer;

/**
 * Pairs a parser and normalizer with the same private artifact type; contains no Hive implementation.
 */
public interface DialectFactory<P> {
    DialectProfile getProfile();

    SqlLexer createLexer();

    SqlSyntaxParser<P> createParser();

    SyntaxNormalizer<P> createNormalizer();
}
