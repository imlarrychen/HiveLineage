package com.panfan.hivelineage.core;

import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.StatementParseStatus;
import com.panfan.hivelineage.result.TableLineage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HiveSelectLineageStatementAnalyzer {
    private static final int PARSE_STATUS_SUCCESS = StatementParseStatus.SUCCESS.getCode();
    private static final int PARSE_STATUS_FAILED = StatementParseStatus.FAILED.getCode();
    private static final Pattern DYNAMIC_PARTITION_COLUMNS = Pattern.compile("(?is)\\bpartition\\s*\\(([^)]*)\\)");

    private final HiveAstParserAdapter parserAdapter;
    private final HiveStatementDatabaseBinder databaseBinder;
    private final HiveTargetColumnResolver targetColumnResolver;
    private final HiveProjectionLineageResolver projectionResolver;
    private final HiveLineageExpansionService expansionService;
    private final HiveColumnLineageBuilder columnLineageBuilder;
    private final HiveJoinConditionAnalyzer joinAnalyzer;

    HiveSelectLineageStatementAnalyzer(Dependencies dependencies) {
        this.parserAdapter = dependencies.parserAdapter;
        this.databaseBinder = dependencies.databaseBinder;
        this.targetColumnResolver = dependencies.targetColumnResolver;
        this.projectionResolver = dependencies.projectionResolver;
        this.expansionService = dependencies.expansionService;
        this.columnLineageBuilder = dependencies.columnLineageBuilder;
        this.joinAnalyzer = dependencies.joinAnalyzer;
    }

    StatementLineageResult analyze(
        StatementAnalysisContext context,
        String operation,
        String targetTable,
        List<String> targetColumns,
        List<String> selectItems,
        ParsedHiveStatement parsedStatement
    ) {
        SelectAnalysisWork work = prepareAnalysisWork(
            context,
            operation,
            targetTable,
            targetColumns,
            selectItems,
            parsedStatement
        );
        if (work.failure != null) {
            return work.failure;
        }
        LineageAnalyzeRequest request = context.getRequest();
        LineageParseMode parseMode = request.getOptions().getParseMode();
        List<TableLineage> tableLineages = buildTableLineages(parseMode, operation, context, work);
        List<ColumnLineage> columnLineages = buildColumnLineages(parseMode, context, work);
        List<JoinCondition> joinConditions = request.getOptions().isParseJoinConditions()
            ? joinAnalyzer.buildJoinConditions(context, work.parsedStatement)
            : Collections.<JoinCondition>emptyList();
        return successResult(context, operation, work, tableLineages, columnLineages, joinConditions);
    }

    private SelectAnalysisWork prepareAnalysisWork(
        StatementAnalysisContext context,
        String operation,
        String targetTable,
        List<String> targetColumns,
        List<String> selectItems,
        ParsedHiveStatement parsedStatement
    ) {
        SelectAnalysisWork work = new SelectAnalysisWork();
        work.parsedStatement = parsedStatement == null
            ? parseAndBind(context, context.getNormalizedStatement())
            : parsedStatement;
        work.targetTable = resolvedTargetTable(work.parsedStatement, targetTable);
        work.targetObject = new DataObjectRef(work.targetTable);
        work.originalInputObjects = filterOutputObjectFromInputs(work.parsedStatement, work.targetTable);
        work.analysisSelectItems = selectItems;
        if (work.originalInputObjects.isEmpty()) {
            work.failure = failedResult(context, "SQL_PARSE_ERROR", "No input object found");
            return work;
        }
        populateSetExpansion(context, work);
        if (work.failure != null) {
            return work;
        }
        work.scope = buildAnalysisScope(
            context,
            work.parsedStatement,
            work.analysisStatement,
            work.originalInputObjects
        );
        populateSingleProjectionStarColumns(context, work);
        populateSelectItems(context, operation, targetColumns, work);
        return work;
    }

    private String resolvedTargetTable(ParsedHiveStatement parsedStatement, String fallbackTargetTable) {
        return parsedStatement.getOutputObject() == null
            ? fallbackTargetTable
            : parsedStatement.getOutputObject().getQualifiedName();
    }

    private void populateSetExpansion(StatementAnalysisContext context, SelectAnalysisWork work) {
        LineageAnalyzeRequest request = context.getRequest();
        LineageParseMode parseMode = request.getOptions().getParseMode();
        work.columnContext = new ColumnResolutionContext(
            request,
            parseMode,
            context.getWarnings(),
            context.getStatementIndex()
        );
        HiveSetOperationStarExpander setOperationStarExpander = new HiveSetOperationStarExpander(targetColumnResolver);
        work.setExpansion = setOperationStarExpander.expand(work.columnContext, context.getNormalizedStatement());
        work.topLevelSetOperation = work.setExpansion.isSetOperation();
        if (work.topLevelSetOperation && !work.setExpansion.hasConsistentBranchWidths()) {
            work.failure = failedResult(
                context,
                "COLUMN_COUNT_MISMATCH",
                "Set operation branch columns do not align"
            );
            return;
        }
        work.analysisStatement = work.setExpansion.getSql();
        if (!work.analysisStatement.equals(context.getNormalizedStatement())) {
            work.parsedStatement = parseAndBind(context, work.analysisStatement);
            work.originalInputObjects = filterOutputObjectFromInputs(work.parsedStatement, work.targetTable);
            work.analysisSelectItems = work.parsedStatement.getSelectExpressions();
        }
    }

    private List<DataObjectRef> filterOutputObjectFromInputs(ParsedHiveStatement parsedStatement, String targetTable) {
        if (parsedStatement.getStatementType() == StatementType.INSERT_SELECT || targetTable == null) {
            return parsedStatement.getInputObjects();
        }
        List<DataObjectRef> filtered = new ArrayList<>();
        for (DataObjectRef inputObject : parsedStatement.getInputObjects()) {
            if (!targetTable.equalsIgnoreCase(inputObject.getQualifiedName())) {
                filtered.add(inputObject);
            }
        }
        return filtered;
    }

    private void populateSelectItems(
        StatementAnalysisContext context,
        String operation,
        List<String> targetColumns,
        SelectAnalysisWork work
    ) {
        boolean starSelect = targetColumnResolver.isSingleStarSelect(work.analysisSelectItems);
        work.positionalSingleDirectSourceStar = isPositionalSingleDirectSourceStar(
            positionalStarCheck(operation, targetColumns, starSelect, work)
        ) && targetColumnResolver.hasColumnMetadataWithoutWarning(
            context.getRequest(),
            work.originalInputObjects.get(0)
        );
        work.textualSelectItems = extractTopLevelSelectItems(work.analysisStatement);
        work.expandedSelectItems = expandSelectItems(work);
        List<String> inferredTargetColumns = inferTargetColumns(targetColumns, starSelect, work);
        work.resolvedTargetColumns = alignTargetColumnsForStaticPartitions(
            inferredTargetColumns,
            work.expandedSelectItems.size(),
            work.parsedStatement.getStaticPartitionValues(),
            extractDynamicPartitionColumns(work.analysisStatement)
        );
        if (work.resolvedTargetColumns.size() != work.expandedSelectItems.size()) {
            work.failure = failedResult(context, "COLUMN_COUNT_MISMATCH", "Target columns and select items do not align");
        }
    }

    private void populateSingleProjectionStarColumns(StatementAnalysisContext context, SelectAnalysisWork work) {
        String projectionTable = projectionResolver.singleProjectionTable(
            work.scope.topLevelProjectionSourceAliases,
            work.scope.statementProjectionTables
        );
        if (projectionTable == null) {
            projectionTable = singleLogicalTopLevelSource(work.scope.topLevelProjectionSourceAliases);
            if (projectionTable == null || work.originalInputObjects.size() <= 1) {
                return;
            }
            work.scope.statementProjectionTables.put(projectionTable, new LinkedHashMap<String, ColumnLineageProjection>());
        }
        Map<String, ColumnLineageProjection> projections = work.scope.statementProjectionTables.get(projectionTable);
        if (projections == null || !projections.isEmpty()) {
            return;
        }
        List<String> columns = commonPhysicalInputColumns(context, work.originalInputObjects);
        for (String column : columns) {
            projections.put(column.toLowerCase(Locale.ROOT), projectionFromPhysicalInputs(work.originalInputObjects, column));
        }
    }

    private String singleLogicalTopLevelSource(Map<String, String> aliases) {
        Set<String> sources = new LinkedHashSet<>();
        for (String source : aliases.values()) {
            if (!source.contains(".")) {
                sources.add(source);
            }
        }
        return sources.size() == 1 ? sources.iterator().next() : null;
    }

    private List<String> commonPhysicalInputColumns(StatementAnalysisContext context, List<DataObjectRef> inputObjects) {
        List<String> columns = null;
        for (DataObjectRef inputObject : inputObjects) {
            HiveTableMetadata metadata = targetColumnResolver.loadTableMetadata(
                context.getRequest(),
                inputObject.getQualifiedName(),
                context.getWarnings(),
                context.getStatementIndex()
            );
            if (metadata == null || metadata.getColumns().isEmpty()) {
                return Collections.emptyList();
            }
            columns = columns == null
                ? new ArrayList<>(metadata.getColumns())
                : targetColumnResolver.intersectionInTargetOrder(columns, metadata.getColumns());
        }
        return columns == null ? Collections.<String>emptyList() : columns;
    }

    private ColumnLineageProjection projectionFromPhysicalInputs(List<DataObjectRef> inputObjects, String column) {
        List<ColumnRef> sources = new ArrayList<>();
        List<String> expressions = new ArrayList<>();
        for (DataObjectRef inputObject : inputObjects) {
            String sourceColumn = inputObject.getQualifiedName() + "." + column;
            sources.add(new ColumnRef(sourceColumn));
            expressions.add(sourceColumn);
        }
        String expression = sources.size() == 1
            ? sources.get(0).getQualifiedName()
            : "union_all(" + HiveSqlTextUtils.joinWithComma(expressions) + ")";
        return new ColumnLineageProjection(sources, expression, sources.size() == 1 ? LineageType.DIRECT : LineageType.DERIVED);
    }

    private StatementLineageResult successResult(
        StatementAnalysisContext context,
        String operation,
        SelectAnalysisWork work,
        List<TableLineage> tableLineages,
        List<ColumnLineage> columnLineages,
        List<JoinCondition> joinConditions
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        int index = context.getStatementIndex();
        return statementBuilder(request, index)
            .statementType(operation)
            .rawSql(context.getRawStatement())
            .normalizedSql(context.getNormalizedStatement())
            .parseStatusCode(PARSE_STATUS_SUCCESS)
            .formalLineageProduced(true)
            .outputObject(work.targetObject)
            .inputObjects(work.scope.inputObjects)
            .parseMode(request.getOptions().getParseMode())
            .tableLineages(tableLineages)
            .columnLineages(columnLineages)
            .joinConditions(joinConditions)
            .warnings(context.getWarnings())
            .build();
    }

    private ParsedHiveStatement parseAndBind(StatementAnalysisContext context, String sql) {
        return databaseBinder.bind(
            parserAdapter.parse(sql),
            context.getStatementDatabase(),
            context.getTemporaryTableSources().keySet(),
            isCreateTemporaryTable(sql)
        );
    }

    private boolean isCreateTemporaryTable(String normalizedStatement) {
        return normalizedStatement.trim().toLowerCase(Locale.ROOT).startsWith("create temporary table ");
    }

    private List<String> expandSelectItems(SelectAnalysisWork work) {
        if (work.topLevelSetOperation) {
            List<String> textSelectItems = projectionResolver.setOperationTextSelectItems(work.analysisStatement);
            return textSelectItems.isEmpty() ? work.analysisSelectItems : textSelectItems;
        }
        if (work.textualSelectItems.size() > work.analysisSelectItems.size()
            && !containsStarSelectItem(work.textualSelectItems)) {
            return work.textualSelectItems;
        }
        if (work.positionalSingleDirectSourceStar) {
            return targetColumnResolver.expandPositionalSingleInputStar(
                work.columnContext,
                work.analysisSelectItems,
                work.originalInputObjects.get(0)
            );
        }
        List<String> projectionColumns = projectionStarSelectItems(work);
        if (!projectionColumns.isEmpty()) {
            return projectionColumns;
        }
        List<DataObjectRef> starInputObjects = starExpansionInputObjects(work);
        return targetColumnResolver.expandStarSelectItems(
            work.columnContext,
            work.targetTable,
            work.analysisSelectItems,
            starInputObjects,
            new QualifiedOwnerResolver(starInputObjects, topLevelExplicitAliases(work))
        );
    }

    private boolean containsStarSelectItem(List<String> selectItems) {
        for (String selectItem : selectItems) {
            QualifiedColumnReference reference = QualifiedColumnReference.parse(HiveSqlTextUtils.stripAlias(selectItem));
            if (reference != null && reference.isStar()) {
                return true;
            }
        }
        return false;
    }

    private List<String> projectionStarSelectItems(SelectAnalysisWork work) {
        if (!targetColumnResolver.isSingleStarSelect(work.analysisSelectItems)) {
            return Collections.emptyList();
        }
        String projectionTable = projectionResolver.singleProjectionTable(
            work.scope.topLevelProjectionSourceAliases,
            work.scope.statementProjectionTables
        );
        Map<String, ColumnLineageProjection> projections = projectionTable == null
            ? null
            : work.scope.statementProjectionTables.get(projectionTable);
        return projections == null || projections.isEmpty()
            ? Collections.<String>emptyList()
            : new ArrayList<>(projections.keySet());
    }

    private Map<String, String> topLevelExplicitAliases(SelectAnalysisWork work) {
        Map<String, String> explicitAliases = targetColumnResolver.extractExplicitSourceAliases(work.analysisStatement);
        return databaseBinder.bindExtractedAliasTargets(explicitAliases, work.originalInputObjects);
    }

    private List<DataObjectRef> starExpansionInputObjects(SelectAnalysisWork work) {
        List<DataObjectRef> topLevelInputs = inputObjectsFromAliases(work.scope.topLevelSourceAliases);
        return topLevelInputs.isEmpty() ? work.originalInputObjects : topLevelInputs;
    }

    private List<DataObjectRef> inputObjectsFromAliases(Map<String, String> aliases) {
        List<DataObjectRef> inputObjects = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String source : aliases.values()) {
            if (source.contains(".") && seen.add(source)) {
                inputObjects.add(new DataObjectRef(source));
            }
        }
        return inputObjects;
    }

    private List<String> extractTopLevelSelectItems(String statement) {
        int selectIndex = HiveSqlTextUtils.topLevelKeywordIndex(statement, "select", 0);
        int fromIndex = HiveSqlTextUtils.topLevelKeywordIndex(statement, "from", selectIndex < 0 ? 0 : selectIndex + 6);
        if (selectIndex < 0 || fromIndex < 0 || fromIndex <= selectIndex) {
            return Collections.emptyList();
        }
        return HiveSqlTextUtils.splitSelectItems(statement.substring(selectIndex + 6, fromIndex));
    }

    private List<String> inferTargetColumns(
        List<String> targetColumns,
        boolean starSelect,
        SelectAnalysisWork work
    ) {
        if (!targetColumns.isEmpty()) {
            return targetColumns;
        }
        if (work.positionalSingleDirectSourceStar) {
            return targetColumnResolver.resolveStarTargetColumns(
                work.columnContext.getRequest(),
                work.targetTable,
                work.expandedSelectItems,
                work.columnContext.getParseMode(),
                work.columnContext.getWarnings(),
                work.columnContext.getStatementIndex()
            );
        }
        return starSelect
            ? new ArrayList<>(work.expandedSelectItems)
            : targetColumnResolver.resolveTargetColumns(
                work.columnContext.getRequest(),
                work.targetTable,
                work.expandedSelectItems,
                work.columnContext.getParseMode(),
                work.columnContext.getWarnings(),
                work.columnContext.getStatementIndex()
            );
    }

    private AnalysisScope buildAnalysisScope(
        StatementAnalysisContext context,
        ParsedHiveStatement parsedStatement,
        String analysisStatement,
        List<DataObjectRef> originalInputObjects
    ) {
        AnalysisScope scope = new AnalysisScope();
        scope.sourceAliases = databaseBinder.bindExtractedAliasTargets(
            targetColumnResolver.extractSourceAliases(analysisStatement),
            originalInputObjects
        );
        mergeMissingSourceAliases(scope.sourceAliases, parsedStatement.getSourceAliases());
        scope.topLevelSourceAliases = databaseBinder.bindExtractedAliasTargets(
            targetColumnResolver.extractTopLevelSourceAliases(analysisStatement),
            originalInputObjects
        );
        targetColumnResolver.mergeTopLevelSourceAliases(scope.sourceAliases, scope.topLevelSourceAliases);
        scope.topLevelProjectionSourceAliases = databaseBinder.bindExtractedAliasTargets(
            projectionResolver.extractProjectionSourceAliases(analysisStatement),
            originalInputObjects
        );
        scope.sourceAliases.putAll(scope.topLevelProjectionSourceAliases);
        Map<String, String> explicitSourceAliases = databaseBinder.bindExtractedAliasTargets(
            targetColumnResolver.extractExplicitSourceAliases(analysisStatement),
            originalInputObjects
        );
        scope.ownerResolver = new QualifiedOwnerResolver(originalInputObjects, explicitSourceAliases);
        scope.statementProjectionTables = projectionResolver.bindPhysicalProjectionSources(
            projectionResolver.extractStatementProjectionTables(analysisStatement),
            scope.sourceAliases,
            databaseBinder
        );
        scope.throughTemporaryTable = expansionService.containsTemporaryTable(
            originalInputObjects,
            context.getTemporaryTableSources()
        );
        List<DataObjectRef> expandedTemporaryInputs = expansionService.expandTemporaryInputObjects(
            originalInputObjects,
            context.getTemporaryTableSources()
        );
        scope.columnReferenceInputObjects = expansionService.filterProjectionInputObjects(
            expandedTemporaryInputs,
            scope.statementProjectionTables
        );
        scope.inputObjects = expansionService.expandViewInputObjects(
            context,
            scope.columnReferenceInputObjects,
            0,
            new LinkedHashSet<String>()
        );
        scope.defaultColumnSourceTable = scope.columnReferenceInputObjects.get(0).getQualifiedName();
        return scope;
    }

    private void mergeMissingSourceAliases(Map<String, String> aliases, Map<String, String> fallbackAliases) {
        for (Map.Entry<String, String> fallbackAlias : fallbackAliases.entrySet()) {
            if (!aliases.containsKey(fallbackAlias.getKey())) {
                aliases.put(fallbackAlias.getKey(), fallbackAlias.getValue());
            }
        }
    }

    private List<TableLineage> buildTableLineages(
        LineageParseMode parseMode,
        String operation,
        StatementAnalysisContext context,
        SelectAnalysisWork work
    ) {
        if (parseMode != LineageParseMode.TABLE_ONLY && parseMode != LineageParseMode.TABLE_AND_COLUMN) {
            return Collections.emptyList();
        }
        List<TableLineage> tableLineages = new ArrayList<>();
        tableLineages.add(new TableLineage(
            work.targetObject,
            work.scope.inputObjects,
            operation,
            context.getStatementIndex(),
            work.scope.throughTemporaryTable
        ));
        return tableLineages;
    }

    private List<ColumnLineage> buildColumnLineages(
        LineageParseMode parseMode,
        StatementAnalysisContext context,
        SelectAnalysisWork work
    ) {
        if (parseMode != LineageParseMode.COLUMN_ONLY && parseMode != LineageParseMode.TABLE_AND_COLUMN) {
            return Collections.emptyList();
        }
        return columnLineageBuilder.build(HiveColumnLineageBuilder.Input.of(
            context,
            work.parsedStatement,
            new HiveColumnLineageBuilder.Target(
                work.targetTable,
                work.resolvedTargetColumns,
                work.expandedSelectItems,
                work.textualSelectItems
            ),
            new HiveColumnLineageBuilder.SetOperation(work.setExpansion, work.analysisStatement),
            new HiveColumnLineageBuilder.Sources(
                work.originalInputObjects,
                work.scope.sourceAliases,
                work.scope.topLevelSourceAliases,
                work.scope.topLevelProjectionSourceAliases,
                work.scope.statementProjectionTables,
                work.scope.ownerResolver
            ),
            new HiveColumnLineageBuilder.InputObjects(
                work.scope.columnReferenceInputObjects,
                work.scope.inputObjects,
                work.scope.defaultColumnSourceTable
            )
        ));
    }

    private PositionalStarCheck positionalStarCheck(
        String operation,
        List<String> targetColumns,
        boolean starSelect,
        SelectAnalysisWork work
    ) {
        PositionalStarCheck check = new PositionalStarCheck();
        check.operation = operation;
        check.targetColumns = targetColumns;
        check.starSelect = starSelect;
        check.topLevelSetOperation = work.topLevelSetOperation;
        check.analysisStatement = work.analysisStatement;
        check.inputObjects = work.originalInputObjects;
        check.topLevelSourceAliases = work.scope.topLevelSourceAliases;
        return check;
    }

    private boolean isPositionalSingleDirectSourceStar(PositionalStarCheck check) {
        if (!hasPositionalInsertStarShape(check) || !hasSingleTopLevelInput(check)) {
            return false;
        }
        Set<String> topLevelSources = new LinkedHashSet<>(check.topLevelSourceAliases.values());
        return topLevelSources.size() == 1
            && topLevelSources.iterator().next().equalsIgnoreCase(check.inputObjects.get(0).getQualifiedName());
    }

    private boolean hasPositionalInsertStarShape(PositionalStarCheck check) {
        return check.operation.startsWith("INSERT_")
            && check.targetColumns.isEmpty()
            && check.starSelect
            && !check.topLevelSetOperation;
    }

    private boolean hasSingleTopLevelInput(PositionalStarCheck check) {
        return targetColumnResolver.hasSingleTopLevelDirectSourceRelation(check.analysisStatement)
            && check.inputObjects.size() == 1;
    }

    private List<String> alignTargetColumnsForStaticPartitions(
        List<String> targetColumns,
        int selectItemCount,
        Map<String, String> staticPartitionValues,
        List<String> dynamicPartitionColumns
    ) {
        if (staticPartitionValues.isEmpty() && dynamicPartitionColumns.isEmpty()) {
            return targetColumns;
        }
        List<String> nonStaticTargetColumns = new ArrayList<>();
        for (String targetColumn : targetColumns) {
            if (!staticPartitionValues.containsKey(targetColumn.toLowerCase(Locale.ROOT))) {
                nonStaticTargetColumns.add(targetColumn);
            }
        }
        if (!dynamicPartitionColumns.isEmpty()) {
            List<String> aligned = alignDynamicPartitionColumns(
                nonStaticTargetColumns,
                selectItemCount,
                dynamicPartitionColumns
            );
            if (aligned != null) {
                return aligned;
            }
        }
        return nonStaticTargetColumns.size() >= selectItemCount
            ? new ArrayList<>(nonStaticTargetColumns.subList(0, selectItemCount))
            : targetColumns;
    }

    private List<String> alignDynamicPartitionColumns(
        List<String> nonStaticTargetColumns,
        int selectItemCount,
        List<String> dynamicPartitionColumns
    ) {
        int regularColumnCount = Math.max(0, selectItemCount - dynamicPartitionColumns.size());
        if (nonStaticTargetColumns.size() < regularColumnCount) {
            return null;
        }
        List<String> aligned = new ArrayList<>(nonStaticTargetColumns.subList(0, regularColumnCount));
        for (String dynamicPartitionColumn : dynamicPartitionColumns) {
            addDynamicPartitionColumn(aligned, nonStaticTargetColumns, dynamicPartitionColumn);
        }
        return aligned.size() == selectItemCount ? aligned : null;
    }

    private void addDynamicPartitionColumn(
        List<String> aligned,
        List<String> nonStaticTargetColumns,
        String dynamicPartitionColumn
    ) {
        if (!containsIgnoreCase(aligned, dynamicPartitionColumn)) {
            aligned.add(preferredColumnCase(nonStaticTargetColumns, dynamicPartitionColumn));
        }
    }

    private boolean containsIgnoreCase(List<String> values, String value) {
        for (String candidate : values) {
            if (candidate.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private String preferredColumnCase(List<String> candidates, String value) {
        for (String candidate : candidates) {
            if (candidate.equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        return value;
    }

    private List<String> extractDynamicPartitionColumns(String normalizedStatement) {
        Matcher matcher = DYNAMIC_PARTITION_COLUMNS.matcher(normalizedStatement);
        if (!matcher.find()) {
            return Collections.emptyList();
        }
        List<String> dynamicColumns = new ArrayList<>();
        for (String partitionItem : HiveSqlTextUtils.splitSelectItems(matcher.group(1))) {
            if (!partitionItem.contains("=")) {
                String column = partitionItem.trim();
                if (!column.isEmpty()) {
                    dynamicColumns.add(column);
                }
            }
        }
        return dynamicColumns;
    }

    private StatementLineageResult failedResult(
        StatementAnalysisContext context,
        String failureCode,
        String failureMessage
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        int index = context.getStatementIndex();
        return statementBuilder(request, index)
            .statementType("UNKNOWN")
            .rawSql(context.getRawStatement())
            .normalizedSql(context.getNormalizedStatement())
            .parseStatusCode(PARSE_STATUS_FAILED)
            .formalLineageProduced(false)
            .inputObjects(Collections.<DataObjectRef>emptyList())
            .parseMode(request.getOptions().getParseMode())
            .failureCode(failureCode)
            .failureMessage(failureMessage)
            .tableLineages(Collections.<TableLineage>emptyList())
            .columnLineages(Collections.<ColumnLineage>emptyList())
            .warnings(context.getWarnings())
            .build();
    }

    private StatementLineageResult.Builder statementBuilder(LineageAnalyzeRequest request, int index) {
        return StatementLineageResult.builder()
            .workflowId(request.getWorkflowId())
            .taskId(request.getTaskId())
            .taskVersion(request.getTaskVersion())
            .statementIndex(index)
            .statementId(statementId(request, index));
    }

    private String statementId(LineageAnalyzeRequest request, int index) {
        return request.getWorkflowId() + "_" + request.getTaskId() + "_" + request.getTaskVersion() + "_" + index;
    }

    static final class Dependencies {
        HiveAstParserAdapter parserAdapter;
        HiveStatementDatabaseBinder databaseBinder;
        HiveTargetColumnResolver targetColumnResolver;
        HiveProjectionLineageResolver projectionResolver;
        HiveLineageExpansionService expansionService;
        HiveColumnLineageBuilder columnLineageBuilder;
        HiveJoinConditionAnalyzer joinAnalyzer;
    }

    private static final class PositionalStarCheck {
        private String operation;
        private List<String> targetColumns;
        private boolean starSelect;
        private boolean topLevelSetOperation;
        private String analysisStatement;
        private List<DataObjectRef> inputObjects;
        private Map<String, String> topLevelSourceAliases;
    }

    private static final class SelectAnalysisWork {
        private ParsedHiveStatement parsedStatement;
        private String targetTable;
        private DataObjectRef targetObject;
        private List<DataObjectRef> originalInputObjects;
        private List<String> analysisSelectItems;
        private ColumnResolutionContext columnContext;
        private SetOperationExpansionResult setExpansion;
        private boolean topLevelSetOperation;
        private String analysisStatement;
        private AnalysisScope scope;
        private boolean positionalSingleDirectSourceStar;
        private List<String> expandedSelectItems;
        private List<String> textualSelectItems;
        private List<String> resolvedTargetColumns;
        private StatementLineageResult failure;
    }

    private static final class AnalysisScope {
        private Map<String, String> sourceAliases;
        private Map<String, String> topLevelSourceAliases;
        private Map<String, String> topLevelProjectionSourceAliases;
        private QualifiedOwnerResolver ownerResolver;
        private Map<String, Map<String, ColumnLineageProjection>> statementProjectionTables;
        private boolean throughTemporaryTable;
        private List<DataObjectRef> columnReferenceInputObjects;
        private List<DataObjectRef> inputObjects;
        private String defaultColumnSourceTable;
    }

}
