package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.syntax.FrameBoundaryKindSyntax;
import com.panfan.hivelineage.v2.syntax.FrameBoundarySyntax;
import com.panfan.hivelineage.v2.syntax.FrameUnitSyntax;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.NullOrderingSyntax;
import com.panfan.hivelineage.v2.syntax.SortDirectionSyntax;
import com.panfan.hivelineage.v2.syntax.SortItemSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxExpression;
import com.panfan.hivelineage.v2.syntax.WindowFrameSyntax;
import com.panfan.hivelineage.v2.syntax.WindowSpecificationSyntax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.apache.hadoop.hive.ql.parse.ASTNode;

/** Normalizes window clauses through the same cached expression entry point as ordinary syntax. */
final class HiveWindowNormalizer {
    private final HiveAstSupport ast;
    private final Function<ASTNode, SyntaxExpression> expressions;

    HiveWindowNormalizer(HiveAstSupport ast, Function<ASTNode, SyntaxExpression> expressions) {
        this.ast = ast;
        this.expressions = expressions;
    }

    WindowSpecificationSyntax window(ASTNode node) {
        ASTNode partition = ast.child(node, "TOK_PARTITIONINGSPEC");
        ASTNode partitionBy = ast.child(partition, "TOK_DISTRIBUTEBY");
        ASTNode orderBy = ast.child(partition, "TOK_ORDERBY");
        ASTNode frameNode = ast.child(node, "TOK_WINDOWRANGE");
        if (frameNode == null) {
            frameNode = ast.child(node, "TOK_WINDOWVALUES");
        }
        WindowFrameSyntax frame = frameNode == null ? null : new WindowFrameSyntax(
                "TOK_WINDOWRANGE".equals(ast.kind(frameNode)) ? FrameUnitSyntax.ROWS : FrameUnitSyntax.RANGE,
                boundary(ast.at(frameNode, 0)), frameNode.getChildCount() > 1 ? boundary(ast.at(frameNode, 1)) : null);
        IdentifierPart base = null;
        for (ASTNode child : ast.children(node)) {
            if ("Identifier".equals(ast.kind(child))) {
                base = ast.part(child);
            }
        }
        return new WindowSpecificationSyntax(base, partitionBy == null ? Collections.<SyntaxExpression>emptyList()
                : expressions(partitionBy, 0), sorts(orderBy), frame);
    }

    private FrameBoundarySyntax boundary(ASTNode node) {
        String direction = node.getText().toLowerCase(Locale.ROOT);
        boolean preceding = "preceding".equals(direction);
        if ("current".equals(direction)) {
            return new FrameBoundarySyntax(FrameBoundaryKindSyntax.CURRENT_ROW, null);
        }
        ASTNode offset = ast.at(node, 0);
        if ("unbounded".equalsIgnoreCase(offset.getText())) {
            return new FrameBoundarySyntax(preceding ? FrameBoundaryKindSyntax.UNBOUNDED_PRECEDING
                    : FrameBoundaryKindSyntax.UNBOUNDED_FOLLOWING, null);
        }
        return new FrameBoundarySyntax(preceding ? FrameBoundaryKindSyntax.PRECEDING
                : FrameBoundaryKindSyntax.FOLLOWING, expressions.apply(offset));
    }

    List<SortItemSyntax> sorts(ASTNode node) {
        List<SortItemSyntax> items = new ArrayList<SortItemSyntax>();
        for (ASTNode item : ast.children(node)) {
            SortDirectionSyntax direction = ast.kind(item).endsWith("DESC") ? SortDirectionSyntax.DESC : SortDirectionSyntax.ASC;
            ASTNode value = ast.at(item, 0);
            NullOrderingSyntax nullOrder = NullOrderingSyntax.DEFAULT;
            if (ast.kind(value).startsWith("TOK_NULLS_")) {
                nullOrder = ast.kind(value).endsWith("FIRST") ? NullOrderingSyntax.FIRST : NullOrderingSyntax.LAST;
                value = ast.at(value, 0);
            }
            items.add(new SortItemSyntax(expressions.apply(value), direction, nullOrder));
        }
        return items;
    }

    private List<SyntaxExpression> expressions(ASTNode parent, int start) {
        List<SyntaxExpression> expressions = new ArrayList<SyntaxExpression>();
        for (int index = start; index < parent.getChildCount(); index++) {
            ASTNode child = ast.at(parent, index);
            if (!"TOK_WINDOWSPEC".equals(ast.kind(child))) {
                expressions.add(this.expressions.apply(child));
            }
        }
        return expressions;
    }

    List<SyntaxExpression> knownChildren(ASTNode node) {
        WindowSpecificationSyntax specification = window(node);
        List<SyntaxExpression> children = new ArrayList<SyntaxExpression>(specification.getPartitionBy());
        for (SortItemSyntax sort : specification.getOrderBy()) {
            children.add(sort.getExpression());
        }
        if (specification.getFrame() != null) {
            addOffset(children, specification.getFrame().getStart());
            addOffset(children, specification.getFrame().getEnd());
        }
        return children;
    }

    private void addOffset(List<SyntaxExpression> children, FrameBoundarySyntax boundary) {
        if (boundary != null && boundary.getOffset() != null) {
            children.add(boundary.getOffset());
        }
    }

}
