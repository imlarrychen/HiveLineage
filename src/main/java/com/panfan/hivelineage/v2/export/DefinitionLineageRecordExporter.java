package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.ColumnEndpoint;
import com.panfan.hivelineage.v2.analysis.Dependency;
import com.panfan.hivelineage.v2.analysis.DependencyRole;
import com.panfan.hivelineage.v2.analysis.ExpressionFacts;
import com.panfan.hivelineage.v2.analysis.RelationFacts;
import com.panfan.hivelineage.v2.api.StatementAnalysisResult;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Publishes already analyzed definition inputs; it does not compute new dependencies.
 */
final class DefinitionLineageRecordExporter {
    private final StatementAnalysisResult statement;
    private final BoundNameIndex names;
    private final BoundExpressionFormatter expressions;
    private final boolean includeTemporary;
    private final Map<NodeId, RelationFacts> facts = new LinkedHashMap<NodeId, RelationFacts>();

    DefinitionLineageRecordExporter(StatementAnalysisResult statement, BoundNameIndex names,
                                    BoundExpressionFormatter expressions, boolean includeTemporary) {
        this.statement = statement;
        this.names = names;
        this.expressions = expressions;
        this.includeTemporary = includeTemporary;
        for (RelationFacts relation : statement.getLineage().get().getRelations()) {
            facts.put(relation.getRelationId(), relation);
        }
    }

    List<TableLineageRecord> tables() {
        List<TableLineageRecord> records = new ArrayList<TableLineageRecord>();
        Set<ObjectId> seen = new LinkedHashSet<ObjectId>();
        for (BoundRelation relation : statement.getBoundUnit().get().getRelations()) {
            if (!(relation instanceof Relations.DefinitionInstance)) {
                continue;
            }
            Relations.DefinitionInstance definition = (Relations.DefinitionInstance) relation;
            if (!included(definition) || !seen.add(definition.getPhysicalObject().get())) {
                continue;
            }
            RelationFacts source = facts.get(definition.getInput());
            if (source != null) {
                ObjectId target = definition.getPhysicalObject().get();
                records.add(new TableLineageRecord(statement.getIdentity(), names.declaredObject(target).get(),
                        objects(source.getRowSetFacts()), operation(target), names.temporary(target)));
            }
        }
        return records;
    }

    List<ColumnLineageRecord> columns() {
        List<ColumnLineageRecord> records = new ArrayList<ColumnLineageRecord>();
        Set<ColumnAddress> seen = new LinkedHashSet<ColumnAddress>();
        for (BoundRelation relation : statement.getBoundUnit().get().getRelations()) {
            if (!(relation instanceof Relations.DefinitionInstance)) {
                continue;
            }
            Relations.DefinitionInstance definition = (Relations.DefinitionInstance) relation;
            if (!included(definition)) {
                continue;
            }
            RelationFacts source = facts.get(definition.getInput());
            if (source == null) {
                continue;
            }
            for (SlotMapping mapping : definition.getMappings()) {
                Optional<OutputSlot> output = names.ir().slot(mapping.getOutput());
                ExpressionFacts dependencies = source.getOutputFacts().get(mapping.getInput());
                if (!output.isPresent() || dependencies == null) {
                    continue;
                }
                QualifiedName object = names.declaredObject(definition.getPhysicalObject().get()).get();
                ColumnAddress target = new ColumnAddress(object, Collections.singletonList(output.get().getDisplayName()));
                if (seen.add(target)) {
                    records.add(column(definition, mapping, target, dependencies));
                }
            }
        }
        return records;
    }

    private ColumnLineageRecord column(Relations.DefinitionInstance definition, SlotMapping mapping,
                                       ColumnAddress target, ExpressionFacts facts) {
        Map<ColumnAddress, ColumnAddress> sources = new LinkedHashMap<ColumnAddress, ColumnAddress>();
        for (Dependency dependency : facts.getDependencies()) {
            if (dependency.getSource() instanceof ColumnEndpoint && dependency.getRole() != DependencyRole.ROWSET
                    && dependency.getRole() != DependencyRole.DEFINITION) {
                ColumnEndpoint endpoint = (ColumnEndpoint) dependency.getSource();
                Optional<QualifiedName> object = names.declaredObject(endpoint.getObjectId());
                Optional<String> field = names.declaredField(endpoint.getObjectId(), endpoint.getFieldId());
                if (object.isPresent() && field.isPresent()) {
                    List<String> path = new ArrayList<String>();
                    path.add(field.get());
                    path.addAll(endpoint.getFieldPath());
                    ColumnAddress source = new ColumnAddress(object.get(), path);
                    sources.putIfAbsent(source, source);
                }
            }
        }
        NodeId id = new NodeId(definition.getId().getValue() + ":export:" + mapping.getInput().getValue());
        Expressions.SlotRef expression = new Expressions.SlotRef(id, TypeRef.unknown(), null, mapping.getInput(), Collections.emptyList());
        Optional<String> text = expressions.format(expression);
        ColumnDerivationKind kind = ColumnDerivationClassifier.classifyShape(expressions.shapes(), expression, facts.getDependencies());
        return new ColumnLineageRecord(statement.getIdentity(), target, new ArrayList<ColumnAddress>(sources.values()), kind,
                new RenderedExpression(text, expressions.extracted(expression)), expressions.functionUsage().functions(expression), facts.getQuality());
    }

    private List<QualifiedName> objects(List<Dependency> dependencies) {
        Map<QualifiedName, QualifiedName> sources = new LinkedHashMap<QualifiedName, QualifiedName>();
        for (Dependency dependency : dependencies) {
            names.endpointName(dependency.getSource())
                    .ifPresent(object -> sources.putIfAbsent(object, object));
        }
        return new ArrayList<QualifiedName>(sources.values());
    }

    private boolean included(Relations.DefinitionInstance definition) {
        if (!definition.getPhysicalObject().isPresent()) {
            return false;
        }
        BoundObjectReference object = statement.getBoundUnit().get().getObjects().get(definition.getPhysicalObject().get());
        return object != null && (object.getKind() == BoundObjectReference.ObjectKind.VIEW
                || object.getKind() == BoundObjectReference.ObjectKind.TEMPORARY && includeTemporary);
    }

    private String operation(ObjectId target) {
        return names.temporary(target) ? "CREATE_TEMPORARY_TABLE_AS_SELECT" : "CREATE_VIEW_AS_SELECT";
    }
}
