package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.v2.api.StatementAnalysisResult;
import com.panfan.hivelineage.v2.api.StatementStatus;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.compat.HiveScriptLineageAdapter;
import com.panfan.hivelineage.v2.testing.GeneratedSqlSupport;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Assert;
import org.junit.Test;

public class ColumnDerivationExportTest {
    public static Set<String> scenarioFiles() {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
                "v2_lineage_export_literal_static_partition_constants.sql",
                "v2_lineage_export_constant_argument_functions_arithmetic_cast_case.sql",
                "v2_lineage_export_zero_argument_functions_count_star.sql",
                "v2_lineage_export_opaque_functions_literal_zero_and_field_arguments.sql",
                "v2_lineage_export_ambiguous_unqualified_column.sql",
                "v2_lineage_export_cte_derived_alias_origins.sql",
                "v2_lineage_export_union_all_literal_and_function_branches.sql",
                "v2_lineage_export_union_all_unknown_literal_function_branch.sql",
                "v2_lineage_export_ctas_field_copy_proof_boundaries.sql",
                "v2_lineage_export_expanded_view_shape_classification.sql",
                "v2_lineage_export_update_literal_direct_with_condition.sql",
                "v2_lineage_export_merge_literal_direct_with_condition.sql",
                "v2_lineage_export_window_functions_shape_and_names.sql",
                "v2_lineage_export_window_nested_key_functions.sql",
                "v2_lineage_export_lateral_view_literal_collection.sql")));
    }

    @Test
    public void literalAndStaticPartitionAssignmentsAreConstantWithNoFieldSources() {
        List<ColumnLineageRecord> columns = columns(analyze("v2_lineage_export_literal_static_partition_constants.sql"), 0);
        Assert.assertEquals(4, columns.size());
        for (ColumnLineageRecord column : columns) {
            Assert.assertEquals(ColumnDerivationKind.CONSTANT, column.getDerivation());
            Assert.assertTrue(column.getSources().isEmpty());
            Assert.assertTrue(column.getFunctionNames().isEmpty());
        }
    }

    @Test
    public void functionArithmeticCastAndCaseRemainDerivedWithOnlyLiteralArguments() {
        List<ColumnLineageRecord> columns = columns(analyze("v2_lineage_export_constant_argument_functions_arithmetic_cast_case.sql"), 0);
        Assert.assertEquals(5, columns.size());
        for (ColumnLineageRecord column : columns) {
            Assert.assertEquals(ColumnDerivationKind.DERIVED, column.getDerivation());
            Assert.assertTrue(column.getSources().isEmpty());
        }
        Assert.assertEquals(Collections.singletonList("upper"), target(columns, "dwd.t", "b").getFunctionNames());
        Assert.assertEquals(Collections.singletonList("cast"), target(columns, "dwd.t", "c").getFunctionNames());
        Assert.assertEquals(Collections.singletonList("concat"), target(columns, "dwd.t", "e").getFunctionNames());
    }

    @Test
    public void zeroArgumentFunctionsAndCountStarAreDerivedWithoutInventedFieldSources() {
        List<ColumnLineageRecord> columns = columns(analyze("v2_lineage_export_zero_argument_functions_count_star.sql"), 0);
        Assert.assertEquals(3, columns.size());
        for (ColumnLineageRecord column : columns) {
            Assert.assertEquals(ColumnDerivationKind.DERIVED, column.getDerivation());
            Assert.assertTrue(column.getSources().isEmpty());
            Assert.assertFalse(column.getFunctionNames().isEmpty());
        }
        Assert.assertEquals(Collections.singletonList("count"), target(columns, "dwd.t", "c").getFunctionNames());
    }

    @Test
    public void opaqueFunctionsAndUnresolvedReferencesStayUnknownEvenWithoutKnownSources() {
        List<ColumnLineageRecord> functions = columns(analyze("v2_lineage_export_opaque_functions_literal_zero_and_field_arguments.sql"), 0);
        for (ColumnLineageRecord column : functions) {
            Assert.assertEquals(ColumnDerivationKind.UNKNOWN, column.getDerivation());
            Assert.assertFalse(column.getFunctionNames().isEmpty());
        }
        Assert.assertTrue(target(functions, "dwd.t", "a").getSources().isEmpty());
        Assert.assertTrue(target(functions, "dwd.t", "b").getSources().isEmpty());
        Assert.assertEquals(Collections.singletonList("name"), target(functions, "dwd.t", "c").getSources().get(0).getFieldPath());
        ColumnLineageRecord missing = columns(analyze("v2_lineage_export_ambiguous_unqualified_column.sql"), 0).get(0);
        Assert.assertEquals(ColumnDerivationKind.UNKNOWN, missing.getDerivation());
        Assert.assertTrue(missing.getSources().isEmpty());
    }

    @Test
    public void aliasesRemainDirectWhileNestedComputationsRetainTheirDerivedOrigin() {
        List<ColumnLineageRecord> columns = columns(analyze("v2_lineage_export_cte_derived_alias_origins.sql"), 0);
        Assert.assertEquals(ColumnDerivationKind.DIRECT, target(columns, "dwd.t", "a").getDerivation());
        Assert.assertEquals(ColumnDerivationKind.DERIVED, target(columns, "dwd.t", "b").getDerivation());
        Assert.assertEquals(Collections.singletonList("upper"), target(columns, "dwd.t", "b").getFunctionNames());
    }

    @Test
    public void pureLiteralSetBranchesAreConstantButLiteralFunctionBranchesAreDerived() {
        List<ColumnLineageRecord> columns = columns(analyze("v2_lineage_export_union_all_literal_and_function_branches.sql"), 0);
        ColumnLineageRecord literal = target(columns, "dwd.t", "a");
        Assert.assertEquals(ColumnDerivationKind.CONSTANT, literal.getDerivation());
        Assert.assertTrue(literal.getFunctionNames().isEmpty());
        ColumnLineageRecord function = target(columns, "dwd.t", "b");
        Assert.assertEquals(ColumnDerivationKind.DERIVED, function.getDerivation());
        Assert.assertEquals(Arrays.asList("union_all", "upper"), function.getFunctionNames());
    }

    @Test
    public void unknownSetBranchesNeverBecomeConstantBecauseTheirKnownInputsAreLiterals() {
        ColumnLineageRecord column = columns(analyze("v2_lineage_export_union_all_unknown_literal_function_branch.sql"), 0).get(0);
        Assert.assertEquals(ColumnDerivationKind.UNKNOWN, column.getDerivation());
        Assert.assertTrue(column.getSources().isEmpty());
        Assert.assertTrue(column.getFunctionNames().contains("company_mask"));
    }

    @Test
    public void multipleProofBoundariesDoNotTurnAFieldCopyIntoADerivedValue() {
        ScriptAnalysisResult result = analyze("v2_lineage_export_ctas_field_copy_proof_boundaries.sql");
        ColumnLineageRecord column = target(columns(result, 1), "dwd.t", "a");
        Assert.assertEquals(ColumnDerivationKind.DIRECT, column.getDerivation());
        Set<String> objects = column.getSources().stream().map(source -> source.getObject().getDisplayName()).collect(Collectors.toSet());
        Assert.assertTrue(objects.contains("ods.t"));
        Assert.assertTrue(objects.contains("tmp.copy_source"));
    }

    @Test
    public void expandedViewDefinitionAndSinkShareShapeClassification() {
        List<ColumnLineageRecord> columns = columns(analyze("v2_lineage_export_expanded_view_shape_classification.sql"), 1);
        for (String object : Arrays.asList("dwd.t", "dim.v")) {
            Assert.assertEquals(ColumnDerivationKind.DIRECT, target(columns, object, "dwd.t".equals(object) ? "a" : "id").getDerivation());
            Assert.assertEquals(ColumnDerivationKind.DERIVED, target(columns, object, "dwd.t".equals(object) ? "b" : "name").getDerivation());
            Assert.assertEquals(ColumnDerivationKind.CONSTANT, target(columns, object, "dwd.t".equals(object) ? "c" : "dt").getDerivation());
        }
        Assert.assertEquals(Collections.singletonList("upper"), target(columns, "dim.v", "name").getFunctionNames());
    }

    @Test
    public void writeConditionsDoNotTransformLiteralOrDirectAssignments() {
        List<ColumnLineageRecord> columns = columns(analyze("v2_lineage_export_update_literal_direct_with_condition.sql"), 0);
        Assert.assertEquals(ColumnDerivationKind.CONSTANT, target(columns, "dwd.t", "name").getDerivation());
        Assert.assertEquals(ColumnDerivationKind.DIRECT, target(columns, "dwd.t", "amount").getDerivation());
    }

    @Test
    public void mergeConditionsDoNotChangeTheAssignedValueClassification() {
        List<ColumnLineageRecord> columns = columns(analyze("v2_lineage_export_merge_literal_direct_with_condition.sql"), 0);
        Assert.assertEquals(ColumnDerivationKind.CONSTANT, target(columns, "dwd.t", "name").getDerivation());
        Assert.assertEquals(ColumnDerivationKind.DIRECT, target(columns, "dwd.t", "amount").getDerivation());
    }

    @Test
    public void windowComputationsRemainDerivedAndRetainTheirFunctionNames() {
        List<ColumnLineageRecord> columns = columns(analyze("v2_lineage_export_window_functions_shape_and_names.sql"), 0);
        Assert.assertEquals(ColumnDerivationKind.DERIVED, target(columns, "dwd.t", "a").getDerivation());
        Assert.assertEquals(ColumnDerivationKind.DERIVED, target(columns, "dwd.t", "b").getDerivation());
        Assert.assertEquals(Collections.singletonList("sum"), target(columns, "dwd.t", "a").getFunctionNames());
        Assert.assertEquals(Collections.singletonList("row_number"), target(columns, "dwd.t", "b").getFunctionNames());
    }

    @Test
    public void generatedValuesRemainDerivedWithLiteralCollectionArguments() {
        ColumnLineageRecord column = columns(analyze("v2_lineage_export_lateral_view_literal_collection.sql"), 0).get(0);
        Assert.assertEquals(ColumnDerivationKind.DERIVED, column.getDerivation());
        Assert.assertTrue(column.getFunctionNames().contains("explode"));
        Assert.assertTrue(column.getFunctionNames().contains("array"));
    }

    private static ColumnLineageRecord target(List<ColumnLineageRecord> columns, String object, String field) {
        return columns.stream().filter(column -> object.equals(column.getTarget().getObject().getDisplayName())
                        && Collections.singletonList(field).equals(column.getTarget().getFieldPath())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing target " + object + "." + field));
    }

    private static List<ColumnLineageRecord> columns(ScriptAnalysisResult result, int index) {
        StatementAnalysisResult statement = result.getStatements().get(index);
        Assert.assertNotEquals(statement.getDiagnostics().getDiagnostics().toString(), StatementStatus.FAILED, statement.getStatus());
        Assert.assertTrue("Statement must publish formal lineage", statement.isFormalLineageProduced());
        return new DefaultFormalLineageRecordExporter(new DefaultSourceRenderer(result.getSources()), false).exportColumns(statement);
    }

    private static ScriptAnalysisResult analyze(String file) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder().workflowId("classification").taskId("export")
                .defaultDatabase("default").rawScript(GeneratedSqlSupport.readSql(file)).options(LineageAnalyzeOptions.builder()
                        .includeIgnoredStatements(true).expandViewLineage(true).build())
                .metadataProvider(table -> HiveTableMetadata.table(table, Arrays.asList("id", "name", "amount", "dt"))).build();
        return new HiveScriptLineageAdapter().analyzeNative(request);
    }
}
