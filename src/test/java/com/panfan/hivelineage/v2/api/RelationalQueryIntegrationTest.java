package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.statement;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Relational syntax matrix with independent, exact physical-source expectations. */
@RunWith(Parameterized.class)
public class RelationalQueryIntegrationTest {
    private final Scenario scenario;
    private final LineageParseMode mode;

    public RelationalQueryIntegrationTest(Scenario scenario, LineageParseMode mode) {
        this.scenario = scenario;
        this.mode = mode;
    }

    @Parameterized.Parameters(name = "{0} [{1}]")
    public static Collection<Object[]> parameters() {
        List<Object[]> values = new ArrayList<>();
        for (Scenario scenario : scenarios()) {
            for (LineageParseMode mode : LineageParseMode.values()) {
                values.add(new Object[] {scenario, mode});
            }
        }
        return values;
    }

    public static Set<String> scenarioFiles() {
        return scenarios().stream().map(scenario -> scenario.file)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    public void fileBackedRelationalSyntaxPreservesPhysicalSourcesInEveryParseMode() {
        ScriptAnalysisResult result = analyze(scenario.file, mode);
        if (scenario.rejectedByFrontend) {
            assertEquals(1, result.getCounts().getTotal());
            assertEquals(1, result.getCounts().getFailed());
            assertEquals(StatementStatus.FAILED, result.getStatements().get(0).getStatus());
            assertEquals("SQL_PARSE_FAILED", result.getStatements().get(0).getFailureCode().get());
            assertTrue(result.getDiagnostics().getDiagnostics().stream()
                .anyMatch(diagnostic -> "HIVE_SYNTAX_ERROR".equals(diagnostic.getCode())));
            assertFalse(result.getStatements().get(0).isFormalLineageProduced());
            assertFalse(result.getStatements().get(0).getLineage().isPresent());
            assertFalse(result.getStatements().get(0).getJoins().isPresent());
            assertTrue(statement(result, 0).getTableLineages().isEmpty());
            assertTrue(statement(result, 0).getColumnLineages().isEmpty());
            return;
        }
        assertNoFailed(result);
        assertEquals(scenario.file, scenario.statementCount, result.getCounts().getTotal());
        for (int index = 0; index < scenario.statementCount; index++) {
            StatementLineageResult statement = statement(result, index);
            assertTrue(scenario.file, statement.isFormalLineageProduced());
            assertEquals("dwd.result", statement.getOutputObject().getQualifiedName());
            assertInputs(statement, scenario.inputs);
            assertTableSources(statement, "dwd.result", scenario.inputs);
            for (Map.Entry<String, String[]> mapping : scenario.columns.entrySet()) {
                assertColumnSources(statement, "dwd.result." + mapping.getKey(), mapping.getValue());
            }
            if (mode != LineageParseMode.TABLE_ONLY) {
                assertEquals("Unexpected output mappings in " + scenario.file,
                    scenario.columns.size(), statement.getColumnLineages().size());
            }
            boolean cross = "CROSS_JOIN".equals(scenario.joinType);
            assertEquals(scenario.file, scenario.joinType == null || cross ? 0 : 1, statement.getJoinConditions().size());
            if (cross) {
                // Condition analysis publishes predicates; the bound IR retains the CROSS operator.
                assertTrue(result.getStatements().get(index).getJoins().get().getJoins().isEmpty());
                assertTrue(result.getStatements().get(index).getBoundUnit().get().getRelations().stream()
                    .anyMatch(relation -> relation instanceof Relations.Join
                        && ((Relations.Join) relation).getJoinType() == Relations.JoinType.CROSS));
            } else if (scenario.joinType != null) {
                assertEquals(scenario.file, scenario.joinType, statement.getJoinConditions().get(0).getJoinTypeName());
                assertEquals(scenario.file, new TreeSet<>(Arrays.asList(scenario.joinColumns)),
                    statement.getJoinConditions().get(0).getJoinColumns().stream()
                        .map(column -> column.getQualifiedName()).collect(Collectors.toCollection(TreeSet::new)));
            }
        }
    }

    private static List<Scenario> scenarios() {
        List<Scenario> scenarios = new ArrayList<>();
        scenarios.add(scenario("join_inner_derived", "ods.users", "ods.departments")
            .column("id", "ods.users.id").column("name", "ods.users.name")
            .column("dept_name", "ods.departments.dept_name")
            .join("INNER_JOIN", "ods.users.dept_id", "ods.departments.dept_id"));
        scenarios.add(scenario("join_left_cte_aggregate", "ods.users", "ods.orders")
            .column("id", "ods.users.id").column("amount", "ods.orders.amount")
            .join("LEFT_JOIN", "ods.users.id", "ods.orders.user_id"));
        scenarios.add(scenario("join_right_derived", "ods.users", "ods.departments")
            .column("id", "ods.users.id", "ods.departments.dept_id")
            .column("dept_name", "ods.departments.dept_name")
            .join("RIGHT_JOIN", "ods.users.dept_id", "ods.departments.dept_id"));
        scenarios.add(scenario("join_full_cte", "ods.users", "ods.users_archive")
            .column("id", "ods.users.id", "ods.users_archive.id")
            .column("name", "ods.users.name", "ods.users_archive.name")
            .join("FULL_JOIN", "ods.users.id", "ods.users_archive.id"));
        scenarios.add(scenario("join_left_semi_derived", "ods.users", "ods.orders")
            .column("id", "ods.users.id").column("name", "ods.users.name")
            .join("LEFT_SEMI_JOIN", "ods.users.id", "ods.orders.user_id"));
        scenarios.add(scenario("join_left_anti_rejected_by_frontend", "ods.users", "ods.events").rejected());
        scenarios.add(scenario("join_cross_derived", "ods.users", "ods.departments")
            .column("id", "ods.users.id").column("dept_name", "ods.departments.dept_name")
            .join("CROSS_JOIN"));
        scenarios.add(scenario("join_comma_derived_where", "ods.users", "ods.orders")
            .column("id", "ods.users.id").column("rn", "ods.orders.order_id")
            .join("IMPLICIT_JOIN", "ods.users.id", "ods.orders.order_id"));
        for (String operator : Arrays.asList("union", "intersect", "except")) {
            for (String quantifier : Arrays.asList("all", "distinct")) {
                scenarios.add(scenario("set_" + operator + "_" + quantifier + "_derived", "ods.users", "ods.users_archive")
                    .column("id", "ods.users.id", "ods.users_archive.id")
                    .column("name", "ods.users.name", "ods.users_archive.name"));
            }
        }
        scenarios.add(scenario("group_having_nested", "ods.users")
            .column("id", "ods.users.dept_id").column("amount", "ods.users.amount").column("rn", "ods.users.id"));
        for (String grouping : Arrays.asList("rollup_cte", "cube_derived", "grouping_sets_cte")) {
            scenarios.add(scenario("group_" + grouping, "ods.users")
                .column("id", "ods.users.dept_id").column("dt", "ods.users.dt").column("amount", "ods.users.amount"));
        }
        // CURRENT_API includes window partition/order fields in legacy column dependencies.
        scenarios.add(scenario("window_inline_rows_nested", "ods.orders")
            .column("id", "ods.orders.user_id")
            .column("amount", "ods.orders.amount", "ods.orders.user_id", "ods.orders.dt")
            .column("rn", "ods.orders.user_id", "ods.orders.amount"));
        scenarios.add(scenario("window_named_range_cte", "ods.users")
            .column("id", "ods.users.id")
            .column("amount", "ods.users.amount", "ods.users.dept_id")
            .column("rn", "ods.users.amount", "ods.users.dept_id"));
        scenarios.add(scenario("lateral_outer_posexplode_derived", "ods.users")
            .column("id", "ods.users.id").column("rn", "ods.users.tags").column("tag", "ods.users.tags"));
        scenarios.add(scenario("lateral_nested_generators_cte", "ods.users")
            .column("id", "ods.users.id").column("tag", "ods.users.tags"));
        scenarios.add(scenario("tails_samples_sort_limit", "ods.users")
            .column("id", "ods.users.id").column("name", "ods.users.name").statements(8));
        scenarios.add(scenario("distinct_star_mixed_constants", "ods.users")
            .column("id", "ods.users.id").column("name", "ods.users.name").column("amount", "ods.users.amount")
            .column("dept_name", "ods.users.dept_id").column("tag").column("rn").column("dt"));
        return Collections.unmodifiableList(scenarios);
    }

    private static Scenario scenario(String suffix, String... inputs) {
        return new Scenario("v2_query_" + suffix + ".sql", inputs);
    }

    public static final class Scenario {
        private final String file;
        private final String[] inputs;
        private final Map<String, String[]> columns = new LinkedHashMap<>();
        private String joinType;
        private String[] joinColumns = new String[0];
        private int statementCount = 1;
        private boolean rejectedByFrontend;

        private Scenario(String file, String[] inputs) {
            this.file = file;
            this.inputs = inputs;
        }

        private Scenario column(String target, String... sources) {
            columns.put(target, sources);
            return this;
        }

        private Scenario join(String type, String... columns) {
            joinType = type;
            joinColumns = columns;
            return this;
        }

        private Scenario statements(int count) {
            statementCount = count;
            return this;
        }

        private Scenario rejected() {
            rejectedByFrontend = true;
            return this;
        }

        @Override
        public String toString() {
            return file;
        }
    }
}
