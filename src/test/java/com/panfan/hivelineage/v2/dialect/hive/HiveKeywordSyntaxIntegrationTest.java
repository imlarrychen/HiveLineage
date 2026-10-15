package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.v2.analysis.Coverage;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.api.StatementStatus;
import com.panfan.hivelineage.v2.compat.HiveScriptLineageAdapter;
import com.panfan.hivelineage.v2.frontend.StageOutcome;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.syntax.BetweenSyntax;
import com.panfan.hivelineage.v2.syntax.BinaryOperatorSyntax;
import com.panfan.hivelineage.v2.syntax.BinarySyntax;
import com.panfan.hivelineage.v2.syntax.CallSyntax;
import com.panfan.hivelineage.v2.syntax.CaseSyntax;
import com.panfan.hivelineage.v2.syntax.CastSyntax;
import com.panfan.hivelineage.v2.syntax.CommandStatementSyntax;
import com.panfan.hivelineage.v2.syntax.CommandSyntax;
import com.panfan.hivelineage.v2.syntax.DerivedFromSyntax;
import com.panfan.hivelineage.v2.syntax.InListSyntax;
import com.panfan.hivelineage.v2.syntax.InsertStatementSyntax;
import com.panfan.hivelineage.v2.syntax.JoinFromSyntax;
import com.panfan.hivelineage.v2.syntax.LiteralKind;
import com.panfan.hivelineage.v2.syntax.LiteralSyntax;
import com.panfan.hivelineage.v2.syntax.NamedFromSyntax;
import com.panfan.hivelineage.v2.syntax.QuantifierSyntax;
import com.panfan.hivelineage.v2.syntax.QueryInsertSourceSyntax;
import com.panfan.hivelineage.v2.syntax.QuoteKind;
import com.panfan.hivelineage.v2.syntax.ResourceCommandKindSyntax;
import com.panfan.hivelineage.v2.syntax.ResourceCommandSyntax;
import com.panfan.hivelineage.v2.syntax.SampleKindSyntax;
import com.panfan.hivelineage.v2.syntax.SelectItemSyntax;
import com.panfan.hivelineage.v2.syntax.SelectQuerySyntax;
import com.panfan.hivelineage.v2.syntax.SetCommandSyntax;
import com.panfan.hivelineage.v2.syntax.SetOperatorSyntax;
import com.panfan.hivelineage.v2.syntax.SetQuerySyntax;
import com.panfan.hivelineage.v2.syntax.SubqueryExpressionSyntax;
import com.panfan.hivelineage.v2.syntax.SubqueryKindSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxQuery;
import com.panfan.hivelineage.v2.syntax.SyntaxResult;
import com.panfan.hivelineage.v2.syntax.TableSampleSyntax;
import com.panfan.hivelineage.v2.syntax.UnaryOperatorSyntax;
import com.panfan.hivelineage.v2.syntax.UnarySyntax;
import com.panfan.hivelineage.v2.syntax.UnknownExpressionSyntax;
import com.panfan.hivelineage.v2.syntax.UseCommandSyntax;
import com.panfan.hivelineage.v2.syntax.WindowCallSyntax;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.analyze;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertColumnSources;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertInputs;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertNoFailed;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertTableSources;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.project;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.readSql;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.statement;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.syntax;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Remaining keyword branches are verified as syntax, bound structure and physical lineage.
 */
@RunWith(Parameterized.class)
public class HiveKeywordSyntaxIntegrationTest {
    private static final String PREFIX = "v2_keyword_";
    private static final List<String> SUFFIXES = Arrays.asList(
            "nested_group_by_scalar_subquery", "nested_order_by_scalar_subquery", "nested_sort_by_scalar_subquery",
            "nested_distribute_by_scalar_subquery", "nested_cluster_by_scalar_subquery", "not_exists_correlated",
            "literal_boolean_datetime_adjacent_strings", "interval_internal_function_boundary",
            "remaining_unary_comparison_operators", "window_named_inheritance_following_frames",
            "window_unknown_and_recursive_boundaries", "select_all_default_set_quantifiers",
            "join_using_partial_boundary", "session_queries_quoted_use_resource_kinds",
            "sample_bucket_percent_rows_byte_length", "cast_primitive_type_parameters");
    private final String file;
    private final String suffix;
    private final LineageParseMode mode;

