package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.ColumnEndpoint;
import com.panfan.hivelineage.v2.analysis.Dependency;
import com.panfan.hivelineage.v2.analysis.DependencyRole;
import com.panfan.hivelineage.v2.api.SourceDocument;
import com.panfan.hivelineage.v2.api.StatementAnalysisResult;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Formal export is derived from typed sink contributions, without SQL parsing or metadata access. */
public final class DefaultFormalLineageRecordExporter implements FormalLineageRecordExporter {
    private final SourceRenderer renderer;
    private final boolean includeTemporary;

    public DefaultFormalLineageRecordExporter() {
        this(new DefaultSourceRenderer(Collections.<String, SourceDocument>emptyMap()), false);
    }

    public DefaultFormalLineageRecordExporter(SourceRenderer renderer, boolean includeTemporary) {
        this.renderer = java.util.Objects.requireNonNull(renderer, "renderer");
        this.includeTemporary = includeTemporary;
    }

    @Override
    public List<TableLineageRecord> exportTables(StatementAnalysisResult statement) {
        List<TableLineageRecord> records = new ArrayList<TableLineageRecord>();
        if (!statement.isFormalLineageProduced() || !statement.getBoundUnit().isPresent() || !statement.getLineage().isPresent()) {
            return Collections.emptyList();
        }
        BoundAnalysisUnit unit = statement.getBoundUnit().get();
        WriteDependencyIndex dependencies = new WriteDependencyIndex(statement.getLineage().get().getWriteDependencies());
        BoundNameIndex names = new BoundNameIndex(unit);
        BoundExpressionFormatter expressions = new BoundExpressionFormatter(unit, names, renderer);
        for (BoundWrite write : unit.getWrites()) {
            Optional<QualifiedName> target = names.declaredObject(write.getTargetObject());
            if (!target.isPresent()) {
                throw new IllegalArgumentException("Write target has no bound display declaration");
            }
            Map<QualifiedName, QualifiedName> sources = new LinkedHashMap<QualifiedName, QualifiedName>();
            for (Dependency dependency : dependencies.forWrite(write)) {
                if (dependency.getEvidence().getOperationId().equals(write.getId()) && include(dependency, names)) {
                    names.endpointName(dependency.getSource())
                            .ifPresent(name -> sources.putIfAbsent(name, name));
                }
            }
            records.add(new TableLineageRecord(statement.getIdentity(), target.get(), new ArrayList<QualifiedName>(sources.values()),
                    statement.getStatementType(), throughTemporary(dependencies, write, names)));
        }
        records.addAll(new DefinitionLineageRecordExporter(statement, names, expressions, includeTemporary).tables());
        return Collections.unmodifiableList(records);
    }

    @Override
    public List<ColumnLineageRecord> exportColumns(StatementAnalysisResult statement) {
        if (!statement.isFormalLineageProduced() || !statement.getBoundUnit().isPresent() || !statement.getLineage().isPresent()) {
            return Collections.emptyList();
        }
        BoundAnalysisUnit unit = statement.getBoundUnit().get();
        WriteDependencyIndex dependencies = new WriteDependencyIndex(statement.getLineage().get().getWriteDependencies());
        BoundNameIndex names = new BoundNameIndex(unit);
        BoundExpressionFormatter expressions = new BoundExpressionFormatter(unit, names, renderer);
        List<ColumnLineageRecord> records = new ArrayList<ColumnLineageRecord>();
        for (BoundWrite write : unit.getWrites()) {
            for (WriteAssignment assignment : write.getAssignments()) {
                records.add(column(statement, write, assignment, dependencies, names, expressions));
            }
        }
        records.addAll(new DefinitionLineageRecordExporter(statement, names, expressions, includeTemporary).columns());
        return Collections.unmodifiableList(records);
    }

    private ColumnLineageRecord column(StatementAnalysisResult statement, BoundWrite write, WriteAssignment assignment,
                                       WriteDependencyIndex dependencyIndex, BoundNameIndex names, BoundExpressionFormatter expressions) {
        QualifiedName targetObject = names.declaredObject(write.getTargetObject())
                .orElseThrow(() -> new IllegalArgumentException("Write target has no bound declaration"));
        ColumnAddress target = new ColumnAddress(targetObject, Collections.singletonList(assignment.getTargetField().getDisplayName()));
        Map<ColumnAddress, ColumnAddress> sources = new LinkedHashMap<ColumnAddress, ColumnAddress>();
        List<Dependency> dependencies = new ArrayList<Dependency>();
        for (Dependency dependency : dependencyIndex.forField(write, assignment.getTargetField())) {
            dependencies.add(dependency);
            if (dependency.getSource() instanceof ColumnEndpoint && dependency.getRole() != DependencyRole.ROWSET
                    && include(dependency, names)) {
                ColumnEndpoint field = (ColumnEndpoint) dependency.getSource();
                Optional<QualifiedName> object = names.declaredObject(field.getObjectId());
                Optional<String> name = names.declaredField(field.getObjectId(), field.getFieldId());
                if (object.isPresent() && name.isPresent()) {
                    List<String> path = new ArrayList<String>();
                    path.add(name.get());
                    path.addAll(field.getFieldPath());
                    ColumnAddress source = new ColumnAddress(object.get(), path);
                    sources.putIfAbsent(source, source);
                }
            }
        }
        ColumnDerivationKind kind = ColumnDerivationClassifier.classify(expressions, assignment.getExpression(), dependencies);
        Optional<String> expression = expressions.format(assignment.getExpression());
        return new ColumnLineageRecord(statement.getIdentity(), target, new ArrayList<ColumnAddress>(sources.values()), kind,
                new RenderedExpression(expression, expressions.extracted(assignment.getExpression())),
                expressions.functions(assignment.getExpression()),
                statement.getLineage().get().getQuality());
    }

    private boolean include(Dependency dependency, BoundNameIndex names) {
        return dependency.getRole() != DependencyRole.DEFINITION || includeTemporary || !names.temporary(dependency.getSource());
    }

    private boolean throughTemporary(WriteDependencyIndex dependencies, BoundWrite write, BoundNameIndex names) {
        for (Dependency dependency : dependencies.forWrite(write)) {
            if (dependency.getEvidence().getOperationId().equals(write.getId())
                    && dependency.getRole() == DependencyRole.DEFINITION
                    && names.endpointObject(dependency.getSource()).map(names::temporary).orElse(false)) {
                return true;
            }
        }
        return false;
    }
}
