package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Registers a window slot and the computation defining it as one operation. */
final class WindowLayout {
    private final List<OutputSlot> slots = new ArrayList<>();
    private final List<Relations.WindowComputation> computations = new ArrayList<>();
    private final Set<SlotId> ids = new LinkedHashSet<>();

    void add(OutputSlot slot, Relations.WindowComputation computation) {
        if (!slot.getId().equals(computation.getAssignment().getOutput())) {
            throw new IllegalArgumentException("Window slot and assignment must agree");
        }
        if (!ids.add(slot.getId())) {
            throw new IllegalArgumentException("Window output is already registered");
        }
        slots.add(slot);
        computations.add(computation);
    }

    List<OutputSlot> slots() {
        return Collections.unmodifiableList(slots);
    }

    List<Relations.WindowComputation> computations() {
        return Collections.unmodifiableList(computations);
    }
}