    public HiveKeywordSyntaxIntegrationTest(String file, LineageParseMode mode) {
        this.file = file;
        this.suffix = file.substring(PREFIX.length(), file.length() - 4);
        this.mode = mode;
    }

    public static Set<String> scenarioFiles() {
        return SUFFIXES.stream().map(suffix -> PREFIX + suffix + ".sql").collect(Collectors.toCollection(TreeSet::new));
    }

    public static java.util.Map<String, Set<Integer>> expectedFrontendPartial() {
        java.util.Map<String, Set<Integer>> partial = new java.util.LinkedHashMap<>();
        partial.put(PREFIX + "session_queries_quoted_use_resource_kinds.sql", Collections.singleton(13));
        partial.put(PREFIX + "join_using_partial_boundary.sql", Collections.singleton(0));
        return partial;
    }

    @Parameterized.Parameters(name = "{0}: {1}")
    public static Collection<Object[]> cases() {
        List<Object[]> cases = new ArrayList<>();
        for (String file : scenarioFiles()) {
            for (LineageParseMode mode : LineageParseMode.values()) {
                cases.add(new Object[]{file, mode});
            }
        }
        return cases;
    }

    @Test
    public void fileBackedKeywordGrammarPreservesStructureAndBoundaries() {
        List<SyntaxResult> syntax = syntax(file);
        ScriptAnalysisResult result = analyze(file, mode);
        if (suffix.equals("session_queries_quoted_use_resource_kinds")) {
            assertEquals(1, result.getCounts().getFailed());
            assertEquals(StatementStatus.FAILED, result.getStatements().get(13).getStatus());
        } else {
            assertNoFailed(result);
        }
        assertEquals(file, syntax.size(), result.getCounts().getTotal());
        for (int index = 0; index < syntax.size(); index++) {
            StageOutcome expected = expectedFrontendPartial().getOrDefault(file, Collections.emptySet()).contains(index)
                    ? StageOutcome.PARTIAL : StageOutcome.SUCCESS;
            assertEquals(file + ": " + syntax.get(index).getDiagnostics(), expected, syntax.get(index).getOutcome());
        }
        if (suffix.startsWith("nested_")) {
            clauseSubquery(result);
        } else {
            switch (suffix) {
                case "not_exists_correlated":
                    notExists(result, syntax);
                    break;
                case "literal_boolean_datetime_adjacent_strings":
                    literals(result, syntax);
                    break;
                case "interval_internal_function_boundary":
                    intervals(result, syntax);
                    break;
                case "remaining_unary_comparison_operators":
                    operators(result, syntax);
                    break;
                case "window_named_inheritance_following_frames":
                    windows(result, syntax);
                    break;
                case "window_unknown_and_recursive_boundaries":
                    windowBoundaries(result);
                    break;
                case "select_all_default_set_quantifiers":
                    quantifiers(result, syntax);
                    break;
                case "join_using_partial_boundary":
                    usingBoundary(result, syntax);
                    break;
                case "session_queries_quoted_use_resource_kinds":
                    commands(result, syntax);
                    break;
                case "sample_bucket_percent_rows_byte_length":
                    samples(result, syntax);
                    break;
                case "cast_primitive_type_parameters":
                    casts(result, syntax);
                    break;
                default:
                    fail("Unreviewed keyword scenario: " + suffix);
            }
        }
    }

    private void clauseSubquery(ScriptAnalysisResult result) {
        successfulWrite(result, 0, new String[]{"ods.users", "ods.events"}, new String[][]{{"id", "ods.users.id"}});
        String clause = suffix.substring("nested_".length(), suffix.length() - "_scalar_subquery".length());
        BoundAnalysisUnit unit = bound(result, 0);
        assertTrue(unit.getQueryBlocks().values().stream().anyMatch(block -> block.getPath().startsWith("root." + clause + ".scalar[")));
        assertEquals(1, unit.getWrites().size());
        if (mode == LineageParseMode.TABLE_ONLY) {
            // Object-only subquery traversal must not demand metastore schemas or projection fields.
            ScriptAnalysisResult withoutMetadata = new HiveScriptLineageAdapter().analyzeNative(LineageAnalyzeRequest.builder()
                    .workflowId("wf_keyword_object_only").taskId(file).taskVersion("V1").defaultDatabase("default")
                    .rawScript(readSql(file)).metadataProvider(table -> {
                        throw new AssertionError("Unneeded metadata: " + table.getQualifiedName());
                    })
                    .options(LineageAnalyzeOptions.builder().parseMode(mode).includeIgnoredStatements(true)
                            .parseJoinConditions(false).expandViewLineage(false).build()).build());
            successfulWrite(withoutMetadata, 0, new String[]{"ods.users", "ods.events"}, new String[][]{{"id", "ods.users.id"}});
            bound(withoutMetadata, 0).getRelations().stream().filter(relation -> relation instanceof Relations.Scan)
                    .forEach(scan -> assertEquals(SchemaState.NOT_REQUESTED, scan.getOutputSchema().getState()));
        } else if (!clause.equals("distribute_by") && !clause.equals("cluster_by")) {
            diagnostic(result, "SCALAR_CARDINALITY_UNKNOWN");
        }
    }

