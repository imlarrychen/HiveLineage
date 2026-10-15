package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.InsertBranchSyntax;
import com.panfan.hivelineage.v2.syntax.InsertModeSyntax;
import com.panfan.hivelineage.v2.syntax.InsertSourceSyntax;
import com.panfan.hivelineage.v2.syntax.InsertStatementSyntax;
import com.panfan.hivelineage.v2.syntax.InsertTargetKindSyntax;
import com.panfan.hivelineage.v2.syntax.InsertTargetSyntax;
import com.panfan.hivelineage.v2.syntax.LoadDataBodySyntax;
import com.panfan.hivelineage.v2.syntax.LoadDataStatementSyntax;
import com.panfan.hivelineage.v2.syntax.OtherStatementSyntax;
import com.panfan.hivelineage.v2.syntax.PartitionEntrySyntax;
import com.panfan.hivelineage.v2.syntax.QueryInsertSourceSyntax;
import com.panfan.hivelineage.v2.syntax.QueryStatementSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxNormalizer;
import com.panfan.hivelineage.v2.syntax.SyntaxOptions;
import com.panfan.hivelineage.v2.syntax.SyntaxResult;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;
import com.panfan.hivelineage.v2.syntax.CommandStatementSyntax;
import com.panfan.hivelineage.v2.syntax.FunctionCommandSyntax;
import com.panfan.hivelineage.v2.syntax.FunctionResourceSyntax;
import com.panfan.hivelineage.v2.syntax.ValuesInsertSourceSyntax;
import com.panfan.hivelineage.v2.syntax.ValuesRowSyntax;
import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.frontend.StageOutcome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.hive.ql.parse.ASTNode;

public final class HiveSyntaxNormalizer implements SyntaxNormalizer<HiveParseArtifact> {
    @Override
    public SyntaxResult normalize(HiveParseArtifact artifact, SyntaxOptions options) {
        if (artifact.command() != null) {
            return new SyntaxResult(artifact.command(), Collections.<Diagnostic>emptyList(),
                    artifact.diagnostics().isEmpty() ? StageOutcome.SUCCESS : StageOutcome.PARTIAL);
        }
        final HiveAstSupport ast;
        try {
            ast = new HiveAstSupport(artifact, options);
        } catch (HiveAstSupport.NormalizationLimit limit) {
            return new SyntaxResult(null, Collections.singletonList(new Diagnostic(AnalysisStage.NORMALIZATION,
                    limit.code(), limit.getMessage(), artifact.unit().getExpandedUnit().getOriginalUnit().getSourceRef())),
                    StageOutcome.FAILED);
        }
        HiveQueryNormalizer queries = new HiveQueryNormalizer(ast);
        ASTNode root = artifact.tree();
        while (root.getType() == 0 && root.getChildCount() > 0) {
            root = ast.at(root, 0);
        }
        try {
            SyntaxStatement statement = statement(root, ast, queries, artifact);
            return new SyntaxResult(statement, ast.diagnostics(),
                    artifact.diagnostics().isEmpty() && ast.diagnostics().isEmpty() ? StageOutcome.SUCCESS : StageOutcome.PARTIAL);
        } catch (IllegalArgumentException error) {
            return failure(ast, root, error.getMessage());
        } catch (IndexOutOfBoundsException error) {
            return failure(ast, root, "Incomplete Hive syntax structure for " + ast.kind(root));
        } catch (StackOverflowError error) {
            return new SyntaxResult(null, Collections.singletonList(new Diagnostic(AnalysisStage.NORMALIZATION,
                    "NORMALIZATION_DEPTH_LIMIT", "Native AST conversion exhausted its stack", ast.ref(root))), StageOutcome.FAILED);
        }
    }

    private SyntaxResult failure(HiveAstSupport ast, ASTNode root, String message) {
        Diagnostic diagnostic = new Diagnostic(AnalysisStage.NORMALIZATION, "HIVE_NORMALIZATION_ERROR", message, ast.ref(root));
        return new SyntaxResult(null, Collections.singletonList(diagnostic), StageOutcome.FAILED);
    }

