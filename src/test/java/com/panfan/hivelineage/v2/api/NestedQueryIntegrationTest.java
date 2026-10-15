package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.v2.analysis.ColumnEndpoint;
import com.panfan.hivelineage.v2.analysis.Coverage;
import com.panfan.hivelineage.v2.analysis.UnknownEndpoint;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;

import java.util.ArrayList;
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

import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.analyze;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertColumnSources;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertInputs;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertNoFailed;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertTableSources;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.statement;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Independent expected physical sources for file-backed nested SQL, through every V2 stage.
 */
@RunWith(Parameterized.class)
public class NestedQueryIntegrationTest {
    private static final List<Scenario> SCENARIOS = Collections.unmodifiableList(Arrays.asList(
            scenario("v2_nested_derived_three_levels.sql", inputs("ods.users"),
                    column("id", "ods.users.id"), column("name", "ods.users.name"), column("amount", "ods.users.amount")),
            scenario("v2_nested_derived_alias_shadowing.sql", inputs("ods.users", "ods.users_archive"),
                    column("id", "ods.users.id"), column("name", "ods.users_archive.name")),
            scenario("v2_nested_derived_join_aggregate.sql", inputs("ods.users", "ods.orders"),
                    column("id", "ods.users.id"), column("name", "ods.users.name"), column("amount", "ods.orders.amount")),
            scenario("v2_nested_cte_dependency_chain.sql", inputs("ods.users"),
                    column("id", "ods.users.id"), column("name", "ods.users.name"), column("amount", "ods.users.amount")),
            scenario("v2_nested_cte_column_aliases.sql", inputs("ods.users"),
                    column("id", "ods.users.id"), column("name", "ods.users.name"), column("amount", "ods.users.amount")),
            scenario("v2_nested_cte_self_join.sql", inputs("ods.users"),
                    column("id", "ods.users.id"), column("name", "ods.users.name"), column("amount", "ods.users.amount")),
            scenario("v2_nested_cte_local_shadowing.sql", inputs("ods.orders", "ods.users_archive"),
                    column("id", "ods.users_archive.id"), column("name", "ods.users_archive.name"), column("amount", "ods.orders.amount")),
            scenario("v2_nested_union_inside_derived.sql", inputs("ods.users", "ods.users_archive"),
                    column("id", "ods.users.id", "ods.users_archive.id"),
                    column("name", "ods.users.name", "ods.users_archive.name"),
                    column("amount", "ods.users.amount", "ods.users_archive.amount")),
            scenario("v2_nested_insert_overwrite_union_all_last_branch_derived.sql", inputs("ods.users", "ods.users_archive"),
                    column("id", "ods.users.id", "ods.users_archive.id"),
                    column("name", "ods.users.name", "ods.users_archive.name")),
            scenario("v2_nested_insert_overwrite_union_all_first_branch_derived.sql", inputs("ods.users", "ods.users_archive"),
                    column("id", "ods.users.id", "ods.users_archive.id"),
                    column("name", "ods.users.name", "ods.users_archive.name")),
            scenario("v2_nested_insert_overwrite_union_all_both_branches_derived.sql", inputs("ods.users", "ods.users_archive"),
                    column("id", "ods.users.id", "ods.users_archive.id"),
                    column("name", "ods.users.name", "ods.users_archive.name")),
            scenario("v2_nested_union_inside_cte.sql", inputs("ods.users", "ods.users_archive", "ods.departments"),
                    column("id", "ods.users.id", "ods.users_archive.id"),
                    column("name", "ods.users.name", "ods.users_archive.name"), column("dept_name", "ods.departments.dept_name")),
            scenario("v2_nested_intersect_inside_derived.sql", inputs("ods.users", "ods.users_archive"),
                    column("id", "ods.users.id", "ods.users_archive.id")),
            scenario("v2_nested_except_inside_cte.sql", inputs("ods.users", "ods.users_archive"),
                    column("id", "ods.users.id", "ods.users_archive.id")),
            scenario("v2_nested_scalar_select_uncorrelated.sql", inputs("ods.users", "ods.orders"),
                    column("id", "ods.users.id"), column("name", "ods.users.name"), column("amount", "ods.orders.amount")),
            scenario("v2_nested_scalar_select_correlated.sql", inputs("ods.users", "ods.orders"),
                    column("id", "ods.users.id"), column("name", "ods.users.name"), column("amount", "ods.orders.amount")),
            scenario("v2_nested_scalar_case_cast_function.sql", inputs("ods.users", "ods.orders", "ods.users_archive"),
                    column("id", "ods.users.id"), column("amount", "ods.users.amount", "ods.orders.amount", "ods.users_archive.amount")),
            scenario("v2_nested_scalar_between_bounds.sql", inputs("ods.users", "ods.orders", "ods.users_archive"),
                    column("id", "ods.users.id"), column("amount", "ods.users.amount", "ods.orders.amount", "ods.users_archive.amount")),
            scenario("v2_nested_scalar_in_list.sql", inputs("ods.users", "ods.orders"),
                    column("id", "ods.users.id"), column("name", "ods.users.id", "ods.users.name", "ods.orders.user_id")),
            scenario("v2_nested_scalar_subscript.sql", inputs("ods.users", "ods.events"),
                    column("id", "ods.users.id"), column("tag", "ods.events.kind")),
            scenario("v2_nested_exists_double_correlation.sql", inputs("ods.users", "ods.orders", "ods.events"),
                    column("id", "ods.users.id"), column("name", "ods.users.name")),
            scenario("v2_nested_in_and_not_in.sql", inputs("ods.users", "ods.orders", "ods.events"),
                    column("id", "ods.users.id"), column("name", "ods.users.name")),
            scenario("v2_nested_having_scalar_subquery.sql", inputs("ods.users", "ods.orders"),
                    column("id", "ods.users.id"), column("amount", "ods.users.amount")),
            scenario("v2_nested_window_derived_filter.sql", inputs("ods.users"),
                    column("id", "ods.users.id"), column("amount", "ods.users.amount"),
                    column("rn", "ods.users.dept_id", "ods.users.amount", "ods.users.dt")),
            scenario("v2_nested_lateral_view_outer_chain.sql", inputs("ods.users"),
                    column("id", "ods.users.id"), column("tag", "ods.users.tags")),
            scenario("v2_nested_mixed_cte_join_union_exists.sql", inputs("ods.users", "ods.users_archive", "ods.orders", "ods.departments", "ods.events"),
                    column("id", "ods.users.id", "ods.users_archive.id"),
                    column("name", "ods.users.name", "ods.users_archive.name"), column("amount", "ods.orders.amount"),
                    column("dept_name", "ods.departments.dept_name")),
            scenario("v2_nested_scalar_derived_aggregate.sql", inputs("ods.users", "ods.orders"),
                    column("id", "ods.users.id"), column("amount", "ods.orders.amount")),
            scenario("v2_nested_join_on_exists.sql", inputs("ods.users", "ods.departments", "ods.events"),
                    column("id", "ods.users.id"), column("name", "ods.users.name"), column("dept_name", "ods.departments.dept_name"))
    ));