    private void notExists(ScriptAnalysisResult result, List<SyntaxResult> syntax) {
        successfulWrite(result, 0, new String[]{"ods.users", "ods.events"}, new String[][]{{"id", "ods.users.id"}});
        UnarySyntax predicate = (UnarySyntax) select(syntax, 0).getBody().getClauses().getWhere();
        assertEquals(UnaryOperatorSyntax.NOT, predicate.getOperator());
        assertEquals(SubqueryKindSyntax.EXISTS, ((SubqueryExpressionSyntax) predicate.getOperand()).getSubqueryKind());
        assertEquals(com.panfan.hivelineage.v2.ir.statement.BoundWrite.WriteMode.OVERWRITE, bound(result, 0).getWrites().get(0).getWriteMode());
        if (mode != LineageParseMode.TABLE_ONLY) {
            Set<String> calls = calls(bound(result, 0));
            assertTrue(calls.toString(), calls.contains("not"));
            assertTrue(bound(result, 0).getRelations().stream().filter(relation -> relation instanceof Relations.Filter)
                    .map(relation -> ((Relations.Filter) relation).getPredicate()).anyMatch(expression -> contains(expression, Expressions.ExistsSubquery.class)));
        }
    }

    private void literals(ScriptAnalysisResult result, List<SyntaxResult> syntax) {
        successfulWrite(result, 0, new String[]{"ods.users"}, constantMappings());
        List<SelectItemSyntax> items = select(syntax, 0).getBody().getSelectItems();
        LiteralKind[] kinds = {LiteralKind.BOOLEAN, LiteralKind.STRING, LiteralKind.BOOLEAN, LiteralKind.DATE,
                LiteralKind.TIMESTAMP, LiteralKind.NUMBER, LiteralKind.NULL};
        for (int index = 0; index < kinds.length; index++) {
            assertEquals(kinds[index], ((LiteralSyntax) items.get(index).getExpression()).getLiteralKind());
        }
        assertEquals("firstsecond", ((LiteralSyntax) items.get(1).getExpression()).getDecodedValue());
        if (mode != LineageParseMode.TABLE_ONLY) {
            statement(result, 0).getColumnLineages().forEach(column -> assertEquals(LineageType.CONSTANT, column.getLineageType()));
            List<Expressions.Literal> values = projected(bound(result, 0)).stream().map(expression -> (Expressions.Literal) expression).collect(Collectors.toList());
            assertEquals(Arrays.asList(TypeRef.Kind.BOOLEAN, TypeRef.Kind.STRING, TypeRef.Kind.BOOLEAN, TypeRef.Kind.DATE,
                    TypeRef.Kind.TIMESTAMP, TypeRef.Kind.INTEGER, TypeRef.Kind.NULL), values.stream().map(value -> value.getType().getKind()).collect(Collectors.toList()));
            assertEquals("firstsecond", values.get(1).getValue().getValue().get());
        }
    }

    private void intervals(ScriptAnalysisResult result, List<SyntaxResult> syntax) {
        successfulWrite(result, 0, new String[]{"ods.users"}, new String[][]{{"tag"}, {"dt"}});
        for (SelectItemSyntax item : select(syntax, 0).getBody().getSelectItems()) {
            CallSyntax interval = (CallSyntax) item.getExpression();
            assertEquals("internal_interval", interval.getFunctionName().getParts().get(0).getValue());
            assertEquals(2, interval.getArguments().getArguments().size());
        }
        if (mode != LineageParseMode.TABLE_ONLY) {
            diagnostic(result, "OPAQUE_FUNCTION");
            assertEquals(Coverage.PARTIAL, result.getStatements().get(0).getLineage().get().getQuality().getCoverage());
        }
    }

