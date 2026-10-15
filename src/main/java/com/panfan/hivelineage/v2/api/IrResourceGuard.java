package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.analysis.Dependency;
import com.panfan.hivelineage.v2.analysis.ExpressionFacts;
import com.panfan.hivelineage.v2.analysis.JoinFact;
import com.panfan.hivelineage.v2.analysis.JoinFacts;
import com.panfan.hivelineage.v2.analysis.LineageFacts;
import com.panfan.hivelineage.v2.analysis.RelationFacts;
import com.panfan.hivelineage.v2.common.ResourceLimitException;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.EvidenceId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSegment;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.UnknownOutputSegment;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Iterative bounds checks over unique typed IR identities and emitted proof edges.
 */
public final class IrResourceGuard {
    public Measures checkUnit(BoundAnalysisUnit unit, ResourceLimits limits) {
        Map<NodeId, Set<NodeId>> graph = new LinkedHashMap<NodeId, Set<NodeId>>();
        Set<SlotId> slots = new HashSet<SlotId>();
        ArrayDeque<BoundExpression> expressions = new ArrayDeque<BoundExpression>();
        for (BoundRelation relation : unit.getRelations()) {
            node(graph, relation.getId(), slots, limits);
            graph.get(relation.getId()).addAll(relation.getInputIds());
            for (OutputSlot slot : IrTraversal.knownSlots(relation.getOutputSchema())) {
                slots.add(slot.getId());
                count(graph, slots, limits);
            }
            for (OutputSegment segment : relation.getOutputSchema().getSegments()) {
                if (segment instanceof UnknownOutputSegment) {
                    node(graph, ((UnknownOutputSegment) segment).getId(), slots, limits);
                }
            }
            for (BoundExpression expression : IrTraversal.expressions(relation)) {
                graph.get(relation.getId()).add(expression.getId());
                expressions.add(expression);
            }
        }
        for (BoundWrite write : unit.getWrites()) {
            node(graph, write.getId(), slots, limits);
            write.getSourceRelation().ifPresent(root -> graph.get(write.getId()).add(root));
            for (WriteAssignment assignment : write.getAssignments()) {
                graph.get(write.getId()).add(assignment.getExpression().getId());
                expressions.add(assignment.getExpression());
            }
            write.getCondition().ifPresent(value -> {
                graph.get(write.getId()).add(value.getId());
                expressions.add(value);
            });
            for (PartitionScope.Assignment assignment : write.getPartitionScope().getAssignments()) {
                graph.get(write.getId()).add(assignment.getExpression().getId());
                expressions.add(assignment.getExpression());
            }
        }
        for (BoundEffect effect : unit.getEffects()) {
            node(graph, effect.getId(), slots, limits);
        }
        unit.getNotRequestedRegions().forEach(region -> node(graph, region.getId(), slots, limits));
        Set<BoundExpression> visited = Collections.newSetFromMap(new IdentityHashMap<BoundExpression, Boolean>());
        while (!expressions.isEmpty()) {
            BoundExpression expression = expressions.removeFirst();
            if (!visited.add(expression)) {
                continue;
            }
            node(graph, expression.getId(), slots, limits);
            if (expression instanceof Expressions.SlotRef) {
                slots.add(((Expressions.SlotRef) expression).getSlotId());
            } else if (expression instanceof Expressions.CorrelatedSlotRef) {
                slots.add(((Expressions.CorrelatedSlotRef) expression).getSlotId());
            } else if (expression instanceof Expressions.ScalarSubquery) {
                graph.get(expression.getId()).add(((Expressions.ScalarSubquery) expression).getQueryRoot());
            } else if (expression instanceof Expressions.ExistsSubquery) {
                graph.get(expression.getId()).add(((Expressions.ExistsSubquery) expression).getQueryRoot());
            } else if (expression instanceof Expressions.InSubquery) {
                graph.get(expression.getId()).add(((Expressions.InSubquery) expression).getQueryRoot());
            } else if (expression instanceof Expressions.NotRequestedExpression) {
                graph.get(expression.getId()).addAll(((Expressions.NotRequestedExpression) expression).getKnownQueryRoots());
            }
            count(graph, slots, limits);
            for (BoundExpression child : IrTraversal.children(expression)) {
                graph.get(expression.getId()).add(child.getId());
                expressions.addLast(child);
            }
        }
        int depth = depth(graph);
        if (depth > limits.getMaxIrDepth()) {
            throw new ResourceLimitException("IR depth " + depth + " exceeds maximum " + limits.getMaxIrDepth());
        }
        return new Measures(graph.size() + slots.size(), depth);
    }

