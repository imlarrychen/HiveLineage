package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.Objects;

public final class WindowCallSyntax extends SyntaxExpression {
    private final CallSyntax call;
    private final WindowSpecificationSyntax window;

    public WindowCallSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            CallSyntax call,
            WindowSpecificationSyntax window) {
        super(nodeId, sourceRef);
        this.call = Objects.requireNonNull(call, "call");
        this.window = Objects.requireNonNull(window, "window");
    }

    public CallSyntax getCall() {
        return call;
    }

    public WindowSpecificationSyntax getWindow() {
        return window;
    }

    @Override
    public ExpressionKind getKind() {
        return ExpressionKind.WINDOW_CALL;
    }
}