    private void operators(ScriptAnalysisResult result, List<SyntaxResult> syntax) {
        String[][] mappings = constantMappings();
        for (int index = 0; index < mappings.length; index++) {
            mappings[index] = new String[]{mappings[index][0], "ods.users.id"};
        }
        successfulWrite(result, 0, new String[]{"ods.users"}, mappings);
        List<SelectItemSyntax> items = select(syntax, 0).getBody().getSelectItems();
        assertEquals(UnaryOperatorSyntax.PLUS, ((UnarySyntax) items.get(0).getExpression()).getOperator());
        assertEquals(UnaryOperatorSyntax.BITWISE_NOT, ((UnarySyntax) items.get(1).getExpression()).getOperator());
        assertEquals(BinaryOperatorSyntax.DIVIDE, ((BinarySyntax) items.get(2).getExpression()).getOperator());
        BinarySyntax notEqual = (BinarySyntax) items.get(3).getExpression();
        assertEquals(BinaryOperatorSyntax.OR, notEqual.getOperator());
        assertEquals(BinaryOperatorSyntax.NOT_EQUAL, ((BinarySyntax) notEqual.getLeft()).getOperator());
        assertEquals(BinaryOperatorSyntax.NOT_EQUAL, ((BinarySyntax) notEqual.getRight()).getOperator());
        BinarySyntax comparisons = (BinarySyntax) items.get(4).getExpression();
        assertEquals(BinaryOperatorSyntax.LESS_THAN, ((BinarySyntax) comparisons.getLeft()).getOperator());
        assertEquals(BinaryOperatorSyntax.LESS_OR_EQUAL, ((BinarySyntax) comparisons.getRight()).getOperator());
        CaseSyntax cases = (CaseSyntax) items.get(5).getExpression();
        assertNull(cases.getElseExpression());
        BinarySyntax condition = (BinarySyntax) cases.getBranches().get(0).getWhenExpression();
        assertEquals(UnaryOperatorSyntax.IS_NULL, ((UnarySyntax) condition.getLeft()).getOperator());
        UnarySyntax notIn = (UnarySyntax) condition.getRight();
        assertEquals(UnaryOperatorSyntax.NOT, notIn.getOperator());
        assertTrue(notIn.getOperand() instanceof InListSyntax);
        assertFalse(((BetweenSyntax) items.get(6).getExpression()).isNegated());
        if (mode != LineageParseMode.TABLE_ONLY) {
            assertTrue(calls(bound(result, 0)).containsAll(Arrays.asList("plus", "bitwise_not", "divide", "not_equal", "less_than",
                    "less_or_equal", "is_null", "not", "in", "between")));
        }
    }

    private void windows(ScriptAnalysisResult result, List<SyntaxResult> syntax) {
        assertEquals(5, result.getCounts().getTotal());
        for (int index = 0; index < 5; index++) {
            String[] inputs = index == 4 ? new String[]{"ods.users", "ods.events"} : new String[]{"ods.users"};
            successfulWrite(result, index, inputs, new String[][]{{"id", "ods.users.id"},
                    index == 4 ? new String[]{"amount", "ods.users.amount", "ods.events.user_id"}
                            : new String[]{"amount", "ods.users.amount", "ods.users.dept_id"}});
            if (mode == LineageParseMode.TABLE_ONLY) {
                continue;
            }
            Relations.WindowComputation computation = bound(result, index).getRelations().stream()
                    .filter(relation -> relation instanceof Relations.Window).map(relation -> (Relations.Window) relation)
                    .flatMap(window -> window.getComputations().stream()).findFirst().get();
            assertEquals(1, computation.getPartitions().size());
            assertEquals(1, computation.getOrderKeys().size());
            Relations.WindowFrame frame = computation.getFrame().get();
            assertEquals(index == 3 ? Relations.FrameUnit.RANGE : Relations.FrameUnit.ROWS, frame.getUnit());
            if (index == 0 || index == 3) {
                assertEquals(Relations.FrameBoundKind.CURRENT_ROW, frame.getStart().getKind());
                assertEquals(Relations.FrameBoundKind.UNBOUNDED_FOLLOWING, frame.getEnd().getKind());
            } else if (index == 1) {
                assertEquals(Relations.FrameBoundKind.FOLLOWING, frame.getStart().getKind());
                assertEquals(Relations.FrameBoundKind.FOLLOWING, frame.getEnd().getKind());
                assertEquals("1", ((Expressions.Literal) frame.getStart().getOffset().get()).getValue().getValue().get());
                assertEquals("3", ((Expressions.Literal) frame.getEnd().getOffset().get()).getValue().getValue().get());
            } else if (index == 2) {
                assertEquals(Relations.FrameBoundKind.PRECEDING, frame.getStart().getKind());
                assertEquals(Relations.FrameBoundKind.CURRENT_ROW, frame.getEnd().getKind());
                assertNull(((WindowCallSyntax) select(syntax, index).getBody().getSelectItems().get(1).getExpression()).getWindow().getFrame().getEnd());
            } else {
                assertEquals(Relations.FrameBoundKind.UNBOUNDED_PRECEDING, frame.getStart().getKind());
                assertEquals(Relations.FrameBoundKind.CURRENT_ROW, frame.getEnd().getKind());
                diagnostic(result, "SCALAR_CARDINALITY_UNKNOWN");
            }
        }
    }