    public long checkFacts(LineageFacts facts, ResourceLimits limits) {
        Set<EvidenceId> dependencies = new HashSet<EvidenceId>();
        Set<EvidenceEdge> parents = new HashSet<EvidenceEdge>();
        List<Dependency> values = new ArrayList<Dependency>(facts.getWriteDependencies());
        values.addAll(facts.getEvidenceGraph().getDependencies().values());
        for (RelationFacts relation : facts.getRelations()) {
            values.addAll(relation.getRowSetFacts());
            for (ExpressionFacts output : relation.getOutputFacts().values()) {
                values.addAll(output.getDependencies());
            }
        }
        for (Dependency dependency : values) {
            dependencies.add(dependency.getEvidence().getId());
            for (EvidenceId parent : dependency.getEvidence().getParentEvidence()) {
                parents.add(new EvidenceEdge(parent, dependency.getEvidence().getId()));
            }
            if ((long) dependencies.size() + parents.size() > limits.getMaxEvidenceEdges()) {
                throw new ResourceLimitException("Dependency/evidence edge limit exceeded");
            }
        }
        return (long) dependencies.size() + parents.size();
    }

    public void checkJoinFacts(JoinFacts facts, ResourceLimits limits) {
        long references = facts.getEvidenceGraph().getDependencies().size();
        for (Dependency dependency : facts.getEvidenceGraph().getDependencies().values()) {
            references += dependency.getEvidence().getParentEvidence().size();
        }
        if (references > limits.getMaxEvidenceEdges()) {
            throw new ResourceLimitException("Join proof graph limit exceeded");
        }
        for (JoinFact join : facts.getJoins()) {
            references += join.getDirectReferences().size() + join.getPhysicalReferences().size();
            if (references > limits.getMaxEvidenceEdges()) {
                throw new ResourceLimitException("Join dependency reference limit exceeded");
            }
        }
    }

    private void node(Map<NodeId, Set<NodeId>> graph, NodeId id, Set<SlotId> slots, ResourceLimits limits) {
        graph.computeIfAbsent(id, ignored -> new LinkedHashSet<NodeId>());
        count(graph, slots, limits);
    }

    private void count(Map<NodeId, Set<NodeId>> graph, Set<SlotId> slots, ResourceLimits limits) {
        if ((long) graph.size() + slots.size() > limits.getMaxIrNodes()) {
            throw new ResourceLimitException("Unique IR node/slot limit exceeded");
        }
    }

    private int depth(Map<NodeId, Set<NodeId>> graph) {
        Map<NodeId, List<NodeId>> parents = new HashMap<NodeId, List<NodeId>>();
        Map<NodeId, Integer> remaining = new HashMap<NodeId, Integer>();
        Map<NodeId, Integer> depths = new HashMap<NodeId, Integer>();
        ArrayDeque<NodeId> ready = new ArrayDeque<NodeId>();
        for (Map.Entry<NodeId, Set<NodeId>> entry : graph.entrySet()) {
            int declaredInputs = 0;
            for (NodeId child : entry.getValue()) {
                if (graph.containsKey(child)) {
                    parents.computeIfAbsent(child, ignored -> new ArrayList<NodeId>()).add(entry.getKey());
                    declaredInputs++;
                }
            }
            remaining.put(entry.getKey(), declaredInputs);
            depths.put(entry.getKey(), 1);
            if (declaredInputs == 0) {
                ready.add(entry.getKey());
            }
        }
        int deepest = 0;
        while (!ready.isEmpty()) {
            NodeId node = ready.removeFirst();
            int current = depths.get(node);
            deepest = Math.max(deepest, current);
            for (NodeId parent : parents.getOrDefault(node, Collections.emptyList())) {
                depths.put(parent, Math.max(depths.get(parent), current + 1));
                int pending = remaining.get(parent) - 1;
                remaining.put(parent, pending);
                if (pending == 0) {
                    ready.add(parent);
                }
            }
        }
        return deepest;
    }

    public static final class Measures {
        private final int nodeCount;
        private final int maxDepth;

        private Measures(int nodeCount, int maxDepth) {
            this.nodeCount = nodeCount;
            this.maxDepth = maxDepth;
        }

        public int getNodeCount() {
            return nodeCount;
        }

        public int getMaxDepth() {
            return maxDepth;
        }
    }

    private static final class EvidenceEdge {
        private final EvidenceId parent;
        private final EvidenceId child;

        private EvidenceEdge(EvidenceId parent, EvidenceId child) {
            this.parent = parent;
            this.child = child;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof EvidenceEdge)) {
                return false;
            }
            EvidenceEdge that = (EvidenceEdge) other;
            return parent.equals(that.parent) && child.equals(that.child);
        }

        @Override
        public int hashCode() {
            return Objects.hash(parent, child);
        }
    }
}
