package com.panfan.hivelineage.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class HiveJoinQueryBlockScopeResolver {
    QueryBlockScopes resolve(String sql) {
        CteExtraction ctes = extractCtes(sql);
        Map<String, String> sqlByPath = new HashMap<>();
        Map<String, String> projectionDefinitionPaths = new HashMap<>();
        for (Map.Entry<String, String> cte : ctes.ctes.entrySet()) {
            String path = "cte." + cte.getKey();
            sqlByPath.put(path, cte.getValue());
            projectionDefinitionPaths.put(cte.getKey(), path);
            collectInlineScopes(cte.getValue(), path, sqlByPath, projectionDefinitionPaths);
        }
        sqlByPath.put("root", ctes.rootSql);
        collectInlineScopes(ctes.rootSql, "root", sqlByPath, projectionDefinitionPaths);
        return new QueryBlockScopes(sqlByPath, projectionDefinitionPaths);
    }

    private void collectInlineScopes(
        String sql,
        String parentPath,
        Map<String, String> sqlByPath,
        Map<String, String> projectionDefinitionPaths
    ) {
        int subqueryIndex = 0;
        int position = 0;
        int whereIndex = HiveSqlTextUtils.topLevelKeywordIndex(sql, "where", 0);
        while (position < sql.length()) {
            if (sql.charAt(position) != '(') {
                position++;
                continue;
            }
            int close = HiveSqlTextUtils.findMatchingParen(sql, position);
            if (close <= position) {
                position++;
                continue;
            }
            String body = sql.substring(position + 1, close).trim();
            if (isQueryBody(body)) {
                String path = parentPath
                    + (whereIndex >= 0 && position > whereIndex ? ".where.exists[" : ".subquery[")
                    + (++subqueryIndex)
                    + "]";
                sqlByPath.put(path, body);
                String alias = readAliasAfter(sql, close + 1);
                if (alias != null) {
                    projectionDefinitionPaths.put(alias, path);
                }
                collectInlineScopes(body, path, sqlByPath, projectionDefinitionPaths);
            }
            position = close + 1;
        }
    }

    private boolean isQueryBody(String sql) {
        return HiveSqlTextUtils.matchesKeywordAt(sql, 0, "select")
            || HiveSqlTextUtils.matchesKeywordAt(sql, 0, "with");
    }

    private String readAliasAfter(String sql, int start) {
        int position = HiveSqlTextUtils.skipWhitespace(sql, start);
        int end = HiveSqlTextUtils.readIdentifier(sql, position);
        if (end > position && "as".equalsIgnoreCase(sql.substring(position, end))) {
            position = HiveSqlTextUtils.skipWhitespace(sql, end);
            end = HiveSqlTextUtils.readIdentifier(sql, position);
        }
        if (end <= position) {
            return null;
        }
        String alias = HiveSqlTextUtils.normalizeIdentifier(sql.substring(position, end));
        return HiveSqlTextUtils.isAliasKeyword(alias) ? null : alias.toLowerCase(Locale.ROOT);
    }

    private CteExtraction extractCtes(String sql) {
        String trimmed = sql.trim();
        Map<String, String> ctes = new HashMap<>();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("with ")) {
            return new CteExtraction(ctes, trimmed);
        }
        int position = HiveSqlTextUtils.skipWhitespace(trimmed, 4);
        while (position < trimmed.length()) {
            int nameStart = position;
            int nameEnd = HiveSqlTextUtils.readIdentifier(trimmed, nameStart);
            if (nameEnd <= nameStart) {
                break;
            }
            String cteName = HiveSqlTextUtils.normalizeIdentifier(trimmed.substring(nameStart, nameEnd));
            position = HiveSqlTextUtils.skipWhitespace(trimmed, nameEnd);
            if (position < trimmed.length() && trimmed.charAt(position) == '(') {
                position = HiveSqlTextUtils.findMatchingParen(trimmed, position) + 1;
                position = HiveSqlTextUtils.skipWhitespace(trimmed, position);
            }
            int asEnd = HiveSqlTextUtils.readIdentifier(trimmed, position);
            if (asEnd <= position || !"as".equalsIgnoreCase(trimmed.substring(position, asEnd))) {
                break;
            }
            position = HiveSqlTextUtils.skipWhitespace(trimmed, asEnd);
            if (position >= trimmed.length() || trimmed.charAt(position) != '(') {
                break;
            }
            int bodyStart = position + 1;
            int bodyEnd = HiveSqlTextUtils.findMatchingParen(trimmed, position);
            if (bodyEnd < bodyStart) {
                break;
            }
            ctes.put(cteName, trimmed.substring(bodyStart, bodyEnd).trim());
            position = HiveSqlTextUtils.skipWhitespace(trimmed, bodyEnd + 1);
            if (position < trimmed.length() && trimmed.charAt(position) == ',') {
                position = HiveSqlTextUtils.skipWhitespace(trimmed, position + 1);
                continue;
            }
            break;
        }
        return new CteExtraction(ctes, position < trimmed.length() ? trimmed.substring(position).trim() : "");
    }

    static final class QueryBlockScopes {
        private final Map<String, String> sqlByPath;
        private final Map<String, String> projectionDefinitionPaths;

        private QueryBlockScopes(Map<String, String> sqlByPath, Map<String, String> projectionDefinitionPaths) {
            this.sqlByPath = sqlByPath;
            this.projectionDefinitionPaths = projectionDefinitionPaths;
        }

        Map<String, String> getSqlByPath() {
            return sqlByPath;
        }

        Map<String, String> getProjectionDefinitionPaths() {
            return projectionDefinitionPaths;
        }
    }

    private static final class CteExtraction {
        private final Map<String, String> ctes;
        private final String rootSql;

        private CteExtraction(Map<String, String> ctes, String rootSql) {
            this.ctes = ctes;
            this.rootSql = rootSql;
        }
    }
}
