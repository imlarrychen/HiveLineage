package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.Objects;

public final class CommandStatementSyntax extends SyntaxStatement {
    private final CommandSyntax command;

    public CommandStatementSyntax(NodeId nodeId, SourceRef sourceRef, CommandSyntax command) {
        super(nodeId, sourceRef);
        this.command = Objects.requireNonNull(command, "command");
    }

    public CommandSyntax getCommand() {
        return command;
    }

    @Override
    public StatementKind getKind() {
        return StatementKind.COMMAND;
    }
}
