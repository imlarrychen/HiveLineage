package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.v2.analysis.Coverage;
import com.panfan.hivelineage.v2.testing.GeneratedSqlSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
import static org.junit.Assert.assertTrue;

/**
 * Literal/operator/function and uncertainty boundaries use reviewed source expectations.
 */
@RunWith(Parameterized.class)
public class ExpressionLineageIntegrationTest {
    private static final Map<String, String[][]> MAPPINGS = mappings();
    private final String file;
    private final LineageParseMode mode;

    public ExpressionLineageIntegrationTest(String file, LineageParseMode mode) {
        this.file = file;
        this.mode = mode;
    }

    public static Set<String> scenarioFiles() {
        return new TreeSet<>(MAPPINGS.keySet());
    }

    @Parameterized.Parameters(name = "{0}: {1}")
    public static Collection<Object[]> cases() {
        Collection<Object[]> cases = new ArrayList<>();
        for (String file : scenarioFiles()) {
            for (LineageParseMode mode : LineageParseMode.values()) {
                cases.add(new Object[]{file, mode});
            }
        }
        return cases;
    }

    @Test
    public void expressionSourcesAndUnsupportedInformationRemainExplicit() {
        boolean complexTypes = file.equals("v2_expression_array_map_struct_access.sql");
        ScriptAnalysisResult result = complexTypes ? GeneratedSqlSupport.analyzeWithComplexTypes(file, mode) : analyze(file, mode);
        if (file.equals("v2_boundary_recursive_cte.sql")) {
            assertEquals(1, result.getCounts().getFailed());
            assertEquals(StatementStatus.FAILED, result.getStatements().get(0).getStatus());
            assertDiagnostic(result, "RECURSIVE_DEFINITION");
            assertTrue(statement(result, 0).getTableLineages().isEmpty());
            assertTrue(statement(result, 0).getColumnLineages().isEmpty());
            return;
        }
        assertNoFailed(result);
        assertEquals(1, result.getCounts().getTotal());
        StatementLineageResult statement = statement(result, 0);
        String[] inputs = inputs();
        assertInputs(statement, inputs);
        assertTableSources(statement, "dwd.result", inputs);
        assertTrue(statement.isFormalLineageProduced());
        for (String[] mapping : MAPPINGS.get(file)) {
            assertColumnSources(statement, "dwd.result." + mapping[0], Arrays.copyOfRange(mapping, 1, mapping.length));
        }
        if (mode != LineageParseMode.TABLE_ONLY) {
            assertEquals(MAPPINGS.get(file).length, statement.getColumnLineages().size());
            if (file.equals("v2_expression_opaque_udf_arguments.sql")) {
                assertDiagnostic(result, "OPAQUE_FUNCTION");
                assertEquals(Coverage.PARTIAL, result.getStatements().get(0).getLineage().get().getQuality().getCoverage());
            }
            if (file.equals("v2_boundary_scalar_multiple_outputs.sql")) {
                assertDiagnostic(result, "SCALAR_SUBQUERY_WIDTH_UNKNOWN");
                assertEquals(StatementStatus.PARTIAL_SUCCESS, result.getStatements().get(0).getStatus());
            }
            if (file.equals("v2_boundary_ambiguous_column_owner.sql")) {
                assertDiagnostic(result, "AMBIGUOUS_COLUMN_OWNER");
                assertEquals(StatementStatus.PARTIAL_SUCCESS, result.getStatements().get(0).getStatus());
            }
            if (file.contains("scalar")) {
                if (!file.equals("v2_boundary_scalar_multiple_outputs.sql")) {
                    assertDiagnostic(result, "SCALAR_CARDINALITY_UNKNOWN");
                }
            }
            if (file.equals("v2_expression_constant_assignments.sql")) {
                statement.getColumnLineages().forEach(column -> {
                    assertEquals(LineageType.CONSTANT, column.getLineageType());
                    assertTrue(column.getSourceColumns().isEmpty());
                });
            }
            if (complexTypes) {
                ScriptAnalysisResult untyped = analyze(file, mode);
                assertNoFailed(untyped);
                assertDiagnostic(untyped, "UNRESOLVED_FIELD_PATH");
                assertColumnSources(statement(untyped, 0), "dwd.result.amount");
            }
        }
    }

