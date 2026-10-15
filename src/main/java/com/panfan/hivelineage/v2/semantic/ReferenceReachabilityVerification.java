package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.CorrelatedQualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundScopeReference;


/** Reference checks consume declared identities and an explicit visibility boundary. */
final class ReferenceReachabilityVerification {
    private final VerificationContext context;
    private final VerificationDiagnostics diagnostics;

    ReferenceReachabilityVerification(VerificationContext context, VerificationDiagnostics diagnostics) {
        this.context = context;
        this.diagnostics = diagnostics;
    }

    void verify(BoundExpression root, QueryBlockId block, VerificationContext.Visibility visibility) {
        for (BoundExpression expression : VerificationSupport.flatten(root)) {
            if (expression instanceof Expressions.SlotRef) {
                if (!visibility.slots().contains(((Expressions.SlotRef) expression).getSlotId())) {
                    diagnostics.error("IR_SLOT_UNREACHABLE", "Slot " + ((Expressions.SlotRef) expression).getSlotId().getValue()
                            + " is not an output of the operator inputs; available=" + visibility.slots(), expression);
                }
            } else if (expression instanceof Expressions.CorrelatedSlotRef) {
                correlatedSlot((Expressions.CorrelatedSlotRef) expression, block);
            } else if (expression instanceof CorrelatedQualifiedFieldHandle) {
                correlatedField((CorrelatedQualifiedFieldHandle) expression, block);
            } else if (expression instanceof QualifiedFieldHandle) {
                QualifiedFieldHandle handle = (QualifiedFieldHandle) expression;
                if (!visibility.owners().contains(handle.getOwner()) || context.scan(handle.getOwner()) == null) {
                    diagnostics.error("IR_FIELD_OWNER_UNREACHABLE", "Physical field owner is not reachable", expression);
                }
            } else if (expression instanceof Expressions.ScalarSubquery) {
                Expressions.ScalarSubquery subquery = (Expressions.ScalarSubquery) expression;
                subquerySlot(subquery.getQueryRoot(), subquery.getResultSlot(), expression);
            } else if (expression instanceof Expressions.InSubquery) {
                Expressions.InSubquery subquery = (Expressions.InSubquery) expression;
                subquerySlot(subquery.getQueryRoot(), subquery.getResultSlot(), expression);
            }
        }
    }

    private void correlatedSlot(Expressions.CorrelatedSlotRef correlated, QueryBlockId block) {
        BoundScopeReference outer = context.scope(correlated.getOuterScopeId());
        if (outer == null || block == null || !context.isAncestor(block, correlated.getOuterScopeId())
                || !outer.getQueryBlockId().equals(context.slotBlock(correlated.getSlotId()))) {
            diagnostics.error("IR_CORRELATION_INVALID", "Correlated slot does not belong to a declared ancestor scope", correlated);
        }
    }

    private void correlatedField(CorrelatedQualifiedFieldHandle field, QueryBlockId block) {
        BoundScopeReference outer = context.scope(field.getOuterScopeId());
        Relations.Scan owner = context.scan(field.getOwner());
        if (outer == null || owner == null || block == null || !context.isAncestor(block, field.getOuterScopeId())
                || !outer.getQueryBlockId().equals(owner.getQueryBlockId())) {
            diagnostics.error("IR_CORRELATION_INVALID", "Correlated physical owner is not in the declared ancestor scope", field);
        }
    }

    private void subquerySlot(NodeId root, SlotId slot, BoundExpression expression) {
        if (!context.relations().containsKey(root) || !context.outputs(root).contains(slot)) {
            diagnostics.error("IR_SUBQUERY_SLOT_INVALID", "Subquery result slot is not exported by its root", expression);
        }
    }

}