    private void windowBoundaries(ScriptAnalysisResult result) {
        assertEquals(2, result.getCounts().getTotal());
        diagnostic(result, "UNKNOWN_WINDOW");
        diagnostic(result, "RECURSIVE_WINDOW_DEFINITION");
        for (int index = 0; index < 2; index++) {
            assertEquals(StatementStatus.PARTIAL_SUCCESS, result.getStatements().get(index).getStatus());
            assertInputs(statement(result, index), "ods.users");
            assertTableSources(statement(result, index), "dwd.result", "ods.users");
            assertColumnSources(statement(result, index), "dwd.result.id", "ods.users.id");
            assertColumnSources(statement(result, index), "dwd.result.amount",
                    index == 0 ? new String[]{"ods.users.amount"} : new String[]{"ods.users.amount", "ods.users.dept_id"});
        }
    }

    private void quantifiers(ScriptAnalysisResult result, List<SyntaxResult> syntax) {
        assertEquals(QuantifierSyntax.DEFAULT, select(syntax, 0).getBody().getQuantifier());
        successfulWrite(result, 0, new String[]{"ods.users"}, new String[][]{{"id", "ods.users.id"}});
        for (int index = 1; index < 4; index++) {
            successfulWrite(result, index, new String[]{"ods.users", "ods.users_archive"},
                    new String[][]{{"id", "ods.users.id", "ods.users_archive.id"}});
            SetQuerySyntax set = setQuery(select(syntax, index));
            SetOperatorSyntax expected = index == 1 ? SetOperatorSyntax.INTERSECT : index == 2 ? SetOperatorSyntax.EXCEPT : SetOperatorSyntax.UNION;
            assertEquals(expected, set.getBody().getOperator());
            assertEquals(QuantifierSyntax.DISTINCT, set.getBody().getQuantifier());
            Relations.SetOp relation = (Relations.SetOp) bound(result, index).getRelations().stream()
                    .filter(value -> value instanceof Relations.SetOp).findFirst().get();
            assertEquals(Relations.SetOperator.valueOf(expected.name()), relation.getOperator());
            assertEquals(Relations.DuplicatePolicy.DISTINCT, relation.getDuplicatePolicy());
        }
    }

    private void usingBoundary(ScriptAnalysisResult result, List<SyntaxResult> syntax) {
        assertEquals(StatementStatus.PARTIAL_SUCCESS, result.getStatements().get(0).getStatus());
        diagnostic(result, "UNSUPPORTED_EXPRESSION");
        diagnostic(result, "UNSUPPORTED_NATIVE_EXPRESSION");
        assertInputs(statement(result, 0), "ods.users", "ods.users_archive");
        assertTableSources(statement(result, 0), "dwd.result", "ods.users", "ods.users_archive");
        assertColumnSources(statement(result, 0), "dwd.result.id", "ods.users.id");
        JoinFromSyntax from = (JoinFromSyntax) select(syntax, 0).getBody().getFrom();
        assertTrue(from.getCondition().getOn() instanceof UnknownExpressionSyntax);
        assertEquals("UNSUPPORTED_NATIVE_EXPRESSION:TOK_TABCOLNAME", ((UnknownExpressionSyntax) from.getCondition().getOn()).getReason());
        assertEquals(1, ((UnknownExpressionSyntax) from.getCondition().getOn()).getKnownChildren().size());
    }

