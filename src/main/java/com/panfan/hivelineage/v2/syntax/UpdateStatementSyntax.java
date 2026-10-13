package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.List;
import java.util.Objects;

public final class UpdateStatementSyntax extends SyntaxStatement {
    private final NamedFromSyntax target;
    private final List<AssignmentSyntax> assignments;
    private final SyntaxExpression where;
    private final List<CteSyntax> ctes;

    public UpdateStatementSyntax(NodeId nodeId, SourceRef sourceRef, NamedFromSyntax target,
                                 List<AssignmentSyntax> assignments, SyntaxExpression where, List<CteSyntax> ctes) {
        super(nodeId, sourceRef);
        this.target = Objects.requireNonNull(target, "target");
        this.assignments = ModelCollections.immutableList(assignments);
        this.where = where;
        this.ctes = ModelCollections.immutableList(ctes);
    }

    public NamedFromSyntax getTarget() {
        return target;
    }

    public List<AssignmentSyntax> getAssignments() {
        return assignments;
    }

    public SyntaxExpression getWhere() {
        return where;
    }

    public List<CteSyntax> getCtes() {
        return ctes;
    }

    @Override
    public StatementKind getKind() {
        return StatementKind.UPDATE;
    }
}
