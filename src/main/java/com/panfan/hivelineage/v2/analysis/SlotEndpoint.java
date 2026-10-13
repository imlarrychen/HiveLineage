package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.ir.identity.SlotId;

public final class SlotEndpoint implements LineageEndpoint {
    private final SlotId slotId;

    public SlotEndpoint(SlotId slotId) {
        this.slotId = ModelChecks.value(slotId, "slotId");
    }

    public SlotId getSlotId() {
        return slotId;
    }

    @Override
    public EndpointKind getKind() {
        return EndpointKind.SLOT;
    }
}