    private void commands(ScriptAnalysisResult result, List<SyntaxResult> syntax) {
        assertEquals(15, result.getCounts().getTotal());
        SetCommandSyntax plain = (SetCommandSyntax) command(syntax, 0);
        assertEquals("", plain.getKey());
        assertNull(plain.getValue());
        SetCommandSyntax query = (SetCommandSyntax) command(syntax, 1);
        assertEquals("hiveconf", query.getNamespace());
        assertEquals("keyword_mode", query.getKey());
        assertNull(query.getValue());
        assertEquals("active", ((SetCommandSyntax) command(syntax, 2)).getValue());
        assertEquals(QuoteKind.BACKTICK, ((UseCommandSyntax) command(syntax, 3)).getDatabase().getParts().get(0).getQuoteKind());
        String[] types = {"JAR", "FILE", "ARCHIVE"};
        for (int index = 4; index < 13; index++) {
            ResourceCommandSyntax resource = (ResourceCommandSyntax) command(syntax, index);
            assertEquals(types[(index - 4) % 3], resource.getResourceType().toUpperCase(java.util.Locale.ROOT));
            assertEquals(index < 7 ? ResourceCommandKindSyntax.ADD : index < 10 ? ResourceCommandKindSyntax.LIST : ResourceCommandKindSyntax.DELETE, resource.getKind());
            assertEquals(index >= 7 && index < 10 ? 0 : 1, resource.getResources().size());
        }
        assertEquals(StageOutcome.PARTIAL, syntax.get(13).getOutcome());
        diagnostic(result, "INVALID_USE_DATABASE");
        for (int index = 0; index < 14; index++) {
            assertFalse(result.getStatements().get(index).isFormalLineageProduced());
            assertTrue(statement(result, index).getTableLineages().isEmpty());
            assertTrue(statement(result, index).getColumnLineages().isEmpty());
            if (index < 13) {
                assertEquals(StatementStatus.IGNORED, result.getStatements().get(index).getStatus());
            }
        }
        successfulWrite(result, 14, new String[]{"ods.users"}, new String[][]{{"id", "ods.users.id"}});
    }

    private void samples(ScriptAnalysisResult result, List<SyntaxResult> syntax) {
        SampleKindSyntax[] kinds = {SampleKindSyntax.BUCKET, SampleKindSyntax.PERCENT, SampleKindSyntax.ROWS, SampleKindSyntax.BYTE_LENGTH};
        for (int index = 0; index < kinds.length; index++) {
            successfulWrite(result, index, new String[]{"ods.users"}, new String[][]{{"id", "ods.users.id"}});
            TableSampleSyntax sample = ((NamedFromSyntax) select(syntax, index).getBody().getFrom()).getSample();
            assertEquals(kinds[index], sample.getKind());
            assertEquals(index == 0 ? 3 : 1, sample.getArguments().size());
            assertTrue(sample.getArguments().get(0) instanceof LiteralSyntax);
            String spelling = ((LiteralSyntax) sample.getArguments().get(0)).getLexeme();
            assertEquals(index == 0 ? "1" : index == 1 ? "10" : index == 2 ? "4" : "100B", spelling);
        }
    }

    private void casts(ScriptAnalysisResult result, List<SyntaxResult> syntax) {
        String[][] names = {{"tinyint", "smallint", "int", "bigint", "float", "double", "decimal"},
                {"boolean", "string", "char", "varchar", "binary", "date", "timestamp"}};
        String[][] sources = {{"id", "id", "id", "id", "amount", "amount", "amount"},
                {"id", "name", "name", "name", "name", "dt", "dt"}};
        for (int index = 0; index < 2; index++) {
            String[][] mappings = constantMappings();
            List<SelectItemSyntax> items = select(syntax, index).getBody().getSelectItems();
            for (int column = 0; column < 7; column++) {
                mappings[column] = new String[]{mappings[column][0], "ods.users." + sources[index][column]};
                CastSyntax cast = (CastSyntax) items.get(column).getExpression();
                assertEquals(names[index][column], cast.getTargetType().getTypeName().getParts().get(0).getValue());
                List<Integer> numeric = index == 0 && column == 6 ? Arrays.asList(12, 2)
                        : index == 1 && column == 2 ? Collections.singletonList(8)
                          : index == 1 && column == 3 ? Collections.singletonList(32) : Collections.emptyList();
                assertEquals(numeric, cast.getTargetType().getNumericArguments());
            }
            successfulWrite(result, index, new String[]{"ods.users"}, mappings);
            if (mode != LineageParseMode.TABLE_ONLY) {
                assertTrue(projected(bound(result, index)).stream().allMatch(value -> value instanceof Expressions.Cast));
            }
        }
    }