    private String[] inputs() {
        if (file.equals("v2_expression_array_map_struct_access.sql")) {
            return new String[]{"ods.complex_users"};
        }
        if (file.equals("v2_expression_simple_searched_case.sql") || file.contains("window_scalar")
                || file.equals("v2_boundary_scalar_multiple_outputs.sql") || file.equals("v2_boundary_ambiguous_column_owner.sql")) {
            return new String[]{"ods.users", "ods.orders"};
        }
        if (file.equals("v2_expression_distinct_aggregate_count_star.sql")) {
            return new String[]{"ods.orders"};
        }
        if (file.equals("v2_expression_field_access_scalar_struct.sql")) {
            return new String[]{"ods.users", "ods.users_archive"};
        }
        return new String[]{"ods.users"};
    }

    private static void assertDiagnostic(ScriptAnalysisResult result, String code) {
        assertTrue(code + ": " + result.getDiagnostics().getDiagnostics(), result.getDiagnostics().getDiagnostics().stream()
                .anyMatch(diagnostic -> code.equals(diagnostic.getCode())));
    }

    private static Map<String, String[][]> mappings() {
        Map<String, String[][]> cases = new LinkedHashMap<>();
        cases.put("v2_expression_arithmetic_bitwise.sql", new String[][]{
                {"id", "ods.users.id"}, {"amount", "ods.users.amount", "ods.users.id"}, {"rn", "ods.users.id"}
        });
        cases.put("v2_expression_simple_searched_case.sql", new String[][]{
                {"id", "ods.users.id"}, {"name", "ods.users.id", "ods.users.name"},
                {"amount", "ods.orders.status", "ods.orders.amount"}
        });
        cases.put("v2_expression_cast_nested_functions.sql", new String[][]{
                {"id", "ods.users.id"}, {"name", "ods.users.id", "ods.users.name"}, {"amount", "ods.users.amount"}
        });
        cases.put("v2_expression_array_map_struct_access.sql", new String[][]{
                {"id", "ods.complex_users.id"}, {"tag", "ods.complex_users.tags"}, {"name", "ods.complex_users.attributes"},
                {"amount", "ods.complex_users.profile.account.score"}
        });
        cases.put("v2_expression_predicate_operators.sql", new String[][]{
                {"id", "ods.users.id"}, {"rn", "ods.users.id", "ods.users.amount"},
                {"tag", "ods.users.name"}, {"name", "ods.users.name"}
        });
        cases.put("v2_expression_distinct_aggregate_count_star.sql", new String[][]{
                {"id", "ods.orders.user_id"}, {"amount", "ods.orders.amount"}, {"rn"}
        });
        cases.put("v2_expression_constant_assignments.sql", new String[][]{{"id"}, {"name"}, {"dt"}});
        cases.put("v2_expression_opaque_udf_arguments.sql", new String[][]{
                {"id", "ods.users.id"}, {"amount", "ods.users.id", "ods.users.amount"}
        });
        cases.put("v2_expression_quoted_identifiers_comments.sql", new String[][]{
                {"id", "ods.users.id"}, {"name", "ods.users.name"}
        });
        for (String file : Arrays.asList("v2_expression_window_scalar_order.sql", "v2_expression_named_window_scalar_order.sql")) {
            cases.put(file, new String[][]{{"id", "ods.users.id"}, {"amount", "ods.users.amount", "ods.orders.amount"}});
        }
        cases.put("v2_expression_field_access_scalar_struct.sql", new String[][]{
                {"id", "ods.users.id"}, {"name", "ods.users_archive.payload.label"}
        });
        cases.put("v2_boundary_scalar_multiple_outputs.sql", new String[][]{{"id", "ods.users.id"}, {"amount"}});
        cases.put("v2_boundary_ambiguous_column_owner.sql", new String[][]{{"id", "ods.users.id"}, {"amount"}});
        cases.put("v2_boundary_recursive_cte.sql", new String[0][]);
        return cases;
    }
}
