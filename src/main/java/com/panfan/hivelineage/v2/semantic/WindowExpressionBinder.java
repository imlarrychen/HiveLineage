package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.syntax.FrameBoundarySyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxExpression;
import com.panfan.hivelineage.v2.syntax.WindowCallSyntax;
import com.panfan.hivelineage.v2.syntax.WindowFrameSyntax;
import com.panfan.hivelineage.v2.syntax.WindowSpecificationSyntax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class WindowExpressionBinder {
    private final BindingWork work;
    private final ExpressionBinder expressions;

    WindowExpressionBinder(BindingWork work, ExpressionBinder expressions) {
        this.work = work;
        this.expressions = expressions;
    }

    BoundExpression bind(WindowCallSyntax syntax, ReferenceScope scope) {
        Expressions.Call call = expressions.call(syntax.getCall(), scope);
        WindowSpecificationSyntax specification = WindowSpecificationResolver.resolve(
                syntax.getWindow(), scope, work, syntax.getSourceRef());
        List<BoundExpression> partitions = new ArrayList<>();
        for (SyntaxExpression partition : specification.getPartitionBy()) {
            partitions.add(expressions.bind(partition, scope));
        }
        Relations.WindowFrame frame = null;
        WindowFrameSyntax syntaxFrame = specification.getFrame();
        if (syntaxFrame != null) {
            Relations.FrameBound end = syntaxFrame.getEnd() == null
                    ? new Relations.FrameBound(Relations.FrameBoundKind.CURRENT_ROW, null) : boundary(syntaxFrame.getEnd(), scope);
            frame = new Relations.WindowFrame(Relations.FrameUnit.valueOf(syntaxFrame.getUnit().name()),
                    boundary(syntaxFrame.getStart(), scope), end);
        }
        SlotId slotId = new SlotId(work.id(syntax, scope, "window-output").getValue());
        scope.windowsLayout.add(new OutputSlot(slotId, "_window_" + syntax.getSourceRef().getSpan().getStart(), call.getType(), null),
                new Relations.WindowComputation(new OutputAssignment(slotId, call),
                        partitions, expressions.orders(specification.getOrderBy(), scope), frame));
        return new Expressions.SlotRef(work.id(syntax, scope, "window-reference"), call.getType(), syntax.getSourceRef(),
                slotId, Collections.<String>emptyList());
    }

    private Relations.FrameBound boundary(FrameBoundarySyntax syntax, ReferenceScope scope) {
        return new Relations.FrameBound(Relations.FrameBoundKind.valueOf(syntax.getKind().name()),
                syntax.getOffset() == null ? null : expressions.bind(syntax.getOffset(), scope));
    }

}
