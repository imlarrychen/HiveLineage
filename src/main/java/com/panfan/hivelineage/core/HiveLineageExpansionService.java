package com.panfan.hivelineage.core;

import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.metadata.TableIdentifier;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.LineageWarning;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.WarningCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class HiveLineageExpansionService {
    private final HiveAstParserAdapter parserAdapter;
    private final HiveTargetColumnResolver targetColumnResolver;
    private final HiveProjectionLineageResolver projectionResolver;

    HiveLineageExpansionService(
        HiveAstParserAdapter parserAdapter,
        HiveTargetColumnResolver targetColumnResolver,
        HiveProjectionLineageResolver projectionResolver
    ) {
        this.parserAdapter = parserAdapter;
        this.targetColumnResolver = targetColumnResolver;
        this.projectionResolver = projectionResolver;
    }

    void registerTemporaryTable(
        StatementLineageResult temporaryStatement,
        Map<String, List<DataObjectRef>> temporaryTableSources,
        Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns
    ) {
        String temporaryTableName = temporaryStatement.getOutputObject().getQualifiedName();
        temporaryTableSources.put(temporaryTableName, temporaryStatement.getInputObjects());
        temporaryTableColumns.put(temporaryTableName, columnProjectionMap(temporaryStatement.getColumnLineages()));
        if (temporaryTableName.contains(".")) {
            String simpleName = temporaryTableName.substring(temporaryTableName.lastIndexOf('.') + 1);
            temporaryTableSources.put(simpleName, temporaryStatement.getInputObjects());
            temporaryTableColumns.put(simpleName, columnProjectionMap(temporaryStatement.getColumnLineages()));
        }
    }
    Map<String, ColumnLineageProjection> columnProjectionMap(List<ColumnLineage> columnLineages) {
        Map<String, ColumnLineageProjection> projections = new HashMap<>();
        for (ColumnLineage columnLineage : columnLineages) {
            String columnName = HiveSqlTextUtils.columnNameOf(columnLineage.getTargetColumn().getQualifiedName());
            if (columnName == null) {
                continue;
            }
            projections.put(
                columnName.toLowerCase(Locale.ROOT),
                new ColumnLineageProjection(
                    columnLineage.getSourceColumns(),
                    columnLineage.getExpression(),
                    columnLineage.getLineageType()
                )
            );
        }
        return projections;
    }
    boolean containsTemporaryTable(
        List<DataObjectRef> inputObjects,
        Map<String, List<DataObjectRef>> temporaryTableSources
    ) {
        for (DataObjectRef inputObject : inputObjects) {
            if (temporaryTableSources.containsKey(inputObject.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }
    List<DataObjectRef> expandTemporaryInputObjects(
        List<DataObjectRef> inputObjects,
        Map<String, List<DataObjectRef>> temporaryTableSources
    ) {
        List<DataObjectRef> expanded = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DataObjectRef inputObject : inputObjects) {
            List<DataObjectRef> temporarySources = temporaryTableSources.get(inputObject.getQualifiedName());
            if (temporarySources == null) {
                addInputObject(expanded, seen, inputObject);
                continue;
            }
            for (DataObjectRef temporarySource : temporarySources) {
                addInputObject(expanded, seen, temporarySource);
            }
        }
        return expanded;
    }
    List<DataObjectRef> filterProjectionInputObjects(
        List<DataObjectRef> inputObjects,
        Map<String, Map<String, ColumnLineageProjection>> statementProjectionTables
    ) {
        if (statementProjectionTables.isEmpty()) {
            return inputObjects;
        }
        List<DataObjectRef> filtered = new ArrayList<>();
        for (DataObjectRef inputObject : inputObjects) {
            if (!isStatementProjectionObject(inputObject.getQualifiedName(), statementProjectionTables)) {
                filtered.add(inputObject);
            }
        }
        return filtered.isEmpty() ? inputObjects : filtered;
    }
    boolean isStatementProjectionObject(
        String tableName,
        Map<String, Map<String, ColumnLineageProjection>> statementProjectionTables
    ) {
        String normalized = tableName.toLowerCase(Locale.ROOT);
        if (statementProjectionTables.containsKey(normalized)) {
            return true;
        }
        int dotIndex = normalized.lastIndexOf('.');
        return dotIndex >= 0 && statementProjectionTables.containsKey(normalized.substring(dotIndex + 1));
    }
    List<DataObjectRef> expandViewInputObjects(
        StatementAnalysisContext context,
        List<DataObjectRef> inputObjects,
        int depth,
        Set<String> visitingViews
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        List<LineageWarning> warnings = context.getWarnings();
        int statementIndex = context.getStatementIndex();
        if (!request.getOptions().isExpandViewLineage()
            || request.getMetadataProvider() == null) {
            return inputObjects;
        }
        if (depth >= request.getOptions().getMaxViewExpandDepth()) {
            warnForBoundaryViews(request, inputObjects, warnings, statementIndex);
            return inputObjects;
        }

        List<DataObjectRef> expanded = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DataObjectRef inputObject : inputObjects) {
            HiveTableMetadata metadata = targetColumnResolver.loadTableMetadata(
                request,
                inputObject.getQualifiedName(),
                warnings,
                statementIndex
            );
            if (metadata == null || !metadata.isView()) {
                addInputObject(expanded, seen, inputObject);
                continue;
            }
            if (metadata.getViewOriginalSql() == null || metadata.getViewOriginalSql().trim().isEmpty()) {
                targetColumnResolver.addWarning(
                    warnings,
                    WarningCode.VIEW_METADATA_NOT_FOUND,
                    "View definition SQL is missing for " + inputObject.getQualifiedName(),
                    statementIndex
                );
                addInputObject(expanded, seen, inputObject);
                continue;
            }

            String viewName = inputObject.getQualifiedName();
            if (!visitingViews.add(viewName)) {
                targetColumnResolver.addWarning(
                    warnings,
                    WarningCode.VIEW_EXPANSION_CYCLE,
                    "View expansion cycle detected at " + viewName,
                    statementIndex
                );
                addInputObject(expanded, seen, inputObject);
                continue;
            }
            List<DataObjectRef> viewSources;
            try {
                viewSources = parserAdapter.parse(metadata.getViewOriginalSql()).getInputObjects();
            } catch (RuntimeException ex) {
                targetColumnResolver.addWarning(
                    warnings,
                    WarningCode.VIEW_EXPANSION_FAILED,
                    "View expansion failed for " + viewName + ": " + ex.getMessage(),
                    statementIndex
                );
                addInputObject(expanded, seen, inputObject);
                visitingViews.remove(viewName);
                continue;
            }
            viewSources = expandViewInputObjects(context, viewSources, depth + 1, visitingViews);
            for (DataObjectRef viewSource : viewSources) {
                addInputObject(expanded, seen, viewSource);
            }
            visitingViews.remove(viewName);
        }
        return expanded;
    }
    List<ColumnRef> expandViewSourceColumns(
        StatementAnalysisContext context,
        List<ColumnRef> sourceColumns,
        int depth,
        Set<String> visitingViews
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        List<LineageWarning> warnings = context.getWarnings();
        int statementIndex = context.getStatementIndex();
        if (!request.getOptions().isExpandViewLineage()
            || request.getMetadataProvider() == null) {
            return sourceColumns;
        }
        if (depth >= request.getOptions().getMaxViewExpandDepth()) {
            warnForBoundaryViewColumns(request, sourceColumns, warnings, statementIndex);
            return sourceColumns;
        }

        List<ColumnRef> expanded = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ColumnRef sourceColumn : sourceColumns) {
            String tableName = HiveSqlTextUtils.tableNameOfColumn(sourceColumn.getQualifiedName());
            String columnName = HiveSqlTextUtils.columnNameOf(sourceColumn.getQualifiedName());
            if (tableName == null || columnName == null) {
                addColumn(expanded, seen, sourceColumn);
                continue;
            }

            HiveTableMetadata metadata = targetColumnResolver.loadTableMetadata(
                request,
                tableName,
                warnings,
                statementIndex
            );
            if (metadata == null || !metadata.isView()) {
                addColumn(expanded, seen, sourceColumn);
                continue;
            }
            if (metadata.getViewOriginalSql() == null || metadata.getViewOriginalSql().trim().isEmpty()) {
                targetColumnResolver.addWarning(
                    warnings,
                    WarningCode.VIEW_METADATA_NOT_FOUND,
                    "View definition SQL is missing for " + tableName,
                    statementIndex
                );
                addColumn(expanded, seen, sourceColumn);
                continue;
            }
            if (!visitingViews.add(tableName)) {
                targetColumnResolver.addWarning(
                    warnings,
                    WarningCode.VIEW_EXPANSION_CYCLE,
                    "View expansion cycle detected at " + tableName,
                    statementIndex
                );
                addColumn(expanded, seen, sourceColumn);
                continue;
            }

            Map<String, ColumnLineageProjection> mapping = viewColumnMapping(
                context,
                metadata,
                depth,
                visitingViews
            );
            ColumnLineageProjection mappedProjection = mapping.get(columnName.toLowerCase(Locale.ROOT));
            if (mappedProjection == null) {
                targetColumnResolver.addWarning(
                    warnings,
                    WarningCode.UNKNOWN_COLUMN_LINEAGE,
                    "Unable to map view column " + sourceColumn.getQualifiedName(),
                    statementIndex
                );
                addColumn(expanded, seen, sourceColumn);
            } else if (mappedProjection.isUnresolved()) {
                targetColumnResolver.addWarning(
                    warnings,
                    WarningCode.UNKNOWN_COLUMN_LINEAGE,
                    "Unable to resolve view column " + sourceColumn.getQualifiedName(),
                    statementIndex
                );
            } else {
                List<ColumnRef> recursivelyExpanded = expandViewSourceColumns(
                    context,
                    mappedProjection.getSourceColumns(),
                    depth + 1,
                    visitingViews
                );
                for (ColumnRef mappedColumn : recursivelyExpanded) {
                    addColumn(expanded, seen, mappedColumn);
                }
            }
            visitingViews.remove(tableName);
        }
        return expanded;
    }
    Map<String, ColumnLineageProjection> viewColumnMapping(
        StatementAnalysisContext context,
        HiveTableMetadata viewMetadata,
        int depth,
        Set<String> visitingViews
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache =
            context.getViewColumnLineageCache();
        List<LineageWarning> warnings = context.getWarnings();
        int statementIndex = context.getStatementIndex();
        String viewName = viewMetadata.getTableIdentifier().getQualifiedName();
        Map<String, ColumnLineageProjection> cached = viewColumnLineageCache.get(viewName);
        if (cached != null) {
            return cached;
        }

        ParsedHiveStatement parsedView;
        try {
            parsedView = parserAdapter.parse(viewMetadata.getViewOriginalSql());
        } catch (RuntimeException ex) {
            targetColumnResolver.addWarning(
                warnings,
                WarningCode.VIEW_EXPANSION_FAILED,
                "View expansion failed for " + viewName + ": " + ex.getMessage(),
                statementIndex
            );
            return Collections.emptyMap();
        }
        List<String> targetColumns = parsedView.getTargetColumns();
        if (targetColumns.isEmpty() && viewMetadata.getColumns().size() >= parsedView.getSelectExpressions().size()) {
            targetColumns = viewMetadata.getColumns().subList(0, parsedView.getSelectExpressions().size());
        }

        List<DataObjectRef> viewInputs = expandViewInputObjects(
            context,
            parsedView.getInputObjects(),
            depth + 1,
            visitingViews
        );
        String defaultSourceTable = parsedView.getInputObjects().isEmpty()
            ? viewName
            : parsedView.getInputObjects().get(0).getQualifiedName();
        Map<String, String> sourceAliases = new HashMap<>(parsedView.getSourceAliases());
        QualifiedOwnerResolver ownerResolver = new QualifiedOwnerResolver(
            parsedView.getInputObjects(),
            targetColumnResolver.extractExplicitSourceAliases(parsedView.getNormalizedSql())
        );
        Map<String, String> columnOwners = targetColumnResolver.buildColumnOwners(
            request,
            viewInputs,
            request.getOptions().getParseMode(),
            warnings,
            statementIndex
        );

        Map<String, ColumnLineageProjection> mapping = new HashMap<>();
        int count = Math.min(targetColumns.size(), parsedView.getSelectExpressions().size());
        for (int i = 0; i < count; i++) {
            String expression = HiveSqlTextUtils.stripAlias(parsedView.getSelectExpressions().get(i));
            String lineageExpression = projectionResolver.normalizeLineageExpression(expression);
            List<ColumnRef> sourceColumns = projectionResolver.extractSourceColumns(
                expression,
                defaultSourceTable,
                sourceAliases,
                columnOwners,
                parsedView.getColumnAliasSources(),
                ownerResolver
            );
            if (sourceColumns.isEmpty() && projectionResolver.mayContainColumnReference(expression)) {
                targetColumnResolver.addWarning(
                    warnings,
                    WarningCode.UNKNOWN_COLUMN_LINEAGE,
                    "Unable to resolve source columns for view expression: " + expression,
                    statementIndex
                );
            }
            sourceColumns = expandViewSourceColumns(context, sourceColumns, depth + 1, visitingViews);
            LineageType lineageType = projectionResolver.resolveLineageType(lineageExpression, expression, sourceColumns);
            boolean unresolved = sourceColumns.isEmpty()
                && projectionResolver.mayContainColumnReference(expression)
                && !projectionResolver.isConstantLineageExpression(expression);
            mapping.put(
                targetColumns.get(i).toLowerCase(Locale.ROOT),
                new ColumnLineageProjection(sourceColumns, lineageExpression, lineageType, unresolved)
            );
        }
        viewColumnLineageCache.put(viewName, mapping);
        return mapping;
    }
    void warnForBoundaryViews(
        LineageAnalyzeRequest request,
        List<DataObjectRef> inputObjects,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        Set<String> seen = new LinkedHashSet<>();
        for (DataObjectRef inputObject : inputObjects) {
            String tableName = inputObject.getQualifiedName();
            if (seen.add(tableName) && isKnownView(request, tableName)) {
                targetColumnResolver.addWarning(
                    warnings,
                    WarningCode.VIEW_EXPANSION_DEPTH_EXCEEDED,
                    "View expansion depth exceeded at " + tableName,
                    statementIndex
                );
            }
        }
    }
    void warnForBoundaryViewColumns(
        LineageAnalyzeRequest request,
        List<ColumnRef> sourceColumns,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        Set<String> seen = new LinkedHashSet<>();
        for (ColumnRef sourceColumn : sourceColumns) {
            String tableName = HiveSqlTextUtils.tableNameOfColumn(sourceColumn.getQualifiedName());
            if (tableName != null && seen.add(tableName) && isKnownView(request, tableName)) {
                targetColumnResolver.addWarning(
                    warnings,
                    WarningCode.VIEW_EXPANSION_DEPTH_EXCEEDED,
                    "View expansion depth exceeded at " + tableName,
                    statementIndex
                );
            }
        }
    }
    boolean isKnownView(LineageAnalyzeRequest request, String tableName) {
        if (request.getMetadataProvider() == null) {
            return false;
        }
        TableIdentifier tableIdentifier = TableIdentifier.parse(tableName, request.getClusterName(), request.getDefaultDatabase());
        HiveTableMetadata metadata = request.getMetadataCache().getTableMetadata(tableIdentifier, request.getMetadataProvider());
        return metadata != null && metadata.isView();
    }
    void addInputObject(List<DataObjectRef> inputObjects, Set<String> seen, DataObjectRef inputObject) {
        if (seen.add(inputObject.getQualifiedName())) {
            inputObjects.add(inputObject);
        }
    }
    void addColumn(List<ColumnRef> columns, Set<String> seen, ColumnRef column) {
        if (seen.add(column.getQualifiedName())) {
            columns.add(column);
        }
    }
    ColumnLineageProjection resolveDirectColumnProjection(
        StatementAnalysisContext context,
        String expression,
        List<DataObjectRef> originalInputObjects,
        Map<String, String> sourceAliases,
        Map<String, String> topLevelProjectionSourceAliases,
        Map<String, Map<String, ColumnLineageProjection>> statementProjectionTables,
        QualifiedOwnerResolver ownerResolver
    ) {
        DirectColumn directColumn = projectionResolver.parseDirectColumn(expression);
        if (directColumn == null) {
            return null;
        }
        String tableName = null;
        if (directColumn.getOwner() != null) {
            ColumnLineageProjection ownerProjection = projectionResolver.columnProjection(
                directColumn.getOwner(),
                directColumn.getColumn(),
                statementProjectionTables
            );
            if (ownerProjection != null) {
                return ownerProjection;
            }
            String aliasedProjectionTable = sourceAliases.get(directColumn.getOwner());
            ColumnLineageProjection aliasedProjection = projectionResolver.columnProjection(
                aliasedProjectionTable == null ? "" : aliasedProjectionTable,
                directColumn.getColumn(),
                statementProjectionTables
            );
            if (aliasedProjection != null) {
                return aliasedProjection;
            }
            tableName = ownerResolver.resolve(directColumn.getOwner());
        } else {
            tableName = projectionResolver.singleProjectionTable(topLevelProjectionSourceAliases, statementProjectionTables);
            if (tableName == null && originalInputObjects.size() == 1) {
                tableName = originalInputObjects.get(0).getQualifiedName();
            }
        }
        if (tableName == null) {
            return null;
        }
        ColumnLineageProjection statementProjection = projectionResolver.columnProjection(
            tableName,
            directColumn.getColumn(),
            statementProjectionTables
        );
        if (statementProjection != null) {
            return statementProjection;
        }
        ColumnLineageProjection temporaryProjection = projectionResolver.columnProjection(
            tableName,
            directColumn.getColumn(),
            context.getTemporaryTableColumns()
        );
        if (temporaryProjection != null) {
            return temporaryProjection;
        }
        return viewColumnProjection(context, tableName, directColumn.getColumn());
    }
    ColumnLineageProjection viewColumnProjection(
        StatementAnalysisContext context,
        String tableName,
        String columnName
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        if (!request.getOptions().isExpandViewLineage() || request.getMetadataProvider() == null) {
            return null;
        }
        HiveTableMetadata metadata = targetColumnResolver.loadTableMetadata(
            request,
            tableName,
            context.getWarnings(),
            context.getStatementIndex()
        );
        if (!hasUsableViewDefinition(metadata)) {
            return null;
        }
        Map<String, ColumnLineageProjection> mapping = viewColumnMapping(
            context,
            metadata,
            0,
            new LinkedHashSet<String>()
        );
        return mapping.get(columnName.toLowerCase(Locale.ROOT));
    }

    private boolean hasUsableViewDefinition(HiveTableMetadata metadata) {
        return metadata != null
            && metadata.isView()
            && metadata.getViewOriginalSql() != null
            && !metadata.getViewOriginalSql().trim().isEmpty();
    }
}