    private SyntaxStatement statement(ASTNode root, HiveAstSupport ast, HiveQueryNormalizer queries, HiveParseArtifact artifact) {
        String kind = ast.kind(root);
        if ("TOK_QUERY".equals(kind)) {
            return queryStatement(root, ast, queries, artifact);
        }
        if (kind.startsWith("TOK_UNION") || kind.startsWith("TOK_INTERSECT") || kind.startsWith("TOK_EXCEPT")) {
            return new QueryStatementSyntax(ast.id(root, "statement"), ast.ref(root), queries.query(root));
        }
        if ("TOK_CREATETABLE".equals(kind) || "TOK_CREATEVIEW".equals(kind) || "TOK_ALTERVIEW".equals(kind)
                || "TOK_DROPTABLE".equals(kind) || "TOK_DROPVIEW".equals(kind) || "TOK_ALTERTABLE".equals(kind)
                || "TOK_TRUNCATETABLE".equals(kind)) {
            return new HiveDefinitionNormalizer(ast, queries).definition(root);
        }
        if ("TOK_MERGE".equals(kind) || "TOK_UPDATE_TABLE".equals(kind) || "TOK_DELETE_FROM".equals(kind)) {
            return new HiveMutationNormalizer(ast, queries).mutation(root);
        }
        if ("TOK_LOAD".equals(kind)) {
            return load(root, ast, queries);
        }
        if ("TOK_CREATEFUNCTION".equals(kind) || "TOK_DROPFUNCTION".equals(kind)) {
            return function(root, ast);
        }
        return new OtherStatementSyntax(ast.id(root, "statement"), ast.ref(root), HiveStatementClassification.semantic(kind), null,
                HiveStatementClassification.dataChanging(kind));
    }

    private SyntaxStatement function(ASTNode root, HiveAstSupport ast) {
        boolean drop = "TOK_DROPFUNCTION".equals(ast.kind(root));
        List<FunctionResourceSyntax> resources = new ArrayList<FunctionResourceSyntax>();
        for (ASTNode resource : ast.children(ast.child(root, "TOK_RESOURCE_LIST"))) {
            String type = ast.kind(ast.at(resource, 0));
            resources.add(new FunctionResourceSyntax(type.startsWith("TOK_") ? type.substring(4).toLowerCase(java.util.Locale.ROOT) : type,
                    HiveAstSupport.unquote(ast.at(resource, 1).getText())));
        }
        FunctionCommandSyntax command = new FunctionCommandSyntax(ast.name(ast.at(root, 0)),
                drop ? null : HiveAstSupport.unquote(ast.at(root, 1).getText()), ast.child(root, "TOK_TEMPORARY") != null,
                drop, resources);
        return new CommandStatementSyntax(ast.id(root, "statement"), ast.ref(root), command);
    }

    private SyntaxStatement queryStatement(ASTNode root, HiveAstSupport ast, HiveQueryNormalizer queries, HiveParseArtifact artifact) {
        List<InsertBranchSyntax> branches = new ArrayList<InsertBranchSyntax>();
        for (ASTNode insert : ast.children(root)) {
            if (!"TOK_INSERT".equals(ast.kind(insert))) {
                continue;
            }
            ASTNode destination = ast.child(insert, "TOK_INSERT_INTO");
            if (destination == null) {
                destination = ast.child(insert, "TOK_DESTINATION");
            }
            if (ast.first(destination, "TOK_TMP_FILE") != null) {
                continue;
            }
            InsertModeSyntax mode = mode(insert, destination, ast);
            InsertSourceSyntax source = values(insert, ast, queries);
            if (source == null) {
                source = new QueryInsertSourceSyntax(queries.select(root, insert));
            }
            branches.add(new InsertBranchSyntax(ast.id(insert), ast.ref(insert), mode,
                    target(destination, ast, queries), source));
        }
        if (branches.isEmpty()) {
            return new QueryStatementSyntax(ast.id(root, "statement"), ast.ref(root), queries.query(root));
        }
        ASTNode from = ast.child(root, "TOK_FROM");
        return new InsertStatementSyntax(ast.id(root, "statement"), ast.ref(root), queries.ctes(root),
                from == null ? null : queries.from(ast.at(from, 0)), branches);
    }

