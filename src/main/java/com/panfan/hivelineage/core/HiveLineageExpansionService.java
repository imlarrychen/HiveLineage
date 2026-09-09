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
import com.panfan.hivelineage.result.TableLineage;
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
    private final HivePhysicalNameResolver nameResolver;
    private final HiveStatementDatabaseBinder databaseBinder;

    HiveLineageExpansionService(
        HiveAstParserAdapter parserAdapter,
        HiveTargetColumnResolver targetColumnResolver,
        HiveProjectionLineageResolver projectionResolver,
        HivePhysicalNameResolver nameResolver,
        HiveStatementDatabaseBinder databaseBinder
    ) {
        this.parserAdapter = parserAdapter;
        this.targetColumnResolver = targetColumnResolver;
        this.projectionResolver = projectionResolver;
        this.nameResolver = nameResolver;
        this.databaseBinder = databaseBinder;
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

    void registerScriptCte(
        String cteName,
        List<DataObjectRef> sourceObjects,
        Map<String, ColumnLineageProjection> columnProjections,
        Map<String, List<DataObjectRef>> scriptCteSources,
        Map<String, Map<String, ColumnLineageProjection>> scriptCteColumns
    ) {
        String normalizedName = HiveSqlTextUtils.normalizeIdentifier(cteName);
        scriptCteSources.put(normalizedName, sourceObjects);
        scriptCteColumns.put(normalizedName, columnProjections);
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
        return expandInputObjects(inputObjects, temporaryTableSources);
    }

    List<DataObjectRef> expandInputObjects(
        List<DataObjectRef> inputObjects,
        Map<String, List<DataObjectRef>> objectSources
    ) {
        if (objectSources.isEmpty()) {
            return inputObjects;
        }
        List<DataObjectRef> expanded = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DataObjectRef inputObject : inputObjects) {
            List<DataObjectRef> sources = objectSources.get(inputObject.getQualifiedName());
            if (sources == null) {
                sources = objectSources.get(HiveTemporaryTableNamer.simpleName(inputObject.getQualifiedName()));
            }
            if (sources == null) {
                addInputObject(expanded, seen, inputObject);
                continue;
            }
            for (DataObjectRef source : sources) {
                addInputObject(expanded, seen, source);
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
            if (context.getTemporaryTableSources().containsKey(inputObject.getQualifiedName())) {
                addInputObject(expanded, seen, inputObject);
                continue;
            }
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
                viewSources = parseBoundView(context, metadata).getInputObjects();
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
    List<TableLineage> viewDefinitionTableLineages(
        StatementAnalysisContext context,
        List<DataObjectRef> inputObjects,
        int depth,
        Set<String> visitingViews
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        if (!request.getOptions().isExpandViewLineage()
            || request.getMetadataProvider() == null
            || depth >= request.getOptions().getMaxViewExpandDepth()) {
            return Collections.emptyList();
        }

        List<TableLineage> tableLineages = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        for (DataObjectRef inputObject : inputObjects) {
            appendViewDefinitionTableLineages(context, inputObject, depth, visitingViews, emitted, tableLineages);
        }
        return tableLineages;
    }

    private void appendViewDefinitionTableLineages(
        StatementAnalysisContext context,
        DataObjectRef viewObject,
        int depth,
        Set<String> visitingViews,
        Set<String> emitted,
        List<TableLineage> tableLineages
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        List<LineageWarning> warnings = context.getWarnings();
        int statementIndex = context.getStatementIndex();
        String viewName = viewObject.getQualifiedName();
        if (context.getTemporaryTableSources().containsKey(viewName) || depth >= request.getOptions().getMaxViewExpandDepth()) {
            return;
        }

        HiveTableMetadata metadata = targetColumnResolver.loadTableMetadata(request, viewName, warnings, statementIndex);
        if (metadata == null || !metadata.isView()) {
            return;
        }
        if (metadata.getViewOriginalSql() == null || metadata.getViewOriginalSql().trim().isEmpty()) {
            targetColumnResolver.addWarning(
                warnings,
                WarningCode.VIEW_METADATA_NOT_FOUND,
                "View definition SQL is missing for " + viewName,
                statementIndex
            );
            return;
        }
        if (!visitingViews.add(viewName)) {
            targetColumnResolver.addWarning(
                warnings,
                WarningCode.VIEW_EXPANSION_CYCLE,
                "View expansion cycle detected at " + viewName,
                statementIndex
            );
            return;
        }

        List<DataObjectRef> viewSources;
        try {
            viewSources = parseBoundView(context, metadata).getInputObjects();
        } catch (RuntimeException ex) {
            targetColumnResolver.addWarning(
                warnings,
                WarningCode.VIEW_EXPANSION_FAILED,
                "View expansion failed for " + viewName + ": " + ex.getMessage(),
                statementIndex
            );
            visitingViews.remove(viewName);
            return;
        }

        for (DataObjectRef viewSource : viewSources) {
            appendViewDefinitionTableLineages(context, viewSource, depth + 1, visitingViews, emitted, tableLineages);
        }
        String key = viewName + "<-" + qualifiedNames(viewSources);
        if (!viewSources.isEmpty() && emitted.add(key)) {
            tableLineages.add(new TableLineage(
                new DataObjectRef(viewName),
                viewSources,
                "VIEW_DEFINITION",
                statementIndex,
                false
            ));
        }
        visitingViews.remove(viewName);
    }

    private String qualifiedNames(List<DataObjectRef> objects) {
        StringBuilder builder = new StringBuilder();
        for (DataObjectRef object : objects) {
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(object.getQualifiedName());
        }
        return builder.toString();
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
            parsedView = parseBoundView(context, viewMetadata);
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
    private ParsedHiveStatement parseBoundView(
        StatementAnalysisContext context,
        HiveTableMetadata viewMetadata
    ) {
        ParsedHiveStatement parsed = parserAdapter.parse(viewMetadata.getViewOriginalSql());
        String viewDatabase = viewMetadata.getTableIdentifier().getDatabaseName();
        return databaseBinder.bind(
            parsed,
            viewDatabase,
            context.getTemporaryTableSources().keySet(),
            context.getTemporaryTableSources().keySet(),
            false,
            HiveTemporaryTableNamer.temporaryDatabase(context.getRequest())
        );
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
        TableIdentifier tableIdentifier = nameResolver.toTableIdentifier(tableName, request.getClusterName());
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

    List<ColumnRef> expandTemporarySourceColumns(
        StatementAnalysisContext context,
        List<ColumnRef> sourceColumns
    ) {
        return expandProjectedSourceColumns(
            context.getTemporaryTableColumns(),
            sourceColumns,
            context.getRequest().getOptions().isIncludeTemporaryLineage()
        );
    }

    List<ColumnRef> expandScriptCteSourceColumns(
        StatementAnalysisContext context,
        List<ColumnRef> sourceColumns
    ) {
        return expandProjectedSourceColumns(context.getScriptCteColumns(), sourceColumns, false);
    }

    private List<ColumnRef> expandProjectedSourceColumns(
        Map<String, Map<String, ColumnLineageProjection>> projectionsByTable,
        List<ColumnRef> sourceColumns,
        boolean keepBoundaryColumn
    ) {
        if (sourceColumns.isEmpty() || projectionsByTable.isEmpty()) {
            return sourceColumns;
        }
        List<ColumnRef> expanded = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ColumnRef sourceColumn : sourceColumns) {
            ColumnLineageProjection projection = projectedColumn(sourceColumn, projectionsByTable);
            if (projection == null || keepBoundaryColumn) {
                addColumn(expanded, seen, sourceColumn);
                continue;
            }
            for (ColumnRef projectedColumn : projection.getSourceColumns()) {
                addColumn(expanded, seen, projectedColumn);
            }
        }
        return expanded;
    }

    private ColumnLineageProjection projectedColumn(
        ColumnRef sourceColumn,
        Map<String, Map<String, ColumnLineageProjection>> projectionsByTable
    ) {
        String tableName = HiveSqlTextUtils.tableNameOfColumn(sourceColumn.getQualifiedName());
        String columnName = HiveSqlTextUtils.columnNameOf(sourceColumn.getQualifiedName());
        if (tableName == null || columnName == null) {
            return null;
        }
        ColumnLineageProjection projection = projectionResolver.columnProjection(tableName, columnName, projectionsByTable);
        return projection == null
            ? projectionResolver.columnProjection(HiveTemporaryTableNamer.simpleName(tableName), columnName, projectionsByTable)
            : projection;
    }

    List<ColumnLineage> prependViewDefinitionColumnLineages(
        StatementAnalysisContext context,
        List<DataObjectRef> inputObjects,
        List<ColumnLineage> downstreamColumnLineages
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        if (!request.getOptions().isExpandViewLineage()
            || request.getMetadataProvider() == null
            || downstreamColumnLineages.isEmpty()) {
            return downstreamColumnLineages;
        }

        List<ColumnLineage> viewDefinitionLineages = new ArrayList<>();
        Set<String> emittedTargets = new LinkedHashSet<>();
        for (DataObjectRef inputObject : inputObjects) {
            appendReferencedViewDefinitionColumnLineages(
                context,
                inputObject,
                downstreamColumnLineages,
                emittedTargets,
                viewDefinitionLineages
            );
        }
        if (viewDefinitionLineages.isEmpty()) {
            return downstreamColumnLineages;
        }
        List<ColumnLineage> combined = new ArrayList<>(viewDefinitionLineages);
        combined.addAll(downstreamColumnLineages);
        return combined;
    }

    private void appendReferencedViewDefinitionColumnLineages(
        StatementAnalysisContext context,
        DataObjectRef inputObject,
        List<ColumnLineage> downstreamColumnLineages,
        Set<String> emittedTargets,
        List<ColumnLineage> viewDefinitionLineages
    ) {
        String viewName = inputObject.getQualifiedName();
        if (context.getTemporaryTableSources().containsKey(viewName)) {
            return;
        }
        HiveTableMetadata metadata = targetColumnResolver.loadTableMetadata(
            context.getRequest(),
            viewName,
            context.getWarnings(),
            context.getStatementIndex()
        );
        if (!hasUsableViewDefinition(metadata)) {
            return;
        }

        Map<String, ColumnLineageProjection> mapping = viewColumnMapping(
            context,
            metadata,
            0,
            new LinkedHashSet<String>()
        );
        for (String referencedColumn : referencedColumnsForView(viewName, downstreamColumnLineages)) {
            ColumnLineageProjection projection = mapping.get(referencedColumn.toLowerCase(Locale.ROOT));
            if (projection == null || projection.isUnresolved()) {
                continue;
            }
            String targetColumn = viewName + "." + referencedColumn;
            if (!emittedTargets.add(targetColumn)) {
                continue;
            }
            viewDefinitionLineages.add(ColumnLineage.builder()
                .targetColumn(new ColumnRef(targetColumn))
                .sourceColumns(projection.getSourceColumns())
                .sourceObjects(sourceObjectsForColumns(projection.getSourceColumns()))
                .expression(projection.getExpression())
                .expressionExtracted(true)
                .lineageType(projection.getLineageType())
                .functionNames(projection.getLineageType() == LineageType.CONSTANT
                    ? Collections.<String>emptyList()
                    : projectionResolver.extractFunctionNames(projection.getExpression()))
                .complexExpression(projection.getLineageType() == LineageType.DERIVED)
                .statementIndex(context.getStatementIndex())
                .build());
        }
    }

    private Set<String> referencedColumnsForView(String viewName, List<ColumnLineage> downstreamColumnLineages) {
        Set<String> referencedColumns = new LinkedHashSet<>();
        for (ColumnLineage columnLineage : downstreamColumnLineages) {
            for (ColumnRef sourceColumn : columnLineage.getSourceColumns()) {
                String tableName = HiveSqlTextUtils.tableNameOfColumn(sourceColumn.getQualifiedName());
                String columnName = HiveSqlTextUtils.columnNameOf(sourceColumn.getQualifiedName());
                if (columnName != null && viewName.equals(tableName)) {
                    referencedColumns.add(columnName);
                }
            }
        }
        return referencedColumns;
    }

    private List<DataObjectRef> sourceObjectsForColumns(List<ColumnRef> sourceColumns) {
        List<DataObjectRef> sourceObjects = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ColumnRef sourceColumn : sourceColumns) {
            String tableName = HiveSqlTextUtils.tableNameOfColumn(sourceColumn.getQualifiedName());
            if (tableName != null && seen.add(tableName)) {
                sourceObjects.add(new DataObjectRef(tableName));
            }
        }
        return sourceObjects;
    }

    static final class DirectProjectionScope {
        private final Map<String, String> sourceAliases;
        private final Map<String, String> topLevelProjectionSourceAliases;
        private final Map<String, Map<String, ColumnLineageProjection>> statementProjectionTables;
        private final QualifiedOwnerResolver ownerResolver;

        DirectProjectionScope(
            Map<String, String> sourceAliases,
            Map<String, String> topLevelProjectionSourceAliases,
            Map<String, Map<String, ColumnLineageProjection>> statementProjectionTables,
            QualifiedOwnerResolver ownerResolver
        ) {
            this.sourceAliases = sourceAliases;
            this.topLevelProjectionSourceAliases = topLevelProjectionSourceAliases;
            this.statementProjectionTables = statementProjectionTables;
            this.ownerResolver = ownerResolver;
        }
    }

    ColumnLineageProjection resolveDirectColumnProjection(
        StatementAnalysisContext context,
        String expression,
        List<DataObjectRef> originalInputObjects,
        DirectProjectionScope scope
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
                scope.statementProjectionTables
            );
            if (ownerProjection != null) {
                return ownerProjection;
            }
            String aliasedProjectionTable = scope.sourceAliases.get(directColumn.getOwner());
            ColumnLineageProjection aliasedProjection = projectionResolver.columnProjection(
                aliasedProjectionTable == null ? "" : aliasedProjectionTable,
                directColumn.getColumn(),
                scope.statementProjectionTables
            );
            if (aliasedProjection != null) {
                return aliasedProjection;
            }
            tableName = scope.ownerResolver.resolve(directColumn.getOwner());
        } else {
            tableName = projectionResolver.singleProjectionTable(
                scope.topLevelProjectionSourceAliases,
                scope.statementProjectionTables
            );
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
            scope.statementProjectionTables
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
            if (context.getRequest().getOptions().isIncludeTemporaryLineage()) {
                return temporaryColumnProjection(tableName, directColumn.getColumn());
            }
            return temporaryProjection;
        }
        return projectionResolver.columnProjection(
            tableName,
            directColumn.getColumn(),
            context.getScriptCteColumns()
        );
    }

    private ColumnLineageProjection temporaryColumnProjection(String tableName, String columnName) {
        return new ColumnLineageProjection(
            Collections.singletonList(new ColumnRef(tableName + "." + columnName)),
            columnName,
            LineageType.DIRECT
        );
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
