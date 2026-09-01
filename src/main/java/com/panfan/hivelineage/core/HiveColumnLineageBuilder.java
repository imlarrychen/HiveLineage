package com.panfan.hivelineage.core;

import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.WarningCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class HiveColumnLineageBuilder {
    private final HiveTargetColumnResolver targetColumnResolver;
    private final HiveProjectionLineageResolver projectionResolver;
    private final HiveLineageExpansionService expansionService;

    HiveColumnLineageBuilder(
        HiveTargetColumnResolver targetColumnResolver,
        HiveProjectionLineageResolver projectionResolver,
        HiveLineageExpansionService expansionService
    ) {
        this.targetColumnResolver = targetColumnResolver;
        this.projectionResolver = projectionResolver;
        this.expansionService = expansionService;
    }

    List<ColumnLineage> build(Input input) {
        List<ColumnLineage> columnLineages = new ArrayList<>();
        input.columnAliasSources = targetColumnResolver.filterColumnAliasSources(
            input.parsedStatement.getColumnAliasSources(),
            input.topLevelSourceAliases
        );
        input.columnOwners = targetColumnResolver.buildColumnOwners(
            input.context.getRequest(),
            input.inputObjects,
            input.context.getRequest().getOptions().getParseMode(),
            input.context.getWarnings(),
            input.context.getStatementIndex()
        );
        boolean setOperationQuery = projectionResolver.hasCompleteSetOperationProjection(
            input.parsedStatement,
            input.expandedSelectItems.size()
        );
        for (int index = 0; index < input.expandedSelectItems.size(); index++) {
            columnLineages.add(buildColumnLineage(input, setOperationQuery, index));
        }
        addStaticPartitionLineages(input, columnLineages);
        return columnLineages;
    }

    private ColumnLineage buildColumnLineage(Input input, boolean setOperationQuery, int index) {
        Expression expression = resolveColumnLineageExpression(input, setOperationQuery, index);
        ColumnLineageProjection directProjection = resolveDirectProjection(input, expression.value, setOperationQuery);
        List<ColumnRef> sourceColumns = resolveInitialSourceColumns(input, expression.value, setOperationQuery, index);
        if (directProjection != null) {
            sourceColumns = directProjection.getSourceColumns();
            if (directProjection.getLineageType() == LineageType.DERIVED) {
                expression.lineageExpression = directProjection.getExpression();
            }
        } else if (!setOperationQuery) {
            applyAliasExpression(input, expression);
        }
        sourceColumns = resolveFallbackSourceColumns(input, expression, directProjection, sourceColumns, index);
        warnWhenColumnLineageUnresolved(input, expression, directProjection, sourceColumns);
        LineageType lineageType = directProjection == null
            ? projectionResolver.resolveLineageType(
                expression.lineageExpression,
                expression.sourceExtractionExpression,
                sourceColumns
            )
            : directProjection.getLineageType();
        return ColumnLineage.builder()
            .targetColumn(new ColumnRef(input.targetTable + "." + input.resolvedTargetColumns.get(index)))
            .sourceColumns(sourceColumns)
            .sourceObjects(input.inputObjects)
            .expression(expression.lineageExpression)
            .expressionExtracted(true)
            .lineageType(lineageType)
            .functionNames(lineageType == LineageType.CONSTANT
                ? Collections.<String>emptyList()
                : projectionResolver.extractFunctionNames(expression.lineageExpression))
            .complexExpression(lineageType == LineageType.DERIVED)
            .statementIndex(input.context.getStatementIndex())
            .build();
    }

    private Expression resolveColumnLineageExpression(Input input, boolean setOperationQuery, int index) {
        String expression = HiveSqlTextUtils.stripAlias(input.expandedSelectItems.get(index));
        if (index < input.textualSelectItems.size() && projectionResolver.isScalarSubqueryExpression(expression)) {
            expression = HiveSqlTextUtils.stripAlias(input.textualSelectItems.get(index));
        }
        String lineageExpression = setOperationQuery
            ? projectionResolver.setOperationExpression(input.parsedStatement, input.analysisStatement, index)
            : projectionResolver.normalizeLineageExpression(expression);
        return new Expression(expression, lineageExpression);
    }

    private ColumnLineageProjection resolveDirectProjection(
        Input input,
        String expression,
        boolean setOperationQuery
    ) {
        if (setOperationQuery) {
            return null;
        }
        return expansionService.resolveDirectColumnProjection(
            input.context,
            expression,
            input.originalInputObjects,
            new HiveLineageExpansionService.DirectProjectionScope(
                input.sourceAliases,
                input.topLevelProjectionSourceAliases,
                input.statementProjectionTables,
                input.ownerResolver
            )
        );
    }

    private List<ColumnRef> resolveInitialSourceColumns(
        Input input,
        String expression,
        boolean setOperationQuery,
        int index
    ) {
        List<ColumnRef> sourceColumns = setOperationQuery
            ? projectionResolver.setOperationSourceColumns(input.parsedStatement, index)
            : Collections.<ColumnRef>emptyList();
        if (setOperationQuery && input.setExpansion.isUnresolvedPosition(index)) {
            return Collections.emptyList();
        }
        if (sourceColumns.isEmpty() && setOperationQuery) {
            return projectionResolver.extractSetOperationSourceColumns(
                input.context.getRequest(),
                expression,
                input.columnReferenceInputObjects,
                input.context.getWarnings(),
                input.context.getStatementIndex()
            );
        }
        return sourceColumns;
    }

    private void applyAliasExpression(Input input, Expression expression) {
        String aliasExpression = projectionResolver.directColumnAliasExpression(
            expression.value,
            input.parsedStatement.getColumnAliasExpressions()
        );
        if (aliasExpression != null) {
            expression.lineageExpression = aliasExpression;
            expression.sourceExtractionExpression = aliasExpression;
        }
    }

    private List<ColumnRef> resolveFallbackSourceColumns(
        Input input,
        Expression expression,
        ColumnLineageProjection directProjection,
        List<ColumnRef> sourceColumns,
        int index
    ) {
        List<ColumnRef> resolvedSourceColumns = sourceColumns;
        boolean unresolvedSetPosition = input.setExpansion.isUnresolvedPosition(index);
        if (shouldExtractSourceColumns(input, expression, directProjection, resolvedSourceColumns, unresolvedSetPosition)) {
            resolvedSourceColumns = projectionResolver.extractSourceColumns(
                expression.sourceExtractionExpression,
                input.defaultColumnSourceTable,
                input.sourceAliases,
                input.columnOwners,
                input.columnAliasSources,
                input.ownerResolver
            );
        }
        if (directProjection == null) {
            return expansionService.expandViewSourceColumns(
                input.context,
                resolvedSourceColumns,
                0,
                new LinkedHashSet<String>()
            );
        }
        return resolvedSourceColumns;
    }

    private boolean shouldExtractSourceColumns(
        Input input,
        Expression expression,
        ColumnLineageProjection directProjection,
        List<ColumnRef> sourceColumns,
        boolean unresolvedSetPosition
    ) {
        return canUseFallbackSourceExtraction(expression, directProjection, sourceColumns)
            && !unresolvedSetPosition
            && !projectionResolver.isConstantLineageExpression(expression.lineageExpression);
    }

    private boolean canUseFallbackSourceExtraction(
        Expression expression,
        ColumnLineageProjection directProjection,
        List<ColumnRef> sourceColumns
    ) {
        return sourceColumns.isEmpty()
            && directProjection == null
            && !expression.isUnresolvedStar();
    }

    private void warnWhenColumnLineageUnresolved(
        Input input,
        Expression expression,
        ColumnLineageProjection directProjection,
        List<ColumnRef> sourceColumns
    ) {
        boolean unresolvedDirectProjection = directProjection != null && directProjection.isUnresolved();
        if (unresolvedDirectProjection || shouldWarnUnknownColumnLineage(expression, directProjection, sourceColumns)) {
            targetColumnResolver.addWarning(
                input.context.getWarnings(),
                WarningCode.UNKNOWN_COLUMN_LINEAGE,
                "Unable to resolve source columns for expression: " + expression.sourceExtractionExpression,
                input.context.getStatementIndex()
            );
        }
    }

    private boolean shouldWarnUnknownColumnLineage(
        Expression expression,
        ColumnLineageProjection directProjection,
        List<ColumnRef> sourceColumns
    ) {
        return sourceColumns.isEmpty()
            && directProjection == null
            && !expression.isUnresolvedStar()
            && projectionResolver.mayContainColumnReference(expression.sourceExtractionExpression);
    }

    private void addStaticPartitionLineages(Input input, List<ColumnLineage> columnLineages) {
        for (Map.Entry<String, String> partitionValue : input.parsedStatement.getStaticPartitionValues().entrySet()) {
            columnLineages.add(ColumnLineage.builder()
                .targetColumn(new ColumnRef(input.targetTable + "." + partitionValue.getKey()))
                .sourceColumns(Collections.<ColumnRef>emptyList())
                .sourceObjects(input.inputObjects)
                .expression(projectionResolver.normalizeLineageExpression(partitionValue.getValue()))
                .expressionExtracted(true)
                .lineageType(LineageType.CONSTANT)
                .functionNames(Collections.<String>emptyList())
                .complexExpression(false)
                .statementIndex(input.context.getStatementIndex())
                .build());
        }
    }

    static final class Input {
        StatementAnalysisContext context;
        ParsedHiveStatement parsedStatement;
        String targetTable;
        List<String> resolvedTargetColumns;
        List<String> expandedSelectItems;
        List<String> textualSelectItems;
        SetOperationExpansionResult setExpansion;
        String analysisStatement;
        List<DataObjectRef> originalInputObjects;
        Map<String, String> sourceAliases;
        Map<String, String> topLevelSourceAliases;
        Map<String, String> topLevelProjectionSourceAliases;
        Map<String, String> columnAliasSources;
        Map<String, String> columnOwners;
        Map<String, Map<String, ColumnLineageProjection>> statementProjectionTables;
        QualifiedOwnerResolver ownerResolver;
        List<DataObjectRef> columnReferenceInputObjects;
        List<DataObjectRef> inputObjects;
        String defaultColumnSourceTable;

        static Input of(
            StatementAnalysisContext context,
            ParsedHiveStatement parsedStatement,
            Target target,
            SetOperation setOperation,
            Sources sources,
            InputObjects inputs
        ) {
            Input buildInput = new Input();
            buildInput.context = context;
            buildInput.parsedStatement = parsedStatement;
            buildInput.targetTable = target.targetTable;
            buildInput.resolvedTargetColumns = target.resolvedTargetColumns;
            buildInput.expandedSelectItems = target.expandedSelectItems;
            buildInput.textualSelectItems = target.textualSelectItems;
            buildInput.setExpansion = setOperation.setExpansion;
            buildInput.analysisStatement = setOperation.analysisStatement;
            buildInput.originalInputObjects = sources.originalInputObjects;
            buildInput.sourceAliases = sources.sourceAliases;
            buildInput.topLevelSourceAliases = sources.topLevelSourceAliases;
            buildInput.topLevelProjectionSourceAliases = sources.topLevelProjectionSourceAliases;
            buildInput.statementProjectionTables = sources.statementProjectionTables;
            buildInput.ownerResolver = sources.ownerResolver;
            buildInput.columnReferenceInputObjects = inputs.columnReferenceInputObjects;
            buildInput.inputObjects = inputs.inputObjects;
            buildInput.defaultColumnSourceTable = inputs.defaultColumnSourceTable;
            return buildInput;
        }
    }

    static final class Target {
        private final String targetTable;
        private final List<String> resolvedTargetColumns;
        private final List<String> expandedSelectItems;
        private final List<String> textualSelectItems;

        Target(
            String targetTable,
            List<String> resolvedTargetColumns,
            List<String> expandedSelectItems,
            List<String> textualSelectItems
        ) {
            this.targetTable = targetTable;
            this.resolvedTargetColumns = resolvedTargetColumns;
            this.expandedSelectItems = expandedSelectItems;
            this.textualSelectItems = textualSelectItems;
        }
    }

    static final class SetOperation {
        private final SetOperationExpansionResult setExpansion;
        private final String analysisStatement;

        SetOperation(SetOperationExpansionResult setExpansion, String analysisStatement) {
            this.setExpansion = setExpansion;
            this.analysisStatement = analysisStatement;
        }
    }

    static final class Sources {
        private final List<DataObjectRef> originalInputObjects;
        private final Map<String, String> sourceAliases;
        private final Map<String, String> topLevelSourceAliases;
        private final Map<String, String> topLevelProjectionSourceAliases;
        private final Map<String, Map<String, ColumnLineageProjection>> statementProjectionTables;
        private final QualifiedOwnerResolver ownerResolver;

        Sources(
            List<DataObjectRef> originalInputObjects,
            Map<String, String> sourceAliases,
            Map<String, String> topLevelSourceAliases,
            Map<String, String> topLevelProjectionSourceAliases,
            Map<String, Map<String, ColumnLineageProjection>> statementProjectionTables,
            QualifiedOwnerResolver ownerResolver
        ) {
            this.originalInputObjects = originalInputObjects;
            this.sourceAliases = sourceAliases;
            this.topLevelSourceAliases = topLevelSourceAliases;
            this.topLevelProjectionSourceAliases = topLevelProjectionSourceAliases;
            this.statementProjectionTables = statementProjectionTables;
            this.ownerResolver = ownerResolver;
        }
    }

    static final class InputObjects {
        private final List<DataObjectRef> columnReferenceInputObjects;
        private final List<DataObjectRef> inputObjects;
        private final String defaultColumnSourceTable;

        InputObjects(
            List<DataObjectRef> columnReferenceInputObjects,
            List<DataObjectRef> inputObjects,
            String defaultColumnSourceTable
        ) {
            this.columnReferenceInputObjects = columnReferenceInputObjects;
            this.inputObjects = inputObjects;
            this.defaultColumnSourceTable = defaultColumnSourceTable;
        }
    }

    private static final class Expression {
        private final String value;
        private String lineageExpression;
        private String sourceExtractionExpression;

        private Expression(String value, String lineageExpression) {
            this.value = value;
            this.lineageExpression = lineageExpression;
            this.sourceExtractionExpression = value;
        }

        private boolean isUnresolvedStar() {
            QualifiedColumnReference qualifiedReference = QualifiedColumnReference.parse(value);
            return qualifiedReference != null && qualifiedReference.isStar();
        }
    }
}
