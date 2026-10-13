package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.syntax.AssignmentSyntax;
import com.panfan.hivelineage.v2.syntax.CteSyntax;
import com.panfan.hivelineage.v2.syntax.DeleteStatementSyntax;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.MergeActionSyntax;
import com.panfan.hivelineage.v2.syntax.MergeBodySyntax;
import com.panfan.hivelineage.v2.syntax.MergeClauseSyntax;
import com.panfan.hivelineage.v2.syntax.MergeDeleteSyntax;
import com.panfan.hivelineage.v2.syntax.MergeInsertSyntax;
import com.panfan.hivelineage.v2.syntax.MergeMatchKindSyntax;
import com.panfan.hivelineage.v2.syntax.MergeStatementSyntax;
import com.panfan.hivelineage.v2.syntax.MergeTargetSyntax;
import com.panfan.hivelineage.v2.syntax.MergeUpdateSyntax;
import com.panfan.hivelineage.v2.syntax.NamedFromSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxExpression;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;
import com.panfan.hivelineage.v2.syntax.UpdateStatementSyntax;
import com.panfan.hivelineage.v2.syntax.ValuesRowSyntax;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.hadoop.hive.ql.parse.ASTNode;

final class HiveMutationNormalizer {
    private final HiveAstSupport ast;
    private final HiveQueryNormalizer queries;

    HiveMutationNormalizer(HiveAstSupport ast, HiveQueryNormalizer queries) {
        this.ast = ast;
        this.queries = queries;
    }

    SyntaxStatement mutation(ASTNode node) {
        if ("TOK_MERGE".equals(ast.kind(node))) {
            return merge(node);
        }
        ASTNode name = ast.at(node, 0);
        NamedFromSyntax target = new NamedFromSyntax(ast.id(name), ast.ref(name), ast.name(name), null, null);
        ASTNode where = ast.child(node, "TOK_WHERE");
        SyntaxExpression predicate = where == null ? null : queries.expressions().expression(ast.at(where, 0));
        if ("TOK_UPDATE_TABLE".equals(ast.kind(node))) {
            return new UpdateStatementSyntax(ast.id(node, "statement"), ast.ref(node), target,
                    assignments(ast.child(node, "TOK_SET_COLUMNS_CLAUSE")), predicate, Collections.<CteSyntax>emptyList());
        }
        return new DeleteStatementSyntax(ast.id(node, "statement"), ast.ref(node), target, predicate,
                Collections.<CteSyntax>emptyList());
    }

    private MergeStatementSyntax merge(ASTNode node) {
        ASTNode target = ast.at(node, 0);
        NamedFromSyntax targetFrom = (NamedFromSyntax) queries.from(target);
        List<MergeClauseSyntax> clauses = new ArrayList<MergeClauseSyntax>();
        for (int index = 3; index < node.getChildCount(); index++) {
            ASTNode clause = ast.at(node, index);
            ASTNode action = ast.at(clause, 0);
            SyntaxExpression condition = clause.getChildCount() > 1 ? queries.expressions().expression(ast.at(clause, 1)) : null;
            clauses.add(new MergeClauseSyntax("TOK_MATCHED".equals(ast.kind(clause)) ? MergeMatchKindSyntax.MATCHED
                    : MergeMatchKindSyntax.NOT_MATCHED, condition, mergeAction(action)));
        }
        MergeBodySyntax body = new MergeBodySyntax(new MergeTargetSyntax(targetFrom.getName(), targetFrom.getAlias()),
                queries.from(ast.at(node, 1)), queries.expressions().expression(ast.at(node, 2)), clauses);
        return new MergeStatementSyntax(ast.id(node, "statement"), ast.ref(node), Collections.<CteSyntax>emptyList(), body);
    }

    private MergeActionSyntax mergeAction(ASTNode node) {
        if ("TOK_DELETE".equals(ast.kind(node))) {
            return new MergeDeleteSyntax();
        }
        if ("TOK_UPDATE".equals(ast.kind(node))) {
            return new MergeUpdateSyntax(assignments(ast.child(node, "TOK_SET_COLUMNS_CLAUSE")));
        }
        ASTNode struct = ast.at(node, 0);
        return new MergeInsertSyntax(Collections.<IdentifierPart>emptyList(),
                new ValuesRowSyntax(queries.expressions().expressions(struct, 1)));
    }

    private List<AssignmentSyntax> assignments(ASTNode node) {
        List<AssignmentSyntax> assignments = new ArrayList<AssignmentSyntax>();
        for (ASTNode assignment : ast.children(node)) {
            assignments.add(new AssignmentSyntax(ast.name(ast.at(assignment, 0)),
                    queries.expressions().expression(ast.at(assignment, 1))));
        }
        return assignments;
    }
}
