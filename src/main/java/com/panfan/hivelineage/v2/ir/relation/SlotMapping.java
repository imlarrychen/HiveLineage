package com.panfan.hivelineage.v2.ir.relation;

import com.panfan.hivelineage.v2.ir.identity.SlotId;
import java.util.Objects;

/** Transparent mapping may legally reuse the same defined Slot on both sides. */
public final class SlotMapping {
    private final SlotId output;
    private final SlotId input;

    public SlotMapping(SlotId output, SlotId input) {
        this.output = Objects.requireNonNull(output, "output");
        this.input = Objects.requireNonNull(input, "input");
    }

    public SlotId getOutput() {
        return output;
    }

    public SlotId getInput() {
        return input;
    }
}
