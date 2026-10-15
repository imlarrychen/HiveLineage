package com.panfan.hivelineage.v2.ir.relation;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;

public final class KnownOutputSegment implements OutputSegment {
    private final List<OutputSlot> slots;

    public KnownOutputSegment(List<OutputSlot> slots) {
        this.slots = ModelCollections.immutableList(slots);
    }

    public List<OutputSlot> getSlots() {
        return slots;
    }
}
