package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class WindowFrameSyntax {
    private final FrameUnitSyntax unit;
    private final FrameBoundarySyntax start;
    private final FrameBoundarySyntax end;

    public WindowFrameSyntax(FrameUnitSyntax unit, FrameBoundarySyntax start, FrameBoundarySyntax end) {
        this.unit = Objects.requireNonNull(unit, "unit");
        this.start = Objects.requireNonNull(start, "start");
        this.end = end;
    }

    public FrameUnitSyntax getUnit() {
        return unit;
    }

    public FrameBoundarySyntax getStart() {
        return start;
    }

    public FrameBoundarySyntax getEnd() {
        return end;
    }
}