    private void successfulWrite(ScriptAnalysisResult result, int index, String[] inputs, String[][] mappings) {
        assertEquals(file + ": " + result.getDiagnostics().getDiagnostics(), StatementStatus.SUCCESS, result.getStatements().get(index).getStatus());
        StatementLineageResult statement = statement(result, index);
        assertTrue(statement.isFormalLineageProduced());
        assertEquals("dwd.result", statement.getOutputObject().getQualifiedName());
        assertInputs(statement, inputs);
        assertTableSources(statement, "dwd.result", inputs);
        for (String[] mapping : mappings) {
            assertColumnSources(statement, "dwd.result." + mapping[0], Arrays.copyOfRange(mapping, 1, mapping.length));
        }
        assertEquals(mode == LineageParseMode.TABLE_ONLY ? 0 : mappings.length, statement.getColumnLineages().size());
    }

    private static String[][] constantMappings() {
        return new String[][]{{"id"}, {"name"}, {"amount"}, {"dept_name"}, {"tag"}, {"rn"}, {"dt"}};
    }

    private static BoundAnalysisUnit bound(ScriptAnalysisResult result, int index) {
        return result.getStatements().get(index).getBoundUnit().get();
    }

    private static SelectQuerySyntax select(List<SyntaxResult> syntax, int index) {
        InsertStatementSyntax statement = (InsertStatementSyntax) syntax.get(index).getStatement();
        return (SelectQuerySyntax) ((QueryInsertSourceSyntax) statement.getBranches().get(0).getSource()).getQuery();
    }

    private static CommandSyntax command(List<SyntaxResult> syntax, int index) {
        return ((CommandStatementSyntax) syntax.get(index).getStatement()).getCommand();
    }

    private static SetQuerySyntax setQuery(SelectQuerySyntax query) {
        SyntaxQuery nested = ((DerivedFromSyntax) query.getBody().getFrom()).getQuery();
        return nested instanceof SetQuerySyntax ? (SetQuerySyntax) nested : setQuery((SelectQuerySyntax) nested);
    }

    private static void diagnostic(ScriptAnalysisResult result, String code) {
        assertTrue(code + " in " + result.getDiagnostics().getDiagnostics(), result.getDiagnostics().getDiagnostics().stream()
                .anyMatch(diagnostic -> code.equals(diagnostic.getCode())));
    }

    private static List<BoundExpression> projected(BoundAnalysisUnit unit) {
        return unit.getRelations().stream().filter(relation -> relation instanceof Relations.Project)
                .map(relation -> (Relations.Project) relation).flatMap(project -> project.getAssignments().stream())
                .map(OutputAssignment::getExpression).collect(Collectors.toList());
    }

    private static Set<String> calls(BoundAnalysisUnit unit) {
        Set<String> calls = new LinkedHashSet<>();
        for (BoundRelation relation : unit.getRelations()) {
            for (BoundExpression expression : IrTraversal.expressions(relation)) {
                collectCalls(expression, calls);
            }
        }
        return calls;
    }

    private static void collectCalls(BoundExpression expression, Set<String> calls) {
        if (expression instanceof Expressions.Call) {
            calls.add(((Expressions.Call) expression).getFunction().getName().getDisplayName());
        }
        for (BoundExpression child : IrTraversal.children(expression)) {
            collectCalls(child, calls);
        }
    }

    private static boolean contains(BoundExpression expression, Class<?> kind) {
        if (kind.isInstance(expression)) {
            return true;
        }
        return IrTraversal.children(expression).stream().anyMatch(child -> contains(child, kind));
    }
}
