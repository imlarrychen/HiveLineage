package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.testing.GeneratedSqlSupport;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Checks public compatibility markers through the real frontend, binder and projection. */
@RunWith(Parameterized.class)
public class LegacyColumnDerivationClassificationTest {
    private final LineageParseMode mode;

    public LegacyColumnDerivationClassificationTest(LineageParseMode mode) {
        this.mode = mode;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> modes() {
        return Arrays.asList(new Object[] {LineageParseMode.COLUMN_ONLY},
                new Object[] {LineageParseMode.TABLE_AND_COLUMN});
    }

    public static Set<String> scenarioFiles() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "v2_lineage_compat_alias_literal_static_partition.sql",
            "v2_lineage_compat_constant_argument_computations.sql",
            "v2_lineage_compat_nested_functions_opaque_udf.sql",
            "v2_lineage_compat_derived_cte_expression_shape.sql",
            "v2_lineage_compat_literal_union_branches.sql",
            "v2_lineage_compat_computed_union_branch.sql",
            "v2_lineage_compat_same_source_field_union.sql",
            "v2_lineage_compat_ctas_direct_computed_expansion.sql",
            "v2_lineage_compat_view_literal_boundary.sql",
            "v2_lineage_compat_view_literal_union_boundary.sql",
            "v2_lineage_compat_view_nested_function_boundary.sql",
            "v2_lineage_compat_view_count_star_constant_aggregate.sql",
            "v2_lineage_compat_merge_literal_condition.sql",
            "v2_lineage_compat_unknown_column_source.sql")));
    }

    @Test
    public void aliasesAreDirectAndLiteralAndStaticPartitionAssignmentsAreConstant() {
        StatementLineageResult statement = analyze("v2_lineage_compat_alias_literal_static_partition.sql").get(0);
        assertEquals(LineageType.DIRECT, target(statement, "dwd.t.id").getLineageType());
        assertEquals(Collections.singleton("ods.t.id"), sources(target(statement, "dwd.t.id")));
        for (String field : Arrays.asList("name", "dt")) {
            ColumnLineage column = target(statement, "dwd.t." + field);
            assertEquals(LineageType.CONSTANT, column.getLineageType());
            assertTrue(column.getSourceColumns().isEmpty());
            assertTrue(column.getFunctionNames().isEmpty());
        }
    }

    @Test
    public void constantArgumentsDoNotMakeCastFunctionsArithmeticOrCaseConstant() {
        StatementLineageResult statement = analyze("v2_lineage_compat_constant_argument_computations.sql").get(0);
        for (ColumnLineage column : statement.getColumnLineages()) {
            assertEquals(column.getExpression(), LineageType.DERIVED, column.getLineageType());
            assertTrue(column.getSourceColumns().isEmpty());
            assertTrue(column.isComplexExpression());
        }
        assertEquals(Collections.singletonList("cast"), target(statement, "dwd.t.id").getFunctionNames());
        assertEquals(Collections.singletonList("concat"), target(statement, "dwd.t.name").getFunctionNames());
    }

    @Test
    public void nestedFunctionsAndOpaqueUdfsRetainTheirNames() {
        StatementLineageResult statement = analyze("v2_lineage_compat_nested_functions_opaque_udf.sql").get(0);
        ColumnLineage nested = target(statement, "dwd.t.name");
        assertEquals(LineageType.DERIVED, nested.getLineageType());
        assertEquals(Arrays.asList("concat", "upper", "cast"), nested.getFunctionNames());
        assertEquals(new LinkedHashSet<>(Arrays.asList("ods.t.name", "ods.t.id")), sources(nested));
        ColumnLineage udf = target(statement, "dwd.t.amount");
        assertEquals(LineageType.DERIVED, udf.getLineageType());
        assertEquals(Collections.singletonList("custom_udf"), udf.getFunctionNames());
    }

    @Test
    public void derivedAndCteAliasesPreserveTheUnderlyingExpressionShape() {
        StatementLineageResult statement = analyze("v2_lineage_compat_derived_cte_expression_shape.sql").get(0);
        assertEquals(LineageType.DIRECT, target(statement, "dwd.t.id").getLineageType());
        assertEquals(LineageType.DERIVED, target(statement, "dwd.t.name").getLineageType());
        assertEquals(Collections.singletonList("upper"), target(statement, "dwd.t.name").getFunctionNames());
        assertEquals(LineageType.DERIVED, target(statement, "dwd.t.amount").getLineageType());
        assertEquals(Collections.singletonList("cast"), target(statement, "dwd.t.amount").getFunctionNames());
    }

    @Test
    public void onlyLiteralSetBranchesAreConstantAndStructuralTagsAreNotFunctionNames() {
        StatementLineageResult literals = analyze("v2_lineage_compat_literal_union_branches.sql").get(0);
        ColumnLineage literal = target(literals, "dwd.t.name");
        assertEquals(LineageType.CONSTANT, literal.getLineageType());
        assertTrue(literal.getFunctionNames().isEmpty());
        assertFalse(literal.isComplexExpression());
        ColumnLineage computed = target(analyze("v2_lineage_compat_computed_union_branch.sql").get(0), "dwd.t.name");
        assertEquals(LineageType.DERIVED, computed.getLineageType());
        assertTrue(computed.getFunctionNames().contains("upper"));
        assertTrue(computed.isComplexExpression());
    }

    @Test
    public void mergingEvenTheSameFieldThroughSetBranchesIsDerived() {
        ColumnLineage column = target(analyze("v2_lineage_compat_same_source_field_union.sql").get(0), "dwd.t.id");
        assertEquals(Collections.singleton("ods.t.id"), sources(column));
        assertEquals(LineageType.DERIVED, column.getLineageType());
    }

    @Test
    public void ctasExpansionDoesNotTurnOneDirectValueIntoTwoIndependentSources() {
        List<StatementLineageResult> statements = analyze("v2_lineage_compat_ctas_direct_computed_expansion.sql");
        ColumnLineage direct = target(statements.get(1), "dwd.t.id");
        assertEquals(new LinkedHashSet<>(Arrays.asList("ods.t.id", "dwd.created.id")), sources(direct));
        assertEquals(LineageType.DIRECT, direct.getLineageType());
        ColumnLineage computed = target(statements.get(1), "dwd.t.name");
        assertEquals(LineageType.DERIVED, computed.getLineageType());
        assertEquals(Collections.singletonList("upper"), computed.getFunctionNames());
    }

    @Test
    public void viewDefinitionConstantsAreConstantAndItsReadRemainsADirectBoundary() {
        StatementLineageResult statement = analyze("v2_lineage_compat_view_literal_boundary.sql").get(1);
        ColumnLineage definition = target(statement, "dim.literal_view.name");
        assertEquals(LineageType.CONSTANT, definition.getLineageType());
        assertTrue(definition.getSourceColumns().isEmpty());
        assertTrue(definition.getFunctionNames().isEmpty());
        ColumnLineage sink = target(statement, "dwd.t.name");
        assertEquals(LineageType.DIRECT, sink.getLineageType());
        assertEquals(Collections.singleton("dim.literal_view.name"), sources(sink));
        ColumnLineage setDefinition = target(analyze("v2_lineage_compat_view_literal_union_boundary.sql").get(1), "dim.literal_set_view.name");
        assertEquals(LineageType.CONSTANT, setDefinition.getLineageType());
        assertTrue(setDefinition.getFunctionNames().isEmpty());
        assertFalse(setDefinition.isComplexExpression());
    }

    @Test
    public void viewDefinitionFunctionsStayOnTheDefinitionContribution() {
        StatementLineageResult statement = analyze("v2_lineage_compat_view_nested_function_boundary.sql").get(1);
        ColumnLineage definition = target(statement, "dim.computed_view.name");
        assertEquals(LineageType.DERIVED, definition.getLineageType());
        assertEquals(Arrays.asList("concat", "upper", "cast"), definition.getFunctionNames());
        ColumnLineage sink = target(statement, "dwd.t.name");
        assertEquals(LineageType.DIRECT, sink.getLineageType());
        assertTrue(sink.getFunctionNames().isEmpty());
    }

    @Test
    public void countStarAndConstantArgumentAggregatesStayDerivedThroughViews() {
        List<StatementLineageResult> statements = analyze("v2_lineage_compat_view_count_star_constant_aggregate.sql");
        assertEquals(LineageType.DERIVED, target(statements.get(0), "dim.count_view.amount").getLineageType());
        assertEquals(LineageType.DERIVED, target(statements.get(0), "dim.count_view.qty").getLineageType());
        StatementLineageResult statement = statements.get(1);
        ColumnLineage count = target(statement, "dim.count_view.amount");
        assertEquals(LineageType.DERIVED, count.getLineageType());
        assertEquals(Collections.singletonList("count"), count.getFunctionNames());
        assertTrue(count.getSourceColumns().isEmpty());
        ColumnLineage sum = target(statement, "dim.count_view.qty");
        assertEquals(LineageType.DERIVED, sum.getLineageType());
        assertEquals(Collections.singletonList("sum"), sum.getFunctionNames());
    }

    @Test
    public void literalMergeAssignmentsRemainConstantDespiteMatchingConditionSources() {
        ColumnLineage column = target(analyze("v2_lineage_compat_merge_literal_condition.sql").get(0), "dwd.t.name");
        assertEquals(LineageType.CONSTANT, column.getLineageType());
        assertTrue(sources(column).contains("dwd.t.id"));
        assertTrue(sources(column).contains("ods.t.id"));
        assertTrue(column.getFunctionNames().isEmpty());
    }

    @Test
    public void unknownSourcesNeverBecomeConstant() {
        ColumnLineage column = target(analyze("v2_lineage_compat_unknown_column_source.sql").get(0), "dwd.t.id");
        assertEquals(LineageType.DERIVED, column.getLineageType());
        assertTrue(column.getSourceColumns().isEmpty());
    }

    private List<StatementLineageResult> analyze(String file) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder().workflowId("wf").taskId("lineage-kind")
                .defaultDatabase("default").rawScript(GeneratedSqlSupport.readSql(file)).options(LineageAnalyzeOptions.builder()
                        .parseMode(mode).expandViewLineage(true).build())
                .metadataProvider(table -> HiveTableMetadata.table(table,
                        Arrays.asList("id", "name", "amount", "qty", "dt"))).build();
        ScriptAnalysisResult result = new HiveScriptLineageAdapter().analyzeNative(request);
        assertEquals(result.getDiagnostics().toString(), 0, result.getCounts().getFailed());
        return new DefaultLegacyResultProjector().project(result).getResult().getStatements();
    }

    private static ColumnLineage target(StatementLineageResult statement, String name) {
        return statement.getColumnLineages().stream()
                .filter(column -> name.equals(column.getTargetColumn().getQualifiedName())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing target " + name + " in " + statement.getRawSql()));
    }

    private static Set<String> sources(ColumnLineage column) {
        return column.getSourceColumns().stream().map(ColumnRef::getQualifiedName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