    private InsertModeSyntax mode(ASTNode insert, ASTNode destination, HiveAstSupport ast) {
        ASTNode target = ast.first(destination, "TOK_TABNAME");
        int end = target == null ? ast.ref(insert).getSpan().getEnd() : ast.ref(target).getSpan().getStart();
        InsertModeSyntax original = ast.insertModeBefore(end);
        if (original != null) {
            return original;
        }
        return ast.child(insert, "TOK_INSERT_INTO") == null ? InsertModeSyntax.OVERWRITE : InsertModeSyntax.INTO;
    }

    private InsertTargetSyntax target(ASTNode destination, HiveAstSupport ast, HiveQueryNormalizer queries) {
        ASTNode name = ast.first(destination, "TOK_TABNAME");
        List<IdentifierPart> columns = new ArrayList<IdentifierPart>();
        for (ASTNode column : ast.children(ast.child(destination, "TOK_TABCOLNAME"))) {
            columns.add(ast.part(column));
        }
        List<PartitionEntrySyntax> partitions = new ArrayList<PartitionEntrySyntax>();
        for (ASTNode partition : ast.children(ast.first(destination, "TOK_PARTSPEC"))) {
            partitions.add(new PartitionEntrySyntax(ast.part(ast.at(partition, 0)), partition.getChildCount() > 1
                    ? queries.expressions().expression(ast.at(partition, 1)) : null));
        }
        if (name != null) {
            return new InsertTargetSyntax(InsertTargetKindSyntax.TABLE, ast.name(name), null, columns, partitions);
        }
        ASTNode directory = ast.first(destination, "TOK_DIR");
        ASTNode path = null;
        for (ASTNode child : ast.children(directory)) {
            if (ast.kind(child).contains("StringLiteral")) {
                path = child;
            }
        }
        return new InsertTargetSyntax(ast.first(destination, "KW_LOCAL") == null ? InsertTargetKindSyntax.DIRECTORY
                : InsertTargetKindSyntax.LOCAL_DIRECTORY, null, queries.expressions().expression(path), columns, partitions);
    }

    private InsertSourceSyntax values(ASTNode insert, HiveAstSupport ast, HiveQueryNormalizer queries) {
        ASTNode select = ast.child(insert, "TOK_SELECT");
        if (select == null || select.getChildCount() != 1) {
            return null;
        }
        ASTNode expression = ast.at(ast.at(select, 0), 0);
        if (!"TOK_FUNCTION".equals(ast.kind(expression)) || !"inline".equalsIgnoreCase(ast.at(expression, 0).getText())) {
            return null;
        }
        ASTNode array = ast.at(expression, 1);
        if (!"TOK_FUNCTION".equals(ast.kind(array)) || !"array".equalsIgnoreCase(ast.at(array, 0).getText())) {
            return null;
        }
        List<ValuesRowSyntax> rows = new ArrayList<ValuesRowSyntax>();
        for (int index = 1; index < array.getChildCount(); index++) {
            ASTNode row = ast.at(array, index);
            rows.add(new ValuesRowSyntax(queries.expressions().expressions(row, 1)));
        }
        return new ValuesInsertSourceSyntax(rows);
    }

    private SyntaxStatement load(ASTNode node, HiveAstSupport ast, HiveQueryNormalizer queries) {
        ASTNode path = ast.at(node, 0);
        ASTNode target = ast.first(node, "TOK_TABNAME");
        List<PartitionEntrySyntax> partitions = new ArrayList<PartitionEntrySyntax>();
        for (ASTNode part : ast.children(ast.first(node, "TOK_PARTSPEC"))) {
            partitions.add(new PartitionEntrySyntax(ast.part(ast.at(part, 0)), queries.expressions().expression(ast.at(part, 1))));
        }
        return new LoadDataStatementSyntax(ast.id(node, "statement"), ast.ref(node), new LoadDataBodySyntax(
                queries.expressions().expression(path), ast.first(node, "KW_LOCAL") != null,
                ast.first(node, "KW_OVERWRITE") != null, ast.name(target), partitions));
    }
}
