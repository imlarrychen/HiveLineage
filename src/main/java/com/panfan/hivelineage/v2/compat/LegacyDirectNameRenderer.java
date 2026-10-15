package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.v2.analysis.DirectFieldEndpoint;
import com.panfan.hivelineage.v2.analysis.LineageEndpoint;
import com.panfan.hivelineage.v2.analysis.SlotEndpoint;
import com.panfan.hivelineage.v2.export.BoundNameIndex;
import com.panfan.hivelineage.v2.export.SourceRenderer;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;

import java.util.Optional;

/**
 * Retains an unqualified spelling for an already bound direct condition reference.
 */
final class LegacyDirectNameRenderer {
    private final BoundNameIndex names;
    private final SourceRenderer renderer;

    LegacyDirectNameRenderer(BoundNameIndex names, SourceRenderer renderer) {
        this.names = names;
        this.renderer = renderer;
    }

    Optional<String> unqualified(LineageEndpoint endpoint, BoundExpression expression) {
        Optional<String> expected = matchingField(endpoint, expression);
        if (expected.isPresent()) {
            Optional<String> source = expression.getSourceRef().flatMap(renderer::render).map(String::trim);
            if (source.isPresent() && (source.get().equals(expected.get())
                    || source.get().equals("`" + expected.get().replace("`", "``") + "`"))) {
                return expected;
            }
        }
        for (BoundExpression child : IrTraversal.children(expression)) {
            Optional<String> result = unqualified(endpoint, child);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    private Optional<String> matchingField(LineageEndpoint endpoint, BoundExpression expression) {
        if (endpoint instanceof DirectFieldEndpoint && expression instanceof QualifiedFieldHandle) {
            DirectFieldEndpoint direct = (DirectFieldEndpoint) endpoint;
            QualifiedFieldHandle field = (QualifiedFieldHandle) expression;
            if (direct.getOwner().equals(field.getOwner()) && direct.getFieldId().equals(field.getFieldIdentity().getId())
                    && direct.getFieldPath().equals(field.getFieldPath())) {
                return Optional.of(field.getFieldIdentity().getDisplayName());
            }
        }
        if (endpoint instanceof SlotEndpoint && expression instanceof Expressions.SlotRef) {
            SlotEndpoint direct = (SlotEndpoint) endpoint;
            Expressions.SlotRef field = (Expressions.SlotRef) expression;
            if (direct.getSlotId().equals(field.getSlotId()) && field.getFieldPath().isEmpty()) {
                return names.ir().slot(field.getSlotId()).map(slot -> slot.getDisplayName());
            }
        }
        return Optional.empty();
    }
}
