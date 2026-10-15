package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.syntax.CommandStatementSyntax;
import com.panfan.hivelineage.v2.syntax.CommandSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionBodySyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionKindSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionStatementSyntax;
import com.panfan.hivelineage.v2.syntax.FunctionCommandSyntax;
import com.panfan.hivelineage.v2.syntax.InsertModeSyntax;
import com.panfan.hivelineage.v2.syntax.InsertStatementSyntax;
import com.panfan.hivelineage.v2.syntax.InsertTargetKindSyntax;
import com.panfan.hivelineage.v2.syntax.OtherStatementSyntax;
import com.panfan.hivelineage.v2.syntax.QueryStatementSyntax;
import com.panfan.hivelineage.v2.syntax.ResourceCommandSyntax;
import com.panfan.hivelineage.v2.syntax.SetCommandSyntax;
import com.panfan.hivelineage.v2.syntax.SourceCommandSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;
import com.panfan.hivelineage.v2.syntax.UseCommandSyntax;
import com.panfan.hivelineage.v2.syntax.ValuesInsertSourceSyntax;

/**
 * Publication names are semantic values supplied by syntax, never parser token names.
 */
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
            return new StatementClassification("SELECT", "PURE_QUERY", detailed(contract));
        }
        if (syntax instanceof InsertStatementSyntax) {
            return insert((InsertStatementSyntax) syntax, contract);
        }
        if (syntax instanceof DefinitionStatementSyntax) {
            return definition(((DefinitionStatementSyntax) syntax).getDefinition(), contract);
        }
        if (syntax instanceof CommandStatementSyntax) {
            return command(((CommandStatementSyntax) syntax).getCommand());
        }
        if (syntax instanceof OtherStatementSyntax) {
            return other((OtherStatementSyntax) syntax);
        }
        return new StatementClassification(syntax.getKind().name(), null, true);
    }

    private static boolean detailed(AnalysisContract contract) {
        return contract.getPublicationPolicy() == StatementPublicationPolicy.DETAILED;
    }

    private static StatementClassification insert(InsertStatementSyntax insert, AnalysisContract contract) {
        if (!insert.getBranches().isEmpty()) {
            boolean values = insert.getBranches().stream().allMatch(branch -> branch.getSource() instanceof ValuesInsertSourceSyntax);
            boolean directory = insert.getBranches().stream().allMatch(branch -> branch.getTarget().getKind() != InsertTargetKindSyntax.TABLE);
            if (values) {
                return new StatementClassification("INSERT_VALUES", "INSERT_VALUES", !directory && detailed(contract));
            }
            if (directory) {
                return new StatementClassification("INSERT_DIRECTORY", "NO_OUTPUT_TABLE", false);
            }
            if (insert.getBranches().get(0).getMode() == InsertModeSyntax.OVERWRITE) {
                return new StatementClassification("INSERT_OVERWRITE", null, true);
            }
        }
        return new StatementClassification("INSERT_INTO", null, true);
    }

    private static StatementClassification definition(DefinitionBodySyntax definition, AnalysisContract contract) {
        boolean temporary = definition.getOptions().isTemporary();
        if (definition.getQuery() != null) {
            String type = queryDefinitionType(definition);
            boolean publish = !temporary || contract.isIncludeTemporaryLineage();
            return new StatementClassification(type, publish ? null : "TEMPORARY_LINEAGE_DISABLED", publish);
        }
        if (definition.getKind() == DefinitionKindSyntax.CREATE_TABLE) {
            String type = "CREATE_TABLE";
            if (temporary) {
                type = "CREATE_TEMPORARY_TABLE";
            } else if (definition.getOptions().isExternal()) {
                type = "CREATE_EXTERNAL_TABLE";
            }
            return new StatementClassification(type, type + "_DDL", false);
        }
        String type = definition.getKind().name();
        return new StatementClassification(type, type, false);
    }

    private static String queryDefinitionType(DefinitionBodySyntax definition) {
        if (definition.getKind() == DefinitionKindSyntax.CREATE_TABLE) {
            return definition.getOptions().isTemporary() ? "CREATE_TEMPORARY_TABLE_AS_SELECT" : "CREATE_TABLE_AS_SELECT";
        }
        return definition.getKind() == DefinitionKindSyntax.ALTER_VIEW ? "ALTER_VIEW_AS_SELECT" : "CREATE_VIEW_AS_SELECT";
    }

    private static StatementClassification command(CommandSyntax command) {
        if (command instanceof UseCommandSyntax) {
            return new StatementClassification("USE", "USE_DATABASE", false);
        }
        if (command instanceof SetCommandSyntax) {
            return new StatementClassification("SET", "SESSION_SET", false);
        }
        String type = "COMMAND";
        if (command instanceof ResourceCommandSyntax) {
            type = ((ResourceCommandSyntax) command).getKind().name() + "_RESOURCE";
        } else if (command instanceof SourceCommandSyntax) {
            type = "SOURCE";
        } else if (command instanceof FunctionCommandSyntax) {
            FunctionCommandSyntax function = (FunctionCommandSyntax) command;
            type = function.isDrop() ? "DROP_" : "CREATE_";
            if (function.isTemporary()) {
                type += "TEMPORARY_";
            }
            type += "FUNCTION";
        }
        return new StatementClassification(type, type, false);
    }

    private static StatementClassification other(OtherStatementSyntax other) {
        String type = other.getClassification();
        if (other.isDataChanging()) {
            return new StatementClassification(type, null, true);
        }
        String reason = type;
        if (type.startsWith("CREATE_") && !type.equals("CREATE_TEMPORARY_MACRO")) {
            reason += "_DDL";
        }
        return new StatementClassification(type, reason, false);
    }
}
