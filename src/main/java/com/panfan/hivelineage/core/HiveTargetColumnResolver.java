package com.panfan.hivelineage.core;

import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.metadata.TableIdentifier;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.LineageWarning;
import com.panfan.hivelineage.result.WarningCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HiveTargetColumnResolver {
    private static final Pattern SOURCE_ALIAS = Pattern.compile(
        "(?is)\\b(?:from|join)\\s+([a-zA-Z_][\\w.]*)\\s+(?:as\\s+)?([a-zA-Z_][\\w]*)\\b"
    );
    private static final Pattern SOURCE_OBJECT = Pattern.compile(
        "(?is)\\b(?:from|join)\\s+([a-zA-Z_][\\w.]*)"
    );

    List<String> parseTargetColumns(String rawColumns) {
        if (rawColumns == null || rawColumns.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> columns = new ArrayList<>();
        for (String column : rawColumns.split(",")) {
            columns.add(column.trim());
        }
        return columns;
    }

    List<String> inferTargetColumns(List<String> selectItems) {
        List<String> columns = new ArrayList<>();
        for (String item : selectItems) {
            String alias = HiveSqlTextUtils.extractAlias(item);
            columns.add(alias == null ? HiveSqlTextUtils.stripAlias(item) : alias);
        }
        return columns;
    }

    List<String> resolveTargetColumns(
        LineageAnalyzeRequest request,
        String targetTable,
        List<String> selectItems,
        LineageParseMode parseMode
    ) {
        return resolveTargetColumns(
            request,
            targetTable,
            selectItems,
            parseMode,
            Collections.<LineageWarning>emptyList(),
            -1
        );
    }

    List<String> resolveTargetColumns(
        LineageAnalyzeRequest request,
        String targetTable,
        List<String> selectItems,
        LineageParseMode parseMode,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        if (parseMode == LineageParseMode.COLUMN_ONLY || parseMode == LineageParseMode.TABLE_AND_COLUMN) {
            HiveTableMetadata metadata = loadTableMetadata(request, targetTable, warnings, statementIndex);
            if (metadata != null && metadata.getColumns().size() >= selectItems.size()) {
                return new ArrayList<>(metadata.getColumns().subList(0, selectItems.size()));
            }
        }
        return inferTargetColumns(selectItems);
    }

    List<String> expandStarSelectItems(
        LineageAnalyzeRequest request,
        List<String> selectItems,
        List<DataObjectRef> inputObjects,
        LineageParseMode parseMode
    ) {
        return expandStarSelectItems(
            request,
            null,
            selectItems,
            inputObjects,
            parseMode,
            Collections.<LineageWarning>emptyList(),
            -1
        );
    }

    List<String> expandStarSelectItems(
        LineageAnalyzeRequest request,
        String targetTable,
        List<String> selectItems,
        List<DataObjectRef> inputObjects,
        LineageParseMode parseMode,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        if ((parseMode != LineageParseMode.COLUMN_ONLY && parseMode != LineageParseMode.TABLE_AND_COLUMN)
            || selectItems.size() != 1
            || !"*".equals(selectItems.get(0).trim())
            || inputObjects.size() != 1) {
            return selectItems;
        }
        if (targetTable != null) {
            HiveTableMetadata targetMetadata = loadTableMetadata(request, targetTable, warnings, statementIndex);
            if (targetMetadata != null && !targetMetadata.getColumns().isEmpty()) {
                HiveTableMetadata sourceMetadata = loadTableMetadataWithoutWarning(request, inputObjects.get(0).getQualifiedName());
                if (sourceMetadata != null && !sourceMetadata.getColumns().isEmpty()) {
                    List<String> intersection = intersectionInTargetOrder(targetMetadata.getColumns(), sourceMetadata.getColumns());
                    if (!intersection.isEmpty()) {
                        return intersection;
                    }
                }
                return new ArrayList<>(targetMetadata.getColumns());
            }
        }
        HiveTableMetadata sourceMetadata = loadTableMetadata(request, inputObjects.get(0).getQualifiedName(), warnings, statementIndex);
        if (sourceMetadata == null || sourceMetadata.getColumns().isEmpty()) {
            return selectItems;
        }
        return new ArrayList<>(sourceMetadata.getColumns());
    }

    boolean isSingleStarSelect(List<String> selectItems) {
        return selectItems.size() == 1 && "*".equals(selectItems.get(0).trim());
    }

    List<String> intersectionInTargetOrder(List<String> targetColumns, List<String> sourceColumns) {
        Set<String> sourceColumnSet = new LinkedHashSet<>();
        for (String sourceColumn : sourceColumns) {
            sourceColumnSet.add(sourceColumn.toLowerCase(Locale.ROOT));
        }
        List<String> intersection = new ArrayList<>();
        for (String targetColumn : targetColumns) {
            if (sourceColumnSet.contains(targetColumn.toLowerCase(Locale.ROOT))) {
                intersection.add(targetColumn);
            }
        }
        return intersection;
    }

    HiveTableMetadata loadTableMetadataWithoutWarning(LineageAnalyzeRequest request, String tableName) {
        if (request.getMetadataProvider() == null) {
            return null;
        }
        TableIdentifier tableIdentifier = TableIdentifier.parse(tableName, request.getClusterName(), request.getDefaultDatabase());
        return request.getMetadataCache().getTableMetadata(tableIdentifier, request.getMetadataProvider());
    }

    HiveTableMetadata loadTableMetadata(LineageAnalyzeRequest request, String tableName) {
        return loadTableMetadata(request, tableName, Collections.<LineageWarning>emptyList(), -1);
    }

    HiveTableMetadata loadTableMetadata(
        LineageAnalyzeRequest request,
        String tableName,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        if (request.getMetadataProvider() == null) {
            return null;
        }
        TableIdentifier tableIdentifier = TableIdentifier.parse(tableName, request.getClusterName(), request.getDefaultDatabase());
        HiveTableMetadata metadata = request.getMetadataCache().getTableMetadata(tableIdentifier, request.getMetadataProvider());
        if (metadata == null) {
            addWarning(warnings, WarningCode.METADATA_NOT_FOUND, "Metadata not found for " + tableIdentifier.getQualifiedName(), statementIndex);
        }
        return metadata;
    }

    Map<String, String> extractSourceAliases(String normalizedStatement) {
        Map<String, String> aliases = new HashMap<>();
        Matcher objectMatcher = SOURCE_OBJECT.matcher(normalizedStatement);
        while (objectMatcher.find()) {
            String tableName = HiveSqlTextUtils.normalizeIdentifier(objectMatcher.group(1));
            String simpleTableName = tableName.contains(".") ? tableName.substring(tableName.lastIndexOf('.') + 1) : tableName;
            aliases.put(tableName, tableName);
            aliases.put(simpleTableName, tableName);
        }
        Matcher aliasMatcher = SOURCE_ALIAS.matcher(normalizedStatement);
        while (aliasMatcher.find()) {
            String tableName = HiveSqlTextUtils.normalizeIdentifier(aliasMatcher.group(1));
            String alias = aliasMatcher.group(2);
            if (alias != null && !HiveSqlTextUtils.isAliasKeyword(alias)) {
                aliases.put(alias.toLowerCase(Locale.ROOT), tableName);
            }
        }
        return aliases;
    }

    void mergeTopLevelSourceAliases(Map<String, String> sourceAliases, Map<String, String> topLevelAliases) {
        for (Map.Entry<String, String> alias : topLevelAliases.entrySet()) {
            if (alias.getValue().contains(".") || !sourceAliases.containsKey(alias.getKey())) {
                sourceAliases.put(alias.getKey(), alias.getValue());
            }
        }
    }

    Map<String, String> filterColumnAliasSources(
        Map<String, String> columnAliasSources,
        Map<String, String> topLevelAliases
    ) {
        Set<String> topLevelPhysicalAliases = new LinkedHashSet<>();
        for (Map.Entry<String, String> alias : topLevelAliases.entrySet()) {
            if (alias.getValue().contains(".") && !alias.getKey().contains(".")) {
                topLevelPhysicalAliases.add(alias.getKey().toLowerCase(Locale.ROOT));
            }
        }
        if (topLevelPhysicalAliases.isEmpty()) {
            return columnAliasSources;
        }
        Map<String, String> filtered = new HashMap<>();
        for (Map.Entry<String, String> source : columnAliasSources.entrySet()) {
            int dotIndex = source.getKey().indexOf('.');
            String owner = dotIndex < 0 ? source.getKey() : source.getKey().substring(0, dotIndex);
            if (!topLevelPhysicalAliases.contains(owner.toLowerCase(Locale.ROOT))) {
                filtered.put(source.getKey(), source.getValue());
            }
        }
        return filtered;
    }

    Map<String, String> extractTopLevelSourceAliases(String normalizedStatement) {
        Map<String, String> aliases = new HashMap<>();
        int start = HiveSqlTextUtils.lastTopLevelKeywordIndex(normalizedStatement, "insert");
        if (start < 0) {
            start = HiveSqlTextUtils.lastTopLevelKeywordIndex(normalizedStatement, "select");
        }
        if (start < 0) {
            start = 0;
        }
        int depth = 0;
        char quote = 0;
        for (int i = start; i < normalizedStatement.length(); i++) {
            char ch = normalizedStatement.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                continue;
            }
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0 && (HiveSqlTextUtils.matchesKeywordAt(normalizedStatement, i, "from") || HiveSqlTextUtils.matchesKeywordAt(normalizedStatement, i, "join"))) {
                i = captureTopLevelSourceAlias(normalizedStatement, i, aliases);
            }
        }
        return aliases;
    }

    int captureTopLevelSourceAlias(String statement, int keywordIndex, Map<String, String> aliases) {
        int position = HiveSqlTextUtils.skipWhitespace(statement, keywordIndex);
        position = HiveSqlTextUtils.readIdentifier(statement, position);
        position = HiveSqlTextUtils.skipWhitespace(statement, position);
        if (position >= statement.length() || statement.charAt(position) == '(') {
            return Math.max(keywordIndex, position - 1);
        }
        int tableStart = position;
        int tableEnd = HiveSqlTextUtils.readIdentifier(statement, tableStart);
        if (tableEnd == tableStart) {
            return position;
        }
        String tableName = HiveSqlTextUtils.normalizeIdentifier(statement.substring(tableStart, tableEnd));
        String simpleTableName = tableName.contains(".") ? tableName.substring(tableName.lastIndexOf('.') + 1) : tableName;
        aliases.put(tableName, tableName);
        aliases.put(simpleTableName, tableName);

        position = HiveSqlTextUtils.skipWhitespace(statement, tableEnd);
        int aliasStart = position;
        int aliasEnd = HiveSqlTextUtils.readIdentifier(statement, aliasStart);
        if (aliasEnd > aliasStart && "as".equalsIgnoreCase(statement.substring(aliasStart, aliasEnd))) {
            aliasStart = HiveSqlTextUtils.skipWhitespace(statement, aliasEnd);
            aliasEnd = HiveSqlTextUtils.readIdentifier(statement, aliasStart);
        }
        if (aliasEnd > aliasStart) {
            String alias = statement.substring(aliasStart, aliasEnd);
            if (!HiveSqlTextUtils.isAliasKeyword(alias)) {
                aliases.put(alias.toLowerCase(Locale.ROOT), tableName);
            }
        }
        return tableEnd;
    }






    Map<String, String> buildColumnOwners(
        LineageAnalyzeRequest request,
        List<DataObjectRef> inputObjects,
        LineageParseMode parseMode
    ) {
        return buildColumnOwners(
            request,
            inputObjects,
            parseMode,
            Collections.<LineageWarning>emptyList(),
            -1
        );
    }

    Map<String, String> buildColumnOwners(
        LineageAnalyzeRequest request,
        List<DataObjectRef> inputObjects,
        LineageParseMode parseMode,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        Map<String, String> columnOwners = new HashMap<>();
        if (inputObjects.size() <= 1 || (parseMode != LineageParseMode.COLUMN_ONLY && parseMode != LineageParseMode.TABLE_AND_COLUMN)) {
            return columnOwners;
        }
        for (DataObjectRef inputObject : inputObjects) {
            HiveTableMetadata metadata = loadTableMetadata(request, inputObject.getQualifiedName(), warnings, statementIndex);
            if (metadata == null) {
                continue;
            }
            for (String column : metadata.getColumns()) {
                String normalizedColumn = column.toLowerCase(Locale.ROOT);
                if (!columnOwners.containsKey(normalizedColumn)) {
                    columnOwners.put(normalizedColumn, inputObject.getQualifiedName());
                }
            }
        }
        return columnOwners;
    }

    void addWarning(List<LineageWarning> warnings, WarningCode warningCode, String message, int statementIndex) {
        if (warnings == null) {
            return;
        }
        for (LineageWarning warning : warnings) {
            if (warning.getWarningCode() == warningCode
                && warning.getStatementIndex() == statementIndex
                && warning.getMessage().equals(message)) {
                return;
            }
        }
        warnings.add(new LineageWarning(warningCode, message, statementIndex));
    }
}