    private final Scenario scenario;
    private final LineageParseMode mode;

    public NestedQueryIntegrationTest(Scenario scenario, LineageParseMode mode) {
        this.scenario = scenario;
        this.mode = mode;
    }

    @Parameterized.Parameters(name = "{0}: {1}")
    public static Collection<Object[]> cases() {
        List<Object[]> cases = new ArrayList<>();
        for (Scenario scenario : SCENARIOS) {
            for (LineageParseMode mode : LineageParseMode.values()) {
                cases.add(new Object[]{scenario, mode});
            }
        }
        return cases;
    }

    public static Set<String> scenarioFiles() {
        Set<String> files = SCENARIOS.stream().map(item -> item.file)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(files);
    }

    @Test
    public void nestedQueriesPreserveOnlyTheirDeclaredPhysicalSourcesInEveryParseMode() {
        ScriptAnalysisResult result = analyze(scenario.file, mode);
        assertNoFailed(result);
        assertEquals(scenario.file, 1, result.getCounts().getTotal());
        StatementLineageResult output = statement(result, 0);
        assertTrue(scenario.file, output.isFormalLineageProduced());
        assertEquals("dwd.result", output.getOutputObject().getQualifiedName());
        assertInputs(output, scenario.inputs);
        assertTableSources(output, "dwd.result", scenario.inputs);
        for (ColumnExpectation expected : scenario.columns) {
            assertColumnSources(output, "dwd.result." + expected.target, expected.sources);
        }
        if (mode != LineageParseMode.TABLE_ONLY) {
            assertEquals(scenario.file, scenario.columns.size(), output.getColumnLineages().size());
        }
        assertNestedIdentityAndScope(result);
        assertScalarCardinalityRemainsExplicit(result);
    }

