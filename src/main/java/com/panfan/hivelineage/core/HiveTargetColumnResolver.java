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
    private static final String COMMA_REGEX = ",";
    private final HivePhysicalNameResolver nameResolver;

    HiveTargetColumnResolver(HivePhysicalNameResolver nameResolver) {
        this.nameResolver = nameResolver;
    }

    List<String> parseTargetColumns(String rawColumns) {
        if (rawColumns == null || rawColumns.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> columns = new ArrayList<>();
        for (String column : rawColumns.split(COMMA_REGEX)) {
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
            new ColumnResolutionContext(
                request,
                parseMode,
                Collections.<LineageWarning>emptyList(),
                -1
            ),
            null,
            selectItems,
            inputObjects,
            new QualifiedOwnerResolver(inputObjects, Collections.<String, String>emptyMap())
        );
    }

    List<String> expandStarSelectItems(
        ColumnResolutionContext context,
        String targetTable,
        List<String> selectItems,
        List<DataObjectRef> inputObjects
    ) {
        return expandStarSelectItems(
            context,
            targetTable,
            selectItems,
            inputObjects,
            new QualifiedOwnerResolver(inputObjects, Collections.<String, String>emptyMap())
        );
    }

    List<String> expandStarSelectItems(
        ColumnResolutionContext context,
        String targetTable,
        List<String> selectItems,
        List<DataObjectRef> inputObjects,
        QualifiedOwnerResolver ownerResolver
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        LineageParseMode parseMode = context.getParseMode();
        List<LineageWarning> warnings = context.getWarnings();
        int statementIndex = context.getStatementIndex();
        if ((parseMode != LineageParseMode.COLUMN_ONLY && parseMode != LineageParseMode.TABLE_AND_COLUMN)
            || inputObjects.isEmpty()) {
            return selectItems;
        }
        if (isSingleStarSelect(selectItems) && inputObjects.size() == 1) {
            return expandSingleInputStar(context, targetTable, selectItems, inputObjects.get(0));
        }
        List<String> expanded = new ArrayList<>();
        for (String selectItem : selectItems) {
            QualifiedColumnReference reference = QualifiedColumnReference.parse(HiveSqlTextUtils.stripAlias(selectItem));
            if (reference == null || !reference.isStar()) {
                expanded.add(selectItem);
                continue;
            }
            String sourceTable = ownerResolver.resolve(reference.getOwner());
            if (sourceTable == null) {
                addWarning(
                    warnings,
                    WarningCode.UNKNOWN_COLUMN_LINEAGE,
                    "Unable to resolve source table for star expression: " + selectItem.trim(),
                    statementIndex
                );
                expanded.add(selectItem);
                continue;
            }
            HiveTableMetadata sourceMetadata = loadTableMetadata(request, sourceTable, warnings, statementIndex);
            if (sourceMetadata == null || sourceMetadata.getColumns().isEmpty()) {
                expanded.add(selectItem);
                continue;
            }
            for (String column : sourceMetadata.getColumns()) {
                expanded.add(sourceTable + "." + column);
            }
        }
        return expanded;
    }

    private List<String> expandSingleInputStar(
        ColumnResolutionContext context,
        String targetTable,
        List<String> selectItems,
        DataObjectRef inputObject
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        List<LineageWarning> warnings = context.getWarnings();
        int statementIndex = context.getStatementIndex();
        if (targetTable != null) {
            List<String> targetColumns = targetColumnsForSingleInput(request, targetTable, inputObject, warnings, statementIndex);
            if (!targetColumns.isEmpty()) {
                return targetColumns;
            }
        }
        HiveTableMetadata sourceMetadata = loadTableMetadata(request, inputObject.getQualifiedName(), warnings, statementIndex);
        if (sourceMetadata == null || sourceMetadata.getColumns().isEmpty()) {
            return selectItems;
        }
        return new ArrayList<>(sourceMetadata.getColumns());
    }

    private List<String> targetColumnsForSingleInput(
        LineageAnalyzeRequest request,
        String targetTable,
        DataObjectRef inputObject,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        HiveTableMetadata targetMetadata = loadTableMetadata(request, targetTable, warnings, statementIndex);
        if (targetMetadata == null || targetMetadata.getColumns().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> intersection = targetSourceColumnIntersection(request, targetMetadata, inputObject);
        return intersection.isEmpty() ? new ArrayList<>(targetMetadata.getColumns()) : intersection;
    }

    private List<String> targetSourceColumnIntersection(
        LineageAnalyzeRequest request,
        HiveTableMetadata targetMetadata,
        DataObjectRef inputObject
    ) {
        HiveTableMetadata sourceMetadata = loadTableMetadataWithoutWarning(request, inputObject.getQualifiedName());
        if (sourceMetadata == null || sourceMetadata.getColumns().isEmpty()) {
            return Collections.emptyList();
        }
        return intersectionInTargetOrder(targetMetadata.getColumns(), sourceMetadata.getColumns());
    }

    List<String> expandPositionalSingleInputStar(
        ColumnResolutionContext context,
        List<String> selectItems,
        DataObjectRef inputObject
    ) {
        HiveTableMetadata sourceMetadata = loadTableMetadata(
            context.getRequest(),
            inputObject.getQualifiedName(),
            context.getWarnings(),
            context.getStatementIndex()
        );
        if (sourceMetadata == null || sourceMetadata.getColumns().isEmpty()) {
            return selectItems;
        }
        List<String> expanded = new ArrayList<>();
        for (String column : sourceMetadata.getColumns()) {
            expanded.add(inputObject.getQualifiedName() + "." + column);
        }
        return expanded;
    }

    boolean isSingleStarSelect(List<String> selectItems) {
        return selectItems.size() == 1 && "*".equals(selectItems.get(0).trim());
    }

    boolean hasColumnMetadataWithoutWarning(LineageAnalyzeRequest request, DataObjectRef inputObject) {
        HiveTableMetadata metadata = loadTableMetadataWithoutWarning(request, inputObject.getQualifiedName());
        return metadata != null && !metadata.getColumns().isEmpty();
    }

    boolean hasSingleTopLevelDirectSourceRelation(String statement) {
        int fromIndex = HiveSqlTextUtils.topLevelKeywordIndex(statement, "from", 0);
        if (fromIndex < 0) {
            return false;
        }
        int relationStart = HiveSqlTextUtils.skipWhitespace(statement, fromIndex + 4);
        if (relationStart >= statement.length() || statement.charAt(relationStart) == '(') {
            return false;
        }

        int relationEnd = statement.length();
        String[] boundaryKeywords = {
            "where", "group", "having", "order", "limit", "distribute", "sort", "cluster",
            "union", "intersect", "except"
        };
        for (String keyword : boundaryKeywords) {
            int boundary = HiveSqlTextUtils.topLevelKeywordIndex(statement, keyword, relationStart);
            if (boundary >= 0) {
                relationEnd = Math.min(relationEnd, boundary);
            }
        }
        int joinIndex = HiveSqlTextUtils.topLevelKeywordIndex(statement, "join", relationStart);
        int lateralIndex = HiveSqlTextUtils.topLevelKeywordIndex(statement, "lateral", relationStart);
        if ((joinIndex >= 0 && joinIndex < relationEnd)
            || (lateralIndex >= 0 && lateralIndex < relationEnd)) {
            return false;
        }

        int depth = 0;
        char quote = 0;
        for (int index = relationStart; index < relationEnd; index++) {
            char ch = statement.charAt(index);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth = Math.max(0, depth - 1);
            } else if (ch == ',' && depth == 0) {
                return false;
            }
        }
        return true;
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

    List<String> resolveStarTargetColumns(
        LineageAnalyzeRequest request,
        String targetTable,
        List<String> expandedSourceItems,
        LineageParseMode parseMode,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        if (parseMode == LineageParseMode.COLUMN_ONLY || parseMode == LineageParseMode.TABLE_AND_COLUMN) {
            HiveTableMetadata metadata = loadTableMetadata(request, targetTable, warnings, statementIndex);
            if (metadata != null && !metadata.getColumns().isEmpty()) {
                return new ArrayList<>(metadata.getColumns());
            }
        }
        List<String> inferred = new ArrayList<>();
        for (String sourceItem : expandedSourceItems) {
            QualifiedColumnReference reference = QualifiedColumnReference.parse(HiveSqlTextUtils.stripAlias(sourceItem));
            inferred.add(reference == null || reference.isStar()
                ? HiveSqlTextUtils.stripAlias(sourceItem)
                : reference.getColumn());
        }
        return inferred;
    }

    HiveTableMetadata loadTableMetadataWithoutWarning(LineageAnalyzeRequest request, String tableName) {
        if (request.getMetadataProvider() == null) {
            return null;
        }
        TableIdentifier tableIdentifier = nameResolver.toTableIdentifier(tableName, request.getClusterName());
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
        TableIdentifier tableIdentifier = nameResolver.toTableIdentifier(tableName, request.getClusterName());
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

    Map<String, String> extractExplicitSourceAliases(String normalizedStatement) {
        Map<String, String> explicitAliases = new HashMap<>();
        scanTopLevelSourceAliases(normalizedStatement, new HashMap<String, String>(), explicitAliases);
        return explicitAliases;
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
        return extractTopLevelSourceAliasScope(normalizedStatement).getAliases();
    }

    SourceAliasScope extractTopLevelSourceAliasScope(String normalizedStatement) {
        Map<String, String> aliases = new HashMap<>();
        Set<String> ambiguousSimpleNames = scanSourceAliases(normalizedStatement, aliases, null, true);
        return new SourceAliasScope(aliases, ambiguousSimpleNames);
    }

    private void scanTopLevelSourceAliases(
        String normalizedStatement,
        Map<String, String> aliases,
        Map<String, String> explicitAliases
    ) {
        scanSourceAliases(normalizedStatement, aliases, explicitAliases, true);
    }

    private Set<String> scanSourceAliases(
        String normalizedStatement,
        Map<String, String> aliases,
        Map<String, String> explicitAliases,
        boolean topLevelOnly
    ) {
        Set<String> ambiguousSimpleNames = new LinkedHashSet<>();
        Set<String> explicitAliasNames = new LinkedHashSet<>();
        Set<String> exactSimpleSourceNames = new LinkedHashSet<>();
        SourceAliasScanState state = new SourceAliasScanState(
            aliases,
            explicitAliases,
            ambiguousSimpleNames,
            explicitAliasNames,
            exactSimpleSourceNames
        );
        int start = topLevelOnly ? HiveSqlTextUtils.lastTopLevelKeywordIndex(normalizedStatement, "insert") : 0;
        if (topLevelOnly && start < 0) {
            start = HiveSqlTextUtils.lastTopLevelKeywordIndex(normalizedStatement, "select");
        }
        start = Math.max(0, start);
        int depth = 0;
        char quote = 0;
        int position = start;
        while (position < normalizedStatement.length()) {
            char ch = normalizedStatement.charAt(position);
            if (quote != 0) {
                int nextPosition = skipQuotedCharacter(normalizedStatement, position, quote);
                quote = nextPosition < 0 ? 0 : quote;
                position = Math.abs(nextPosition);
                continue;
            }
            if (ch == '-' && position + 1 < normalizedStatement.length()
                && normalizedStatement.charAt(position + 1) == '-') {
                position += 2;
                while (position < normalizedStatement.length()
                    && normalizedStatement.charAt(position) != '\n'
                    && normalizedStatement.charAt(position) != '\r') {
                    position++;
                }
                continue;
            }
            if (ch == '/' && position + 1 < normalizedStatement.length()
                && normalizedStatement.charAt(position + 1) == '*') {
                int commentEnd = normalizedStatement.indexOf("*/", position + 2);
                position = commentEnd < 0 ? normalizedStatement.length() : commentEnd + 2;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                position++;
                continue;
            }
            if (ch == '(') {
                depth++;
                position++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                position++;
                continue;
            }
            if ((!topLevelOnly || depth == 0) && isSourceKeywordAt(normalizedStatement, position)) {
                position = captureTopLevelSourceAlias(normalizedStatement, position, state) + 1;
                continue;
            }
            position++;
        }
        return ambiguousSimpleNames;
    }

    private int skipQuotedCharacter(String statement, int position, char quote) {
        char ch = statement.charAt(position);
        if (ch == '\\' && quote != '`' && position + 1 < statement.length()) {
            return position + 2;
        }
        if (ch == quote && isEscapedQuote(statement, position, quote)) {
            return position + 2;
        }
        if (ch == quote) {
            return -(position + 1);
        }
        return position + 1;
    }

    private boolean isEscapedQuote(String statement, int position, char quote) {
        return position + 1 < statement.length() && statement.charAt(position + 1) == quote;
    }

    static final class SourceAliasScope {
        private final Map<String, String> aliases;
        private final Set<String> ambiguousSimpleNames;

        private SourceAliasScope(Map<String, String> aliases, Set<String> ambiguousSimpleNames) {
            this.aliases = aliases;
            this.ambiguousSimpleNames = ambiguousSimpleNames;
        }

        Map<String, String> getAliases() {
            return aliases;
        }

        Set<String> getAmbiguousSimpleNames() {
            return ambiguousSimpleNames;
        }
    }

    private boolean isSourceKeywordAt(String statement, int position) {
        return HiveSqlTextUtils.matchesKeywordAt(statement, position, "from")
            || HiveSqlTextUtils.matchesKeywordAt(statement, position, "join");
    }

    int captureTopLevelSourceAlias(String statement, int keywordIndex, Map<String, String> aliases) {
        SourceAliasScanState state = new SourceAliasScanState(
            aliases,
            null,
            new LinkedHashSet<String>(),
            new LinkedHashSet<String>(),
            new LinkedHashSet<String>()
        );
        return captureTopLevelSourceAlias(statement, keywordIndex, state);
    }

    private int captureTopLevelSourceAlias(
        String statement,
        int keywordIndex,
        SourceAliasScanState state
    ) {
        int position = HiveSqlTextUtils.skipWhitespace(statement, keywordIndex);
        position = HiveSqlTextUtils.readIdentifier(statement, position);
        position = HiveSqlTextUtils.skipWhitespace(statement, position);
        if (position >= statement.length() || statement.charAt(position) == '(') {
            return Math.max(keywordIndex, position - 1);
        }
        int tableStart = position;
        int tableEnd = readPossiblyQuotedIdentifier(statement, tableStart);
        if (tableEnd == tableStart) {
            return position;
        }
        String tableName = normalizePossiblyQuotedIdentifier(statement.substring(tableStart, tableEnd));
        String simpleTableName = tableName.contains(".") ? tableName.substring(tableName.lastIndexOf('.') + 1) : tableName;
        state.aliases.put(tableName, tableName);
        if (!tableName.contains(".")) {
            state.exactSimpleSourceNames.add(simpleTableName);
            state.aliases.put(simpleTableName, tableName);
        } else if (!state.explicitAliasNames.contains(simpleTableName)
            && !state.exactSimpleSourceNames.contains(simpleTableName)
            && !state.ambiguousSimpleNames.contains(simpleTableName)) {
            String existing = state.aliases.get(simpleTableName);
            if (existing == null || existing.equals(tableName)) {
                state.aliases.put(simpleTableName, tableName);
            } else {
                state.aliases.remove(simpleTableName);
                state.ambiguousSimpleNames.add(simpleTableName);
            }
        }

        position = HiveSqlTextUtils.skipWhitespace(statement, tableEnd);
        int aliasStart = position;
        int aliasEnd = readPossiblyQuotedIdentifier(statement, aliasStart);
        boolean quotedAlias = aliasStart < statement.length() && statement.charAt(aliasStart) == '`';
        if (aliasEnd > aliasStart && "as".equalsIgnoreCase(statement.substring(aliasStart, aliasEnd))) {
            aliasStart = HiveSqlTextUtils.skipWhitespace(statement, aliasEnd);
            aliasEnd = readPossiblyQuotedIdentifier(statement, aliasStart);
            quotedAlias = aliasStart < statement.length() && statement.charAt(aliasStart) == '`';
        }
        if (aliasEnd > aliasStart) {
            String alias = normalizePossiblyQuotedIdentifier(statement.substring(aliasStart, aliasEnd));
            if (quotedAlias || !HiveSqlTextUtils.isAliasKeyword(alias)) {
                state.aliases.put(alias, tableName);
                state.explicitAliasNames.add(alias);
                state.ambiguousSimpleNames.remove(alias);
                if (state.explicitAliases != null) {
                    state.explicitAliases.put(alias, tableName);
                }
            }
        }
        return tableEnd;
    }

    private static final class SourceAliasScanState {
        private final Map<String, String> aliases;
        private final Map<String, String> explicitAliases;
        private final Set<String> ambiguousSimpleNames;
        private final Set<String> explicitAliasNames;
        private final Set<String> exactSimpleSourceNames;

        private SourceAliasScanState(
            Map<String, String> aliases,
            Map<String, String> explicitAliases,
            Set<String> ambiguousSimpleNames,
            Set<String> explicitAliasNames,
            Set<String> exactSimpleSourceNames
        ) {
            this.aliases = aliases;
            this.explicitAliases = explicitAliases;
            this.ambiguousSimpleNames = ambiguousSimpleNames;
            this.explicitAliasNames = explicitAliasNames;
            this.exactSimpleSourceNames = exactSimpleSourceNames;
        }
    }

    private int readPossiblyQuotedIdentifier(String statement, int position) {
        int cursor = position;
        boolean consumedPart = false;
        while (cursor < statement.length()) {
            int partStart = cursor;
            if (statement.charAt(cursor) == '`') {
                int closingBacktick = statement.indexOf('`', cursor + 1);
                if (closingBacktick < 0 || closingBacktick == cursor + 1) {
                    return position;
                }
                cursor = closingBacktick + 1;
            } else {
                while (cursor < statement.length()) {
                    char ch = statement.charAt(cursor);
                    if (!Character.isLetterOrDigit(ch) && ch != '_') {
                        break;
                    }
                    cursor++;
                }
                if (cursor == partStart) {
                    break;
                }
            }
            consumedPart = true;
            if (cursor >= statement.length() || statement.charAt(cursor) != '.') {
                break;
            }
            cursor++;
        }
        return consumedPart ? cursor : position;
    }

    private String normalizePossiblyQuotedIdentifier(String identifier) {
        return HiveSqlTextUtils.normalizeIdentifier(identifier.replace("`", ""));
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
