package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.dialect.DialectFactory;
import com.panfan.hivelineage.v2.dialect.DialectProfile;
import com.panfan.hivelineage.v2.lexical.SqlLexer;
import com.panfan.hivelineage.v2.syntax.SqlSyntaxParser;
import com.panfan.hivelineage.v2.syntax.SyntaxNormalizer;

import java.util.Collections;

public final class HiveDialectFactory implements DialectFactory<HiveParseArtifact> {
    @Override
    public DialectProfile getProfile() {
        return new DialectProfile("hive", "4.0.1", "4.0.1", Collections.singleton("OVERWRITE_TARGET_COLUMNS"));
    }

    @Override
    public SqlLexer createLexer() {
        return new HiveSqlLexer();
    }

    @Override
    public SqlSyntaxParser<HiveParseArtifact> createParser() {
        return new HiveSqlSyntaxParser();
    }

    @Override
    public SyntaxNormalizer<HiveParseArtifact> createNormalizer() {
        return new HiveSyntaxNormalizer();
    }
}