    private void assertNestedIdentityAndScope(ScriptAnalysisResult result) {
        BoundAnalysisUnit unit = result.getStatements().get(0).getBoundUnit().get();
        if (scenario.file.startsWith("v2_nested_insert_overwrite_union_all_")) {
            assertEquals(1, unit.getWrites().size());
            assertEquals(BoundWrite.WriteMode.OVERWRITE, unit.getWrites().get(0).getWriteMode());
            List<Relations.SetOp> sets = unit.getRelations().stream()
                    .filter(relation -> relation instanceof Relations.SetOp).map(relation -> (Relations.SetOp) relation)
                    .collect(Collectors.toList());
            assertEquals(1, sets.size());
            assertEquals(Relations.SetOperator.UNION, sets.get(0).getOperator());
            assertEquals(Relations.DuplicatePolicy.ALL, sets.get(0).getDuplicatePolicy());
            assertEquals(2, sets.get(0).getBranches().size());
        }
        Set<String> paths = unit.getQueryBlocks().values().stream().map(block -> block.getPath())
                .collect(Collectors.toSet());
        if ("v2_nested_derived_three_levels.sql".equals(scenario.file)) {
            assertTrue(paths.toString(), paths.contains("root.subquery[1].subquery[1].subquery[1]"));
        }
        if ("v2_nested_exists_double_correlation.sql".equals(scenario.file)) {
            assertTrue(paths.toString(), paths.contains("root.where.exists[1].where.exists[1]"));
        }
        if ("v2_nested_join_on_exists.sql".equals(scenario.file)) {
            assertTrue(paths.toString(), paths.contains("root.on.exists[1].subquery[1]"));
        }
        if ("v2_nested_cte_self_join.sql".equals(scenario.file)) {
            List<Relations.DefinitionInstance> instances = unit.getRelations().stream()
                    .filter(relation -> relation instanceof Relations.DefinitionInstance)
                    .map(relation -> (Relations.DefinitionInstance) relation).collect(Collectors.toList());
            assertEquals(2, instances.size());
            assertEquals(instances.get(0).getDefinitionId(), instances.get(1).getDefinitionId());
            assertNotEquals(instances.get(0).getInstanceId(), instances.get(1).getInstanceId());
            assertNotEquals(instances.get(0).getOutputSchema(), instances.get(1).getOutputSchema());
        }
    }

    private void assertScalarCardinalityRemainsExplicit(ScriptAnalysisResult result) {
        boolean scalarProjection = scenario.file.startsWith("v2_nested_scalar_");
        boolean scalarHaving = "v2_nested_having_scalar_subquery.sql".equals(scenario.file);
        if (!scalarProjection && !scalarHaving) {
            return;
        }
        boolean cardinalityRequested = scalarHaving || mode != LineageParseMode.TABLE_ONLY;
        boolean hasUnknownCardinality = result.getDiagnostics().getDiagnostics().stream()
                .anyMatch(diagnostic -> "SCALAR_CARDINALITY_UNKNOWN".equals(diagnostic.getCode()));
        assertEquals(scenario.file + ": " + mode, cardinalityRequested, hasUnknownCardinality);
        if (cardinalityRequested) {
            assertEquals(scenario.file, Coverage.PARTIAL, result.getStatements().get(0).getLineage().get().getQuality().getCoverage());
            assertTrue("Scalar uncertainty must be retained in native facts", result.getStatements().get(0).getLineage().get()
                    .getWriteDependencies().stream().anyMatch(dependency -> dependency.getSource() instanceof UnknownEndpoint
                            && ((UnknownEndpoint) dependency.getSource()).getDiagnostics().getDiagnostics().stream()
                            .anyMatch(diagnostic -> "SCALAR_CARDINALITY_UNKNOWN".equals(diagnostic.getCode()))));
            if (mode != LineageParseMode.TABLE_ONLY) {
                assertTrue("Known physical field dependencies must survive scalar uncertainty", result.getStatements().get(0)
                        .getLineage().get().getWriteDependencies().stream()
                        .anyMatch(dependency -> dependency.getSource() instanceof ColumnEndpoint));
            }
        }
    }

    private static Scenario scenario(String file, String[] inputs, ColumnExpectation... columns) {
        return new Scenario(file, inputs, Arrays.asList(columns));
    }

    private static String[] inputs(String... inputs) {
        return inputs;
    }

    private static ColumnExpectation column(String target, String... sources) {
        return new ColumnExpectation(target, sources);
    }

    public static final class Scenario {
        private final String file;
        private final String[] inputs;
        private final List<ColumnExpectation> columns;

        private Scenario(String file, String[] inputs, List<ColumnExpectation> columns) {
            this.file = file;
            this.inputs = inputs;
            this.columns = columns;
        }

        @Override
        public String toString() {
            return file;
        }
    }

    private static final class ColumnExpectation {
        private final String target;
        private final String[] sources;

        private ColumnExpectation(String target, String[] sources) {
            this.target = target;
            this.sources = sources;
        }
    }
}
