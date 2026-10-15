package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.Objects;

/**
 * Function syntax only; function behavior and symbol binding belong to the Binder.
 */
public final class CallSyntax extends SyntaxExpression {
    private final SyntaxName functionName;
    private final CallArgumentsSyntax arguments;

    public CallSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            SyntaxName functionName,
            CallArgumentsSyntax arguments) {
        super(nodeId, sourceRef);
        this.functionName = Objects.requireNonNull(functionName, "functionName");
        this.arguments = Objects.requireNonNull(arguments, "arguments");
    }

    public SyntaxName getFunctionName() {
        return functionName;
    }

    public CallArgumentsSyntax getArguments() {
        return arguments;
    }

    @Override
    public ExpressionKind getKind() {
        return ExpressionKind.CALL;
    }
}
