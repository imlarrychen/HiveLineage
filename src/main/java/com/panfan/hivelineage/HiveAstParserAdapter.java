package com.panfan.hivelineage;

public interface HiveAstParserAdapter {
    ParsedHiveStatement parse(String normalizedSql);
}
