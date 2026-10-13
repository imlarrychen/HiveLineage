package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;

/** Identity-only lookup over a verified graph. No textual name recovery or external access. */
public final class PureIrIndex {
    private final Map<NodeId, BoundRelation> relations = new LinkedHashMap<NodeId, BoundRelation>();
    private final Map<SlotId, OutputSlot> slots = new LinkedHashMap<SlotId, OutputSlot>();
    private final Map<SlotId, BoundRelation> slotOwners = new LinkedHashMap<SlotId, BoundRelation>();
    private final Map<RelationInstanceId, ObjectId> objects = new LinkedHashMap<RelationInstanceId, ObjectId>();

    public PureIrIndex(BoundAnalysisUnit unit) {
        for (BoundRelation relation : unit.getRelations()) {
            relations.put(relation.getId(), relation);
            for (OutputSlot slot : IrTraversal.knownSlots(relation.getOutputSchema())) {
                if (!slots.containsKey(slot.getId())
                        || isTransparent(slotOwners.get(slot.getId()), slot.getId()) && !isTransparent(relation, slot.getId())) {
                    slots.put(slot.getId(), slot);
                    slotOwners.put(slot.getId(), relation);
                }
            }
            if (relation instanceof Relations.Scan) {
                objects.put(((Relations.Scan) relation).getInstanceId(), ((Relations.Scan) relation).getObjectId());
            } else if (relation instanceof Relations.DefinitionInstance) {
                Relations.DefinitionInstance definition = (Relations.DefinitionInstance) relation;
                definition.getPhysicalObject().ifPresent(object -> objects.put(definition.getInstanceId(), object));
            }
        }
    }

    public Map<NodeId, BoundRelation> getRelations() {
        return Collections.unmodifiableMap(relations);
    }

    public Optional<OutputSlot> slot(SlotId id) {
        return Optional.ofNullable(slots.get(id));
    }

    public Optional<BoundRelation> slotOwner(SlotId id) {
        return Optional.ofNullable(slotOwners.get(id));
    }

    public Optional<ObjectId> object(RelationInstanceId id) {
        return Optional.ofNullable(objects.get(id));
    }

    private boolean isTransparent(BoundRelation relation, SlotId slot) {
        List<SlotMapping> mappings = Collections.emptyList();
        if (relation instanceof Relations.Filter) {
            mappings = ((Relations.Filter) relation).getMappings();
        } else if (relation instanceof Relations.Join) {
            mappings = ((Relations.Join) relation).getMappings();
        } else if (relation instanceof Relations.Window) {
            mappings = ((Relations.Window) relation).getRetainedSlots();
        } else if (relation instanceof Relations.Generate) {
            mappings = ((Relations.Generate) relation).getRetainedSlots();
        } else if (relation instanceof Relations.Sort) {
            mappings = ((Relations.Sort) relation).getMappings();
        } else if (relation instanceof Relations.Limit) {
            mappings = ((Relations.Limit) relation).getMappings();
        } else if (relation instanceof Relations.Distinct) {
            mappings = ((Relations.Distinct) relation).getMappings();
        } else if (relation instanceof Relations.DefinitionInstance) {
            mappings = ((Relations.DefinitionInstance) relation).getMappings();
        }
        for (SlotMapping mapping : mappings) {
            if (mapping.getInput().equals(slot) && mapping.getOutput().equals(slot)) {
                return true;
            }
        }
        return false;
    }
}
