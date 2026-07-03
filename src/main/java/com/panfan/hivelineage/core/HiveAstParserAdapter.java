package com.panfan.hivelineage.core;

public interface HiveAstParserAdapter {
    ParsedHiveStatement parse(String normalizedSql);
}
