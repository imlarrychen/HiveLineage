package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.frontend.StageOutcome;
import com.panfan.hivelineage.v2.lexical.LexedUnit;
import com.panfan.hivelineage.v2.syntax.ParseResult;
import com.panfan.hivelineage.v2.syntax.SqlSyntaxParser;
import com.panfan.hivelineage.v2.syntax.SyntaxOptions;
import com.panfan.hivelineage.v2.syntax.OtherStatementSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;
import org.apache.hadoop.hive.ql.parse.ParseDriver;

public final class HiveSqlSyntaxParser implements SqlSyntaxParser<HiveParseArtifact> {
    @Override
    public ParseResult<HiveParseArtifact> parse(LexedUnit unit, SyntaxOptions options) {
        Optional<Diagnostic> limit = new HiveParserResourceGuard().check(unit, options);
        if (limit.isPresent()) {
            return new ParseResult<HiveParseArtifact>(null, Collections.singletonList(limit.get()), StageOutcome.FAILED);
        }
        SyntaxStatement command = new HiveCommandParser().parse(unit);
        if (command != null) {
            if (command instanceof OtherStatementSyntax
                    && "INVALID_USE_DATABASE".equals(((OtherStatementSyntax) command).getUnsupportedReason())) {
                List<Diagnostic> diagnostics = Collections.singletonList(new Diagnostic(AnalysisStage.SYNTAX,
                        "INVALID_USE_DATABASE", "USE requires one unquoted or backtick-quoted database identifier", command.getSourceRef()));
                return new ParseResult<HiveParseArtifact>(new HiveParseArtifact(unit, null, command, diagnostics),
                        diagnostics, StageOutcome.PARTIAL);
            }
            return new ParseResult<HiveParseArtifact>(new HiveParseArtifact(unit, null, command),
                    Collections.<Diagnostic>emptyList(), StageOutcome.SUCCESS);
        }
        CapturingParser parser = new CapturingParser(new CommonTokenStream(new HiveTokenBridge(unit, options.isRecoveryEnabled())));
        parser.setTreeAdaptor(ParseDriver.adaptor);
        try {
            ASTNode tree = (ASTNode) parser.statement().getTree();
            if (!parser.errors.isEmpty() || tree == null) {
                return failure(unit, parser.errors.isEmpty() ? "Hive produced no syntax tree" : parser.errors.get(0));
            }
            tree.setUnknownTokenBoundaries();
            List<Diagnostic> diagnostics = Collections.emptyList();
            if (options.isRecoveryEnabled() && !HiveGrammarExtensions.unknownProjectionTokens(unit).isEmpty()) {
                diagnostics = Collections.singletonList(new Diagnostic(AnalysisStage.SYNTAX, "RECOVERED_PROJECTION",
                        "Opaque projection syntax was preserved while parsing the remaining query structure",
                        unit.getExpandedUnit().getOriginalUnit().getSourceRef()));
            }
            return new ParseResult<HiveParseArtifact>(new HiveParseArtifact(unit, tree, null, diagnostics),
                    diagnostics, diagnostics.isEmpty() ? StageOutcome.SUCCESS : StageOutcome.PARTIAL);
        } catch (RecognitionException error) {
            return failure(unit, parser.getErrorMessage(error, HiveParser.tokenNames));
        } catch (StackOverflowError error) {
            Diagnostic diagnostic = new Diagnostic(AnalysisStage.SYNTAX, "PARSER_NESTING_LIMIT",
                    "Native parser exhausted its stack while processing nested Token structure",
                    unit.getExpandedUnit().getOriginalUnit().getSourceRef());
            return new ParseResult<HiveParseArtifact>(null, Collections.singletonList(diagnostic), StageOutcome.FAILED);
        } catch (IllegalArgumentException error) {
            return failure(unit, error.getMessage());
        }
    }

    private ParseResult<HiveParseArtifact> failure(LexedUnit unit, String message) {
        Diagnostic diagnostic = new Diagnostic(AnalysisStage.SYNTAX, "HIVE_SYNTAX_ERROR", message,
                unit.getExpandedUnit().getOriginalUnit().getSourceRef());
        OtherStatementSyntax audit = new HiveDdlAuditRecovery().recover(unit);
        if (audit != null) {
            List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
            diagnostics.add(diagnostic);
            diagnostics.add(new Diagnostic(AnalysisStage.SYNTAX, "INVALID_DDL", "Invalid non-lineage DDL: " + message,
                    unit.getExpandedUnit().getOriginalUnit().getSourceRef()));
            return new ParseResult<HiveParseArtifact>(new HiveParseArtifact(unit, null, audit, diagnostics), diagnostics,
                    StageOutcome.PARTIAL);
        }
        return new ParseResult<HiveParseArtifact>(null, Collections.singletonList(diagnostic), StageOutcome.FAILED);
    }

    private static final class CapturingParser extends HiveParser {
        private final List<String> errors = new ArrayList<String>();

        private CapturingParser(CommonTokenStream stream) {
            super(stream);
        }

        @Override
        public void displayRecognitionError(String[] tokenNames, RecognitionException error) {
            errors.add(getErrorMessage(error, tokenNames));
        }
    }
}
