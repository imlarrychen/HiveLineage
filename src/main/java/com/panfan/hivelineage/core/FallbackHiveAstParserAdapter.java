package com.panfan.hivelineage.core;

public final class FallbackHiveAstParserAdapter implements HiveAstParserAdapter {
    private final HiveAstParserAdapter primary;
    private final HiveAstParserAdapter fallback;

    public FallbackHiveAstParserAdapter(HiveAstParserAdapter primary, HiveAstParserAdapter fallback) {
        if (primary == null) {
            throw new IllegalArgumentException("primary adapter must not be null");
        }
        if (fallback == null) {
            throw new IllegalArgumentException("fallback adapter must not be null");
        }
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public ParsedHiveStatement parse(String normalizedSql) {
        try {
            ParsedHiveStatement parsed = primary.parse(normalizedSql);
            if (isUsable(parsed)) {
                return parsed;
            }
        } catch (RuntimeException ignored) {
            // Keep batch parsing resilient: Hive Parser failures should not stop heuristic extraction.
        }
        return fallback.parse(normalizedSql);
    }

    private boolean isUsable(ParsedHiveStatement parsed) {
        return parsed != null
            && (parsed.getStatementType() != StatementType.UNSUPPORTED
            || parsed.getOutputObject() != null
            || !parsed.getInputObjects().isEmpty());
    }
}
