package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Operator behavior shared by the small built-in rules. */
final class StandardRelationRules {
    private StandardRelationRules() {
    }

    static void scan(RelationFactBuilder facts) {
        Relations.Scan scan = (Relations.Scan) facts.relation();
        facts.addRow(facts.evidence().dependency(new RowSetEndpoint(scan.getInstanceId()), facts.rowTarget(), DependencyRole.ROWSET,
                DependencyPrecision.STRUCTURAL, facts.id(), "scan.rows", Collections.emptyList(), scan.getSourceRef()));
        for (OutputSlot slot : IrTraversal.knownSlots(scan.getOutputSchema())) {
            LineageEndpoint source = slot.getFieldIdentity().isPresent()
                    ? new ColumnEndpoint(scan.getObjectId(), slot.getFieldIdentity().get().getId(), Collections.emptyList())
                    : new UnknownEndpoint(FactSupport.diagnostic("SCAN_FIELD_IDENTITY_MISSING", "Scan field lacks a declared identity", null));
            List<Dependency> dependencies = Collections.singletonList(facts.evidence().dependency(source, new SlotEndpoint(slot.getId()),
                    DependencyRole.VALUE, source instanceof UnknownEndpoint ? DependencyPrecision.UNKNOWN : DependencyPrecision.STRUCTURAL,
                    facts.id(), "scan.column", Collections.emptyList(), scan.getSourceRef()));
            facts.output(slot.getId(), facts.id(), dependencies);
        }
    }

    static void values(RelationFactBuilder facts) {
        Relations.Values values = (Relations.Values) facts.relation();
        List<OutputSlot> declared = IrTraversal.knownSlots(values.getOutputSchema());
        for (int position = 0; position < declared.size(); position++) {
            SlotId target = declared.get(position).getId();
            List<Dependency> dependencies = new ArrayList<Dependency>();
            for (List<BoundExpression> row : values.getRows()) {
                dependencies.addAll(facts.expression(row.get(position), target, "values", null));
            }
            facts.output(target, facts.id(), dependencies);
        }
    }

    static void filter(RelationFactBuilder facts) {
        Relations.Filter filter = (Relations.Filter) facts.relation();
        facts.mappings(filter.getMappings(), "filter.passthrough");
        facts.predicate(filter.getPredicate(), "filter.condition");
    }

    static void join(RelationFactBuilder facts) {
        Relations.Join join = (Relations.Join) facts.relation();
        facts.mappings(join.getMappings(), "join.passthrough");
        join.getCondition().ifPresent(condition -> facts.predicate(condition, "join.condition"));
        if (!join.getCondition().isPresent()) {
            for (Relations.JoinPredicate predicate : join.getPredicates()) {
                facts.predicate(predicate.getExpression(), "join.condition");
            }
        }
    }

    static void aggregate(RelationFactBuilder facts) {
        Relations.Aggregate aggregate = (Relations.Aggregate) facts.relation();
        for (BoundExpression grouping : aggregate.getGroupings()) {
            facts.predicate(grouping, "aggregate.grouping");
        }
        facts.assignments(aggregate.getAssignments(), "aggregate.output");
    }

    static void sort(RelationFactBuilder facts) {
        Relations.Sort sort = (Relations.Sort) facts.relation();
        facts.mappings(sort.getMappings(), "sort.passthrough");
        for (Relations.OrderKey key : sort.getOrderKeys()) {
            facts.predicate(key.getExpression(), "sort.order");
        }
    }

    static void limit(RelationFactBuilder facts) {
        Relations.Limit limit = (Relations.Limit) facts.relation();
        facts.mappings(limit.getMappings(), "limit.passthrough");
        facts.predicate(limit.getCount(), "limit.count");
        limit.getOffset().ifPresent(offset -> facts.predicate(offset, "limit.offset"));
    }

    static void distinct(RelationFactBuilder facts) {
        Relations.Distinct distinct = (Relations.Distinct) facts.relation();
        facts.mappings(distinct.getMappings(), "distinct.passthrough");
        for (SlotId key : distinct.getKeys()) {
            facts.addRows(facts.retarget(facts.slots().get(key).getDependencies(), facts.rowTarget(), "distinct.keys", DependencyRole.CONDITION));
        }
    }

    static void definitionInstance(RelationFactBuilder facts) {
        Relations.DefinitionInstance definition = (Relations.DefinitionInstance) facts.relation();
        facts.mappings(definition.getMappings(), "definition.expand");
        if (!definition.getPhysicalObject().isPresent()) {
            return;
        }
        facts.addRow(facts.evidence().dependency(new RowSetEndpoint(definition.getInstanceId()), facts.rowTarget(), DependencyRole.DEFINITION,
                DependencyPrecision.STRUCTURAL, facts.id(), "definition.boundary", Collections.emptyList(), definition.getSourceRef()));
        for (OutputSlot slot : IrTraversal.knownSlots(definition.getOutputSchema())) {
            if (slot.getFieldIdentity().isPresent() && facts.output(slot.getId()) != null) {
                List<Dependency> dependencies = new ArrayList<Dependency>(facts.output(slot.getId()).getDependencies());
                dependencies.add(facts.evidence().dependency(new ColumnEndpoint(definition.getPhysicalObject().get(), slot.getFieldIdentity().get().getId(), Collections.emptyList()),
                        new SlotEndpoint(slot.getId()), DependencyRole.DEFINITION, DependencyPrecision.STRUCTURAL, facts.id(), "definition.boundary", Collections.emptyList(), definition.getSourceRef()));
                facts.output(slot.getId(), facts.id(), dependencies);
            }
        }
    }

    static void unknown(RelationFactBuilder facts) {
        facts.addRow(facts.evidence().dependency(new UnknownEndpoint(FactSupport.diagnostic("UNKNOWN_RELATION", "Relation behavior is unknown", null)),
                facts.rowTarget(), DependencyRole.ROWSET, DependencyPrecision.UNKNOWN, facts.id(), "unknown.relation", Collections.emptyList(), facts.relation().getSourceRef()));
    }
}
