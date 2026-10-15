package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;

/** Coordinates demand, reference and version checks over one immutable declaration context. */
public final class DefaultBindingVerifier implements BindingVerifier {
    @Override
    public DiagnosticBag verifyBinding(BoundAnalysisUnit unit, BindingDemand demand) {
        VerificationContext context = new VerificationContext(unit);
        VerificationDiagnostics diagnostics = new VerificationDiagnostics();
        BindingCoverageVerification coverage = new BindingCoverageVerification(context, demand, diagnostics);
        ReferenceReachabilityVerification references = new ReferenceReachabilityVerification(context, diagnostics);
        VersionBindingVerification versions = new VersionBindingVerification(context, demand, diagnostics);
        coverage.verifyCoverage();
        for (BoundRelation relation : unit.getRelations()) {
            VerificationContext.Visibility visibility = context.inputs(relation);
            for (BoundExpression expression : IrTraversal.expressions(relation)) {
                coverage.expression(expression);
                references.verify(expression, relation.getQueryBlockId(), visibility);
            }
            coverage.relation(relation);
            versions.relation(relation);
        }
        for (BoundWrite write : unit.getWrites()) {
            VerificationContext.Visibility visibility = context.write(write);
            for (WriteAssignment assignment : write.getAssignments()) {
                references.verify(assignment.getExpression(), null, visibility);
            }
            for (PartitionScope.Assignment assignment : write.getPartitionScope().getAssignments()) {
                references.verify(assignment.getExpression(), null, visibility);
            }
            write.getCondition().ifPresent(expression -> references.verify(expression, null, visibility));
            coverage.write(write);
            versions.write(write);
        }
        return diagnostics.result();
    }
}
