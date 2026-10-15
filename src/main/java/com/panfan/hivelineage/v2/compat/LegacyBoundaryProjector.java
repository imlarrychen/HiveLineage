package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.TableLineage;
import com.panfan.hivelineage.v2.analysis.Dependency;
import com.panfan.hivelineage.v2.analysis.DependencyRole;
import com.panfan.hivelineage.v2.analysis.ExpressionFacts;
import com.panfan.hivelineage.v2.analysis.RelationFacts;
import com.panfan.hivelineage.v2.analysis.UnknownEndpoint;
import com.panfan.hivelineage.v2.api.StatementAnalysisResult;
import com.panfan.hivelineage.v2.export.BoundExpressionFormatter;
import com.panfan.hivelineage.v2.export.BoundInputObjectProjector;
import com.panfan.hivelineage.v2.export.BoundNameIndex;
import com.panfan.hivelineage.v2.export.ColumnDerivationClassifier;
import com.panfan.hivelineage.v2.export.ColumnDerivationKind;
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
 * Includes definition boundaries using their computed facts, without re-running lineage rules.
 */
final class LegacyBoundaryProjector {
    private final StatementAnalysisResult statement;
    private final BoundNameIndex names;
    private final BoundExpressionFormatter expressions;
    private final boolean includeTemporary;
    private final LegacyColumnSourceSelector selector;
    private final BoundInputObjectProjector inputs;
    private final Map<NodeId, RelationFacts> facts = new LinkedHashMap<NodeId, RelationFacts>();

    LegacyBoundaryProjector(StatementAnalysisResult statement, BoundNameIndex names,
                            BoundExpressionFormatter expressions, boolean includeTemporary,
                            LegacyColumnSourceSelector selector, BoundInputObjectProjector inputs) {
        this.statement = statement;
        this.names = names;
        this.expressions = expressions;
        this.includeTemporary = includeTemporary;
        this.selector = selector;
        this.inputs = inputs;
        for (RelationFacts relation : statement.getLineage().get().getRelations()) {
            facts.put(relation.getRelationId(), relation);
        }
    }

    List<TableLineage> tables() {
        List<TableLineage> result = new ArrayList<TableLineage>();
        Set<ObjectId> seen = new LinkedHashSet<ObjectId>();
        for (BoundRelation relation : statement.getBoundUnit().get().getRelations()) {
            if (!included(relation)) {
                continue;
            }
            Relations.DefinitionInstance definition = (Relations.DefinitionInstance) relation;
            ObjectId object = definition.getPhysicalObject().get();
            RelationFacts source = facts.get(definition.getInput());
            if (source != null && seen.add(object)) {
                List<DataObjectRef> inputs = new ArrayList<DataObjectRef>();
                this.inputs.inputsForRoot(definition.getInput()).forEach(input -> inputs.add(new DataObjectRef(input.getDisplayName())));
                result.add(new TableLineage(new DataObjectRef(names.object(object).get()), inputs,
                        "VIEW_DEFINITION", statement.getIdentity().getStatementIndex(), false));
            }
        }
        return result;
    }

    List<ColumnLineage> columns() {
        List<ColumnLineage> result = new ArrayList<ColumnLineage>();
        Set<String> seen = new LinkedHashSet<String>();
        for (BoundRelation relation : statement.getBoundUnit().get().getRelations()) {
            if (!included(relation)) {
                continue;
            }
            Relations.DefinitionInstance definition = (Relations.DefinitionInstance) relation;
            RelationFacts source = facts.get(definition.getInput());
            if (source != null) {
                for (SlotMapping mapping : definition.getMappings()) {
                    appendColumn(result, seen, definition, mapping, source);
                }
            }
        }
        return result;
    }

    private void appendColumn(List<ColumnLineage> result, Set<String> seen, Relations.DefinitionInstance definition,
                              SlotMapping mapping, RelationFacts source) {
        Optional<OutputSlot> output = names.ir().slot(mapping.getOutput());
        ExpressionFacts dependencies = source.getOutputFacts().get(mapping.getInput());
        if (!output.isPresent() || dependencies == null) {
            return;
        }
        String target = names.object(definition.getPhysicalObject().get()).get() + "." + output.get().getDisplayName();
        if (!seen.add(target)) {
            return;
        }
        List<ColumnRef> columns = new ArrayList<ColumnRef>();
        Set<String> fieldNames = new LinkedHashSet<String>();
        NodeId expressionId = new NodeId(definition.getId().getValue() + ":export:" + mapping.getInput().getValue());
        Expressions.SlotRef expression = new Expressions.SlotRef(expressionId,
                TypeRef.unknown(), null, mapping.getInput(), Collections.emptyList());
        List<Dependency> selected = selector.select(dependencies.getDependencies(), expression, definition.getInput());
        for (Dependency dependency : selected) {
            boolean hiddenTemporary = dependency.getRole() == DependencyRole.DEFINITION
                    && names.temporary(dependency.getSource()) && !includeTemporary;
            if (!hiddenTemporary && dependency.getRole() != DependencyRole.ROWSET) {
                names.column(dependency.getSource(), false).ifPresent(fieldNames::add);
            }
        }
        for (String field : fieldNames) {
            columns.add(new ColumnRef(field));
        }
        if (fieldNames.isEmpty() && dependencies.getDependencies().stream()
                .anyMatch(dependency -> dependency.getSource() instanceof UnknownEndpoint)) {
            return;
        }
        Optional<String> text = expressions.format(expression);
        ColumnDerivationKind kind = ColumnDerivationClassifier.classify(expressions, expression, dependencies.getDependencies());
        LineageType derivation = kind == ColumnDerivationKind.UNKNOWN ? LineageType.DERIVED : LineageType.valueOf(kind.name());
        result.add(ColumnLineage.builder().targetColumn(new ColumnRef(target)).sourceColumns(columns)
                .sourceObjects(objects(selected)).expression(text.orElse(null))
                .expressionExtracted(expressions.extracted(expression))
                .lineageType(derivation)
                .functionNames(expressions.functions(expression))
                .complexExpression(derivation != LineageType.CONSTANT && expressions.complex(expression))
                .statementIndex(statement.getIdentity().getStatementIndex()).build());
    }

    private List<DataObjectRef> objects(List<Dependency> dependencies) {
        Set<String> values = new LinkedHashSet<String>();
        for (Dependency dependency : dependencies) {
            names.physicalObject(dependency.getSource()).ifPresent(values::add);
        }
        List<DataObjectRef> result = new ArrayList<DataObjectRef>();
        for (String value : values) {
            result.add(new DataObjectRef(value));
        }
        return result;
    }

    private boolean included(BoundRelation relation) {
        if (!(relation instanceof Relations.DefinitionInstance)) {
            return false;
        }
        Optional<ObjectId> object = ((Relations.DefinitionInstance) relation).getPhysicalObject();
        if (!object.isPresent() || !names.object(object.get()).isPresent()) {
            return false;
        }
        BoundObjectReference declaration = statement.getBoundUnit().get().getObjects().get(object.get());
        return declaration.getKind() == BoundObjectReference.ObjectKind.VIEW;
    }
}
