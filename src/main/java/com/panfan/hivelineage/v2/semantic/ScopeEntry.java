package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;

import java.util.List;
import java.util.Optional;

public final class ScopeEntry {
    private final String visibleName;
    private final NodeId definitionNode;
    private final Optional<RelationInstanceId> relation;
    private final List<SlotId> visibleSlots;

    public ScopeEntry(String visibleName, NodeId definitionNode, Optional<RelationInstanceId> relation, List<SlotId> visibleSlots) {
        this.visibleName = ModelChecks.text(visibleName, "visibleName");
        this.definitionNode = ModelChecks.value(definitionNode, "definitionNode");
        this.relation = ModelChecks.value(relation, "relation");
        this.visibleSlots = ModelCollections.immutableList(visibleSlots);
    }

    public String getVisibleName() {
        return visibleName;
    }

    public NodeId getDefinitionNode() {
        return definitionNode;
    }

    public Optional<RelationInstanceId> getRelation() {
        return relation;
    }

    public List<SlotId> getVisibleSlots() {
        return visibleSlots;
    }
}
