package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.syntax.CommandStatementSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionBodySyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionKindSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionStatementSyntax;
import com.panfan.hivelineage.v2.syntax.FunctionCommandSyntax;
import com.panfan.hivelineage.v2.syntax.InsertStatementSyntax;
import com.panfan.hivelineage.v2.syntax.InsertModeSyntax;
import com.panfan.hivelineage.v2.syntax.OtherStatementSyntax;
import com.panfan.hivelineage.v2.syntax.InsertTargetKindSyntax;
import com.panfan.hivelineage.v2.syntax.QueryStatementSyntax;
import com.panfan.hivelineage.v2.syntax.ResourceCommandSyntax;
import com.panfan.hivelineage.v2.syntax.SetCommandSyntax;
import com.panfan.hivelineage.v2.syntax.SourceCommandSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;
import com.panfan.hivelineage.v2.syntax.UseCommandSyntax;
import com.panfan.hivelineage.v2.syntax.ValuesInsertSourceSyntax;

/** Publication is decided from structured syntax, independently of binding and inference. */
final class StatementClassification {
    final String type;
    final String skipReason;
    final boolean formalCandidate;

    private StatementClassification(String type, String skipReason, boolean formalCandidate) {
        this.type = type;
        this.skipReason = skipReason;
        this.formalCandidate = formalCandidate;
    }

    static StatementClassification of(SyntaxStatement syntax, AnalysisContract contract) {
        if (syntax instanceof QueryStatementSyntax) {
            return new StatementClassification("SELECT", "PURE_QUERY", contract.getPublicationPolicy() == StatementPublicationPolicy.DETAILED);
        }
        if (syntax instanceof InsertStatementSyntax) {
            InsertStatementSyntax insert = (InsertStatementSyntax) syntax;
            boolean values = !insert.getBranches().isEmpty() && insert.getBranches().stream().allMatch(branch -> branch.getSource() instanceof ValuesInsertSourceSyntax);
            boolean directory = !insert.getBranches().isEmpty() && insert.getBranches().stream().allMatch(branch -> branch.getTarget().getKind() != InsertTargetKindSyntax.TABLE);
            return new StatementClassification(values ? "INSERT_VALUES" : directory ? "INSERT_DIRECTORY" : !insert.getBranches().isEmpty() && insert.getBranches().get(0).getMode() == InsertModeSyntax.OVERWRITE ? "INSERT_OVERWRITE" : "INSERT_INTO", values ? "INSERT_VALUES" : directory ? "NO_OUTPUT_TABLE" : null,
                !directory && (!values || contract.getPublicationPolicy() == StatementPublicationPolicy.DETAILED));
        }
        if (syntax instanceof DefinitionStatementSyntax) {
            DefinitionBodySyntax definition = ((DefinitionStatementSyntax) syntax).getDefinition();
            boolean query = definition.getQuery() != null;
            boolean temporary = definition.getOptions().isTemporary();
            String type = definition.getKind().name();
            if (query) {
                type = definition.getKind() == DefinitionKindSyntax.CREATE_TABLE
                    ? temporary ? "CREATE_TEMPORARY_TABLE_AS_SELECT" : "CREATE_TABLE_AS_SELECT"
                    : definition.getKind() == DefinitionKindSyntax.ALTER_VIEW ? "ALTER_VIEW_AS_SELECT" : "CREATE_VIEW_AS_SELECT";
            }
            String ddlReason = definition.getKind() == DefinitionKindSyntax.CREATE_TABLE
                ? temporary ? "CREATE_TEMPORARY_TABLE_DDL" : definition.getOptions().isExternal() ? "CREATE_EXTERNAL_TABLE_DDL" : "CREATE_TABLE_DDL"
                : type;
            if (!query && definition.getKind() == DefinitionKindSyntax.CREATE_TABLE) {
                type = temporary ? "CREATE_TEMPORARY_TABLE" : definition.getOptions().isExternal() ? "CREATE_EXTERNAL_TABLE" : "CREATE_TABLE";
            }
            return new StatementClassification(type, query ? temporary && !contract.isIncludeTemporaryLineage() ? "TEMPORARY_LINEAGE_DISABLED" : null : ddlReason,
                query && (!temporary || contract.isIncludeTemporaryLineage()));
        }
        if (syntax instanceof CommandStatementSyntax) {
            Object command = ((CommandStatementSyntax) syntax).getCommand();
            String type = command instanceof SetCommandSyntax ? "SET" : command instanceof UseCommandSyntax ? "USE"
                : command instanceof ResourceCommandSyntax ? ((ResourceCommandSyntax) command).getKind().name() + "_RESOURCE" : command instanceof SourceCommandSyntax ? "SOURCE"
                : command instanceof FunctionCommandSyntax ? (((FunctionCommandSyntax) command).isDrop() ? "DROP_" : "CREATE_")
                    + (((FunctionCommandSyntax) command).isTemporary() ? "TEMPORARY_" : "") + "FUNCTION" : "COMMAND";
            return new StatementClassification(type, command instanceof UseCommandSyntax ? "USE_DATABASE" : command instanceof SetCommandSyntax ? "SESSION_SET" : type, false);
        }
        if (syntax instanceof OtherStatementSyntax) {
            OtherStatementSyntax other = (OtherStatementSyntax) syntax;
            if ("USE".equals(other.getClassification())) {
                return new StatementClassification("USE", null, true);
            }
            if (other.getUnsupportedReason() == null || "INVALID_DDL".equals(other.getUnsupportedReason())) {
                String kind = other.getClassification();
                String type = "TOK_CREATEDATABASE".equals(kind) ? "CREATE_DATABASE" : "TOK_CREATEMACRO".equals(kind) ? "CREATE_TEMPORARY_MACRO"
                    : "TOK_DROPDATABASE".equals(kind) ? "DROP_DATABASE" : "TOK_DROPINDEX".equals(kind) ? "DROP_INDEX"
                    : "TOK_CREATEINDEX".equals(kind) ? "CREATE_INDEX" : "TOK_ALTERTABLE".equals(kind) ? "ALTER_TABLE" : kind;
                return new StatementClassification(type, type.startsWith("CREATE_") && !type.equals("CREATE_TEMPORARY_MACRO") ? type + "_DDL" : type, false);
            }
        }
        return new StatementClassification(syntax.getKind().name(), null, true);
    }
}
