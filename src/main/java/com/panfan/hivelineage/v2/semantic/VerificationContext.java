package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundScopeReference;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only declarations and bounded reachability queries for one verification. */
final class VerificationContext {
    private static final int REACHABILITY_CACHE_ENTRIES = 32;
    private final BoundAnalysisUnit unit;
    private final Map<NodeId, BoundRelation> relations;
    private final Map<NodeId, List<OutputSlot>> slots = new LinkedHashMap<>();
    private final Map<NodeId, Set<SlotId>> outputs = new LinkedHashMap<>();
    private final Map<SlotId, QueryBlockId> slotBlocks = new LinkedHashMap<>();
    private final Map<RelationInstanceId, Relations.Scan> scans = new LinkedHashMap<>();
    private final Map<QueryBlockId, List<BoundScopeReference>> blockScopes = new LinkedHashMap<>();
    private final Map<NodeId, Set<NodeId>> reachable = new LinkedHashMap<NodeId, Set<NodeId>>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<NodeId, Set<NodeId>> entry) {
            return size() > REACHABILITY_CACHE_ENTRIES;
        }
    };

    VerificationContext(BoundAnalysisUnit unit) {
        this.unit = unit;
        relations = ModelCollections.immutableMap(VerificationSupport.relations(unit));
        for (BoundRelation relation : relations.values()) {
            List<OutputSlot> known = ModelCollections.immutableList(IrTraversal.knownSlots(relation.getOutputSchema()));
            slots.put(relation.getId(), known);
            Set<SlotId> ids = new LinkedHashSet<>();
            for (OutputSlot slot : known) {
                ids.add(slot.getId());
            }
            outputs.put(relation.getId(), Collections.unmodifiableSet(ids));
        }
        for (BoundRelation relation : unit.getRelations()) {
            for (SlotId slot : outputs(relation.getId())) {
                slotBlocks.putIfAbsent(slot, relation.getQueryBlockId());
            }
            if (relation instanceof Relations.Scan) {
                scans.put(((Relations.Scan) relation).getInstanceId(), (Relations.Scan) relation);
            }
        }
        unit.getScopes().values().forEach(scope -> blockScopes.computeIfAbsent(scope.getQueryBlockId(), ignored -> new ArrayList<>()).add(scope));
    }

    BoundAnalysisUnit unit() {
        return unit;
    }

    Map<NodeId, BoundRelation> relations() {
        return relations;
    }

    List<OutputSlot> slots(NodeId id) {
        return slots.getOrDefault(id, Collections.emptyList());
    }

    List<OutputSlot> declaredSlots(BoundRelation relation) {
        return relations.get(relation.getId()) == relation ? slots(relation.getId())
                : ModelCollections.immutableList(IrTraversal.knownSlots(relation.getOutputSchema()));
    }

    Set<SlotId> declaredOutputs(BoundRelation relation) {
        return relations.get(relation.getId()) == relation ? outputs(relation.getId())
                : Collections.unmodifiableSet(VerificationSupport.outputs(relation));
    }

    Set<SlotId> outputs(NodeId id) {
        return outputs.getOrDefault(id, Collections.emptySet());
    }

    QueryBlockId slotBlock(SlotId id) {
        return slotBlocks.get(id);
    }

    Relations.Scan scan(RelationInstanceId id) {
        return scans.get(id);
    }

    BoundScopeReference scope(ScopeId id) {
        return unit.getScopes().get(id);
    }

    Set<NodeId> reachable(NodeId root) {
        return reachable.computeIfAbsent(root, id -> Collections.unmodifiableSet(VerificationSupport.reachable(id, relations)));
    }

    Set<SlotId> inputSlots(BoundRelation relation) {
        Set<SlotId> available = new LinkedHashSet<>();
        for (NodeId input : relation.getInputIds()) {
            available.addAll(outputs(input));
        }
        return Collections.unmodifiableSet(available);
    }

    Visibility inputs(BoundRelation relation) {
        Set<RelationInstanceId> owners = new LinkedHashSet<>();
        for (NodeId input : relation.getInputIds()) {
            owners.addAll(owners(input));
        }
        return new Visibility(inputSlots(relation), owners);
    }

    Visibility write(BoundWrite write) {
        if (!write.getSourceRelation().isPresent()) {
            return new Visibility(slotBlocks.keySet(), scans.keySet());
        }
        NodeId root = write.getSourceRelation().get();
        return new Visibility(outputs(root), owners(root));
    }

    private Set<RelationInstanceId> owners(NodeId root) {
        Set<RelationInstanceId> owners = new LinkedHashSet<>();
        for (NodeId ancestor : reachable(root)) {
            BoundRelation relation = relations.get(ancestor);
            if (relation instanceof Relations.Scan) {
                owners.add(((Relations.Scan) relation).getInstanceId());
            }
        }
        return owners;
    }

    boolean isAncestor(QueryBlockId block, ScopeId ancestor) {
        if (block == null) {
            return false;
        }
        for (BoundScopeReference scope : blockScopes.getOrDefault(block, Collections.emptyList())) {
            if (hasAncestor(scope, ancestor)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAncestor(BoundScopeReference scope, ScopeId ancestor) {
        Set<ScopeId> seen = new HashSet<>();
        BoundScopeReference cursor = scope;
        while (cursor.getParent().isPresent() && seen.add(cursor.getId())) {
            if (cursor.getParent().get().equals(ancestor)) {
                return true;
            }
            cursor = this.scope(cursor.getParent().get());
            if (cursor == null) {
                break;
            }
        }
        return false;
    }

    static final class Visibility {
        private final Set<SlotId> slots;
        private final Set<RelationInstanceId> owners;

        Visibility(Set<SlotId> slots, Set<RelationInstanceId> owners) {
            this.slots = Collections.unmodifiableSet(new LinkedHashSet<>(slots));
            this.owners = Collections.unmodifiableSet(new LinkedHashSet<>(owners));
        }

        Set<SlotId> slots() {
            return slots;
        }

        Set<RelationInstanceId> owners() {
            return owners;
        }
    }
}
