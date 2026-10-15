package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.v2.compat.DefaultRequestAdapter;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.relation.ExternalDatasetRelation;
import com.panfan.hivelineage.v2.ir.relation.KnownOutputSegment;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.DefaultV1MetadataAdapterFactory;
import com.panfan.hivelineage.v2.metadata.LegacyMetadataBinding;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.analyze;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.analyzeWithExternalLineage;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertColumnSources;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertInputs;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertNoFailed;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertTableSources;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.request;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.statement;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Exercises file-backed statements through the parser, binder, verifier, effects and legacy projection.
 */
@RunWith(Parameterized.class)
public class StatementLineageIntegrationTest {
    private final LineageParseMode mode;

    public StatementLineageIntegrationTest(LineageParseMode mode) {
        this.mode = mode;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> modes() {
        return Arrays.stream(LineageParseMode.values()).map(mode -> new Object[]{mode}).collect(Collectors.toList());
    }

    public static Set<String> scenarioFiles() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                "v2_statement_insert_explicit_column_order.sql",
                "v2_statement_partition_static_explicit_columns.sql",
                "v2_statement_partition_dynamic_nested_source.sql",
                "v2_statement_partition_mixed_static_dynamic.sql",
                "v2_statement_multi_insert_shared_nested_union.sql",
                "v2_statement_ctas_nested_aggregate_chain.sql",
                "v2_statement_temporary_ctas_two_stage_chain.sql",
                "v2_statement_create_alter_view_nested_chain.sql",
                "v2_statement_session_set_use_variable_substitution.sql",
                "v2_statement_pure_select_cte_session_reuse.sql",
                "v2_statement_update_multiple_assignments.sql",
                "v2_statement_delete_predicate_without_column_assignments.sql",
                "v2_statement_merge_derived_source_three_actions.sql",
                "v2_statement_catalog_add_replace_rename_drop.sql",
                "v2_statement_values_directory_ignored_then_insert.sql",
                "v2_statement_load_data_local_and_distributed.sql",
                "v2_statement_resource_function_source_commands.sql",
                "v2_statement_catalog_properties_location_truncate_boundary.sql",
                "v2_statement_external_table_storage_and_like_ddl.sql")));
    }

    @Test
    public void explicitInsertColumnsPreserveDifferentDeclaredOrdersAndModes() {
        ScriptAnalysisResult result = successful("v2_statement_insert_explicit_column_order.sql");
        assertEquals(2, result.getCounts().getTotal());
        assertWrite(result, 0, BoundWrite.WriteMode.APPEND, "name", "id", "amount");
        assertWrite(result, 1, BoundWrite.WriteMode.OVERWRITE, "amount", "name", "id");
        assertInputs(statement(result, 0), "ods.users");
        assertInputs(statement(result, 1), "ods.users_archive");
        assertColumnSources(statement(result, 0), "dwd.result.id", "ods.users.id");
        assertColumnSources(statement(result, 0), "dwd.result.name", "ods.users.name");
        assertColumnSources(statement(result, 1), "dwd.result_archive.amount", "ods.users_archive.amount");
        assertColumnSources(statement(result, 1), "dwd.result_archive.name", "ods.users_archive.name");
    }

    @Test
    public void staticPartitionIsLiteralWithoutInventedSourceColumns() {
        ScriptAnalysisResult result = successful("v2_statement_partition_static_explicit_columns.sql");
        assertPartition(result, PartitionScope.Kind.STATIC, PartitionScope.AssignmentKind.STATIC);
        assertColumnSources(statement(result, 0), "dwd.result.id", "ods.users.id");
        assertColumnSources(statement(result, 0), "dwd.result.dt");
        assertTrue(write(result, 0).getPartitionScope().getAssignments().get(0).getExpression() instanceof Expressions.Literal);
    }

    @Test
    public void dynamicPartitionTracesNestedAliasesAndTrailingQueryOutput() {
        ScriptAnalysisResult result = successful("v2_statement_partition_dynamic_nested_source.sql");
        assertPartition(result, PartitionScope.Kind.DYNAMIC, PartitionScope.AssignmentKind.DYNAMIC);
        assertColumnSources(statement(result, 0), "dwd.result.id", "ods.users.id");
        assertColumnSources(statement(result, 0), "dwd.result.name", "ods.users.name");
        assertColumnSources(statement(result, 0), "dwd.result.dt", "ods.users.dt");
    }

    @Test
    public void mixedPartitionsKeepLiteralAndArrayDerivedMappingsSeparate() {
        ScriptAnalysisResult result = successful("v2_statement_partition_mixed_static_dynamic.sql");
        assertPartition(result, PartitionScope.Kind.MIXED, PartitionScope.AssignmentKind.STATIC, PartitionScope.AssignmentKind.DYNAMIC);
        assertColumnSources(statement(result, 0), "dwd.result.tag", "ods.users.tags");
        assertColumnSources(statement(result, 0), "dwd.result.dt");
    }

    @Test
    public void sharedNestedUnionCreatesTwoIndependentInsertContributions() {
        ScriptAnalysisResult result = successful("v2_statement_multi_insert_shared_nested_union.sql");
        List<BoundWrite> writes = unit(result, 0).getWrites();
        assertEquals(2, writes.size());
        assertEquals(BoundWrite.WriteMode.APPEND, writes.get(0).getWriteMode());
        assertEquals(BoundWrite.WriteMode.OVERWRITE, writes.get(1).getWriteMode());
        assertNotEquals(writes.get(0).getId(), writes.get(1).getId());
        assertNotEquals(writes.get(0).getTargetObject(), writes.get(1).getTargetObject());
        assertInputs(statement(result, 0), "ods.users", "ods.users_archive");
        assertTableSources(statement(result, 0), "dwd.result", "ods.users", "ods.users_archive");
        assertTableSources(statement(result, 0), "dwd.result_archive", "ods.users", "ods.users_archive");
        assertColumnSources(statement(result, 0), "dwd.result.id", "ods.users.id", "ods.users_archive.id");
        assertColumnSources(statement(result, 0), "dwd.result.name", "ods.users.name", "ods.users_archive.name");
        assertColumnSources(statement(result, 0), "dwd.result_archive.amount", "ods.users.amount", "ods.users_archive.amount");
    }

    @Test
    public void nestedAggregateCtasRegistersDefinitionForLaterReads() {
        ScriptAnalysisResult result = successful("v2_statement_ctas_nested_aggregate_chain.sql");
        assertEquals(BoundWrite.WriteMode.CREATE_AS, write(result, 0).getWriteMode());
        assertEffect(result, 0, Effects.EffectKind.CREATE_OBJECT);
        assertEffect(result, 0, Effects.EffectKind.REGISTER_DEFINITION);
        assertColumnSources(statement(result, 0), "dwd.statement_order_totals.id", "ods.orders.user_id");
        assertColumnSources(statement(result, 0), "dwd.statement_order_totals.amount", "ods.orders.amount");
        assertInputs(statement(result, 1), "dwd.statement_order_totals");
        assertColumnSources(statement(result, 1), "dwd.result.id", "ods.orders.user_id", "dwd.statement_order_totals.id");
        assertColumnSources(statement(result, 1), "dwd.result.amount", "ods.orders.amount", "dwd.statement_order_totals.amount");
    }

    @Test
    public void temporaryCtasChainIsUnpublishedButStillExpandsFinalSources() {
        ScriptAnalysisResult result = analyze("v2_statement_temporary_ctas_two_stage_chain.sql", mode);
        assertNoFailed(result);
        assertIgnored(result, 0, "TEMPORARY_LINEAGE_DISABLED");
        assertIgnored(result, 1, "TEMPORARY_LINEAGE_DISABLED");
        assertEquals(BoundWrite.WriteMode.CREATE_AS, write(result, 0).getWriteMode());
        assertEquals(BoundWrite.WriteMode.CREATE_AS, write(result, 1).getWriteMode());
        assertEquals(StatementStatus.SUCCESS, result.getStatements().get(2).getStatus());
        assertInputs(statement(result, 2), "ods.users");
        assertColumnSources(statement(result, 2), "dwd.result.id", "ods.users.id");
        assertColumnSources(statement(result, 2), "dwd.result.name", "ods.users.name");
        assertColumnSources(statement(result, 2), "dwd.result.amount", "ods.users.amount");
    }

    @Test
    public void alteringViewReplacesItsSourcesThroughoutTheNestedViewChain() {
        ScriptAnalysisResult result = successful("v2_statement_create_alter_view_nested_chain.sql");
        assertEquals(4, result.getCounts().getTotal());
        assertInputs(statement(result, 0), "ods.users");
        assertInputs(statement(result, 1), "ods.users_archive");
        assertInputs(statement(result, 3), "dim.statement_outer_view");
        assertColumnSources(statement(result, 3), "dim.statement_user_view.id", "ods.users_archive.id");
        assertColumnSources(statement(result, 3), "dim.statement_outer_view.id", "dim.statement_user_view.id");
        assertColumnSources(statement(result, 3), "dwd.result.id", "dim.statement_outer_view.id");
        assertFalse(unit(result, 3).getObjects().values().stream().anyMatch(object -> "ods.users".equals(object.getName().getDisplayName())));
    }

    @Test
    public void setAndUseEffectsChangeLaterVariableExpansionAndDefaultDatabase() {
        ScriptAnalysisResult result = analyze("v2_statement_session_set_use_variable_substitution.sql", mode);
        assertNoFailed(result);
        assertIgnored(result, 0, "SESSION_SET");
        assertIgnored(result, 1, "SESSION_SET");
        assertIgnored(result, 2, "USE_DATABASE");
        assertEffect(result, 0, Effects.EffectKind.SET_VARIABLE);
        assertEffect(result, 1, Effects.EffectKind.SET_VARIABLE);
        assertEffect(result, 2, Effects.EffectKind.SET_DATABASE);
        Effects.SetVariable source = (Effects.SetVariable) unit(result, 0).getEffects().get(0);
        assertEquals(Effects.Namespace.HIVEVAR, source.getKey().getNamespace());
        assertEquals("source_table", source.getKey().getKey());
        assertEquals("users", source.getValue());
        StatementAnalysisResult insert = result.getStatements().get(3);
        assertEquals(StatementStatus.SUCCESS, insert.getStatus());
        assertTrue(insert.getRawSql().getText().contains("${hivevar:source_table}"));
        assertFalse(insert.getNormalizedSql().getText().contains("${"));
        assertTrue(insert.getNormalizedSql().getText().contains("20261014"));
        assertInputs(statement(result, 3), "ods.users");
        assertColumnSources(statement(result, 3), "dwd.result.id", "ods.users.id");
        assertColumnSources(statement(result, 3), "dwd.result.dt");
    }

    @Test
    public void ignoredPureSelectCteRemainsAvailableToLaterInsert() {
        ScriptAnalysisResult result = analyze("v2_statement_pure_select_cte_session_reuse.sql", mode);
        assertNoFailed(result);
        assertIgnored(result, 0, "PURE_QUERY");
        assertEffect(result, 0, Effects.EffectKind.REGISTER_DEFINITION);
        assertEquals(StatementStatus.SUCCESS, result.getStatements().get(1).getStatus());
        assertInputs(statement(result, 1), "ods.users");
        assertColumnSources(statement(result, 1), "dwd.result.id", "ods.users.id");
        assertColumnSources(statement(result, 1), "dwd.result.name", "ods.users.name");
    }

    @Test
    public void updateKeepsEveryAssignmentAndPredicateDependencies() {
        ScriptAnalysisResult result = successful("v2_statement_update_multiple_assignments.sql");
        assertWrite(result, 0, BoundWrite.WriteMode.UPDATE, "amount", "name");
        assertTrue(write(result, 0).getCondition().isPresent());
        assertInputs(statement(result, 0), "dwd.result");
        assertColumnSources(statement(result, 0), "dwd.result.amount", "dwd.result.amount", "dwd.result.id");
        assertColumnSources(statement(result, 0), "dwd.result.name", "dwd.result.name", "dwd.result.id");
    }

    @Test
    public void deleteKeepsItsPredicateWithoutInventingColumnWrites() {
        ScriptAnalysisResult result = successful("v2_statement_delete_predicate_without_column_assignments.sql");
        assertWrite(result, 0, BoundWrite.WriteMode.DELETE);
        assertTrue(write(result, 0).getCondition().isPresent());
        assertInputs(statement(result, 0), "dwd.result");
        assertTrue(statement(result, 0).getColumnLineages().isEmpty());
    }

    @Test
    public void mergeDerivedSourceRetainsUpdateDeleteInsertAsSeparateWrites() {
        ScriptAnalysisResult result = successful("v2_statement_merge_derived_source_three_actions.sql");
        List<BoundWrite> writes = unit(result, 0).getWrites();
        assertEquals(Arrays.asList(BoundWrite.WriteMode.UPDATE, BoundWrite.WriteMode.DELETE, BoundWrite.WriteMode.APPEND),
                writes.stream().map(BoundWrite::getWriteMode).collect(Collectors.toList()));
        assertEquals(3, writes.stream().map(BoundWrite::getId).distinct().count());
        assertTrue(writes.stream().allMatch(write -> write.getCondition().isPresent()));
        assertTrue(writes.get(1).getAssignments().isEmpty());
        if (mode == LineageParseMode.TABLE_ONLY) {
            assertTrue(writes.stream().allMatch(write -> write.getAssignments().isEmpty()));
        } else {
            assertEquals(2, writes.get(0).getAssignments().size());
            assertEquals(7, writes.get(2).getAssignments().size());
        }
        assertInputs(statement(result, 0), "dwd.result", "ods.users");
        assertColumnSources(statement(result, 0), "dwd.result.id", "ods.users.id", "dwd.result.id");
        assertColumnSources(statement(result, 0), "dwd.result.dt", "ods.users.dt", "dwd.result.id", "ods.users.id");
    }

    @Test
    public void catalogSimulationAppliesAddReplaceRenameAndDropSequentially() {
        ScriptAnalysisResult result = analyzeWithCatalog("v2_statement_catalog_add_replace_rename_drop.sql");
        assertNoFailed(result);
        assertEffect(result, 0, Effects.EffectKind.CREATE_OBJECT);
        assertEffect(result, 1, Effects.EffectKind.ALTER_OBJECT);
        assertEffect(result, 2, Effects.EffectKind.ALTER_OBJECT);
        assertEffect(result, 3, Effects.EffectKind.RENAME_OBJECT);
        assertEffect(result, 5, Effects.EffectKind.DROP_OBJECT);
        for (int index : new int[]{0, 1, 2, 3, 5}) {
            assertEquals(StatementStatus.IGNORED, result.getStatements().get(index).getStatus());
            assertFalse(result.getStatements().get(index).isFormalLineageProduced());
            assertTrue(unit(result, index).getWrites().isEmpty());
        }
        Effects.AlterObject added = (Effects.AlterObject) unit(result, 1).getEffects().get(0);
        assertEquals(Arrays.asList("id", "name", "amount"), added.getReplacement().getSchema().getSegments().stream()
                .filter(segment -> segment instanceof KnownOutputSegment).map(segment -> (KnownOutputSegment) segment)
                .flatMap(segment -> segment.getSlots().stream()).map(slot -> slot.getDisplayName()).collect(Collectors.toList()));
        Effects.RenameObject renamed = (Effects.RenameObject) unit(result, 3).getEffects().get(0);
        assertEquals("dwd.statement_catalog_renamed", renamed.getNewName().getDisplayName());
        assertEquals(StatementStatus.SUCCESS, result.getStatements().get(4).getStatus());
        assertInputs(statement(result, 4), "dwd.statement_catalog_renamed");
        assertColumnSources(statement(result, 4), "dwd.result.amount", "dwd.statement_catalog_renamed.amount");
    }

    @Test
    public void valuesAndBothDirectoryTargetsStayIgnoredAndLaterInsertRecovers() {
        ScriptAnalysisResult result = analyze("v2_statement_values_directory_ignored_then_insert.sql", mode);
        assertNoFailed(result);
        assertIgnored(result, 0, "INSERT_VALUES");
        assertIgnored(result, 1, "NO_OUTPUT_TABLE");
        assertIgnored(result, 2, "NO_OUTPUT_TABLE");
        for (int index = 0; index < 3; index++) {
            assertTrue(unit(result, index).getWrites().isEmpty());
            assertFalse(result.getStatements().get(index).getLineage().isPresent());
        }
        assertEquals(StatementStatus.SUCCESS, result.getStatements().get(3).getStatus());
        assertColumnSources(statement(result, 3), "dwd.result.id", "ods.users.id");
    }

    @Test
    public void loadDataRequiresExplicitCapabilityAndRetainsDeclaredStorageIdentity() {
        String file = "v2_statement_load_data_local_and_distributed.sql";
        ScriptAnalysisResult disabled = analyze(file, mode);
        assertEquals(2, disabled.getCounts().getFailed());
        for (StatementAnalysisResult statement : disabled.getStatements()) {
            assertEquals(StatementStatus.FAILED, statement.getStatus());
            assertEquals("UNSUPPORTED_STATEMENT", statement.getFailureCode().get());
            assertTrue(statement.getDiagnostics().getDiagnostics().stream().anyMatch(diagnostic -> "EXTERNAL_DATA_NOT_REQUESTED".equals(diagnostic.getCode())));
        }
        ScriptAnalysisResult enabled = analyzeWithExternalLineage(file, mode);
        assertNoFailed(enabled);
        assertEquals(BoundWrite.WriteMode.OVERWRITE, write(enabled, 0).getWriteMode());
        assertEquals(BoundWrite.WriteMode.APPEND, write(enabled, 1).getWriteMode());
        assertEquals(PartitionScope.Kind.STATIC, write(enabled, 0).getPartitionScope().getKind());
        String[] paths = {"/tmp/v2_statement_users.tsv", "hdfs:///warehouse/v2_statement_users.tsv"};
        ExternalDatasetRelation.SourceKind[] kinds = {ExternalDatasetRelation.SourceKind.LOCAL_PATH, ExternalDatasetRelation.SourceKind.DISTRIBUTED_PATH};
        for (int index = 0; index < 2; index++) {
            assertEquals("External schema remains unknown", StatementStatus.PARTIAL_SUCCESS, enabled.getStatements().get(index).getStatus());
            assertTrue(enabled.getStatements().get(index).getDiagnostics().getDiagnostics().stream()
                    .anyMatch(diagnostic -> "EXTERNAL_SCHEMA_UNKNOWN".equals(diagnostic.getCode())));
            ExternalDatasetRelation input = (ExternalDatasetRelation) unit(enabled, index).getRelations().stream()
                    .filter(relation -> relation instanceof ExternalDatasetRelation).findFirst().get();
            assertEquals(paths[index], input.getDatasetIdentity());
            assertEquals(kinds[index], input.getSourceKind());
            assertTrue(enabled.getStatements().get(index).isFormalLineageProduced());
            if (mode != LineageParseMode.TABLE_ONLY) {
                assertFalse(write(enabled, index).getUnknownMappings().isEmpty());
            }
            assertColumnSources(statement(enabled, index), "dwd.result.dt");
        }
    }

    @Test
    public void resourceFunctionAndSourceCommandsAreAuditedWithoutExecution() {
        ScriptAnalysisResult result = analyze("v2_statement_resource_function_source_commands.sql", mode);
        assertNoFailed(result);
        String[] types = {"ADD_RESOURCE", "ADD_RESOURCE", "ADD_RESOURCE", "LIST_RESOURCE", "DELETE_RESOURCE", "SOURCE", "CREATE_TEMPORARY_FUNCTION", "DROP_TEMPORARY_FUNCTION"};
        for (int index = 0; index < types.length; index++) {
            assertEquals(types[index], result.getStatements().get(index).getStatementType());
            assertIgnored(result, index, types[index]);
            assertTrue(unit(result, index).getWrites().isEmpty());
            assertTrue(unit(result, index).getEffects().isEmpty());
        }
        assertEquals(StatementStatus.SUCCESS, result.getStatements().get(8).getStatus());
        assertInputs(statement(result, 8), "ods.users");
    }

    @Test
    public void propertiesLocationAndTruncateExplicitlyRetainUnknownCatalogEffects() {
        ScriptAnalysisResult result = analyzeWithCatalog("v2_statement_catalog_properties_location_truncate_boundary.sql");
        assertNoFailed(result);
        assertEffect(result, 0, Effects.EffectKind.CREATE_OBJECT);
        String[] codes = {"CATALOG_ACTION_NOT_BOUND", "CATALOG_ACTION_NOT_BOUND", "TRUNCATE_VERSION_UNKNOWN"};
        for (int index = 1; index < 4; index++) {
            assertEquals(StatementStatus.IGNORED, result.getStatements().get(index).getStatus());
            assertFalse(result.getStatements().get(index).isFormalLineageProduced());
            assertTrue(unit(result, index).getWrites().isEmpty());
            BoundEffect effect = unit(result, index).getEffects().get(0);
            assertTrue(effect instanceof Effects.UnknownEffect);
            assertEquals(Effects.Certainty.UNKNOWN, effect.getCertainty());
            assertEquals(codes[index - 1], ((Effects.UnknownEffect) effect).getDiagnosticCode());
        }
    }

    @Test
    public void externalTableDdlDeclaresSchemaWhileLikeDoesNotInferASourceSchema() {
        ScriptAnalysisResult result = analyzeWithCatalog("v2_statement_external_table_storage_and_like_ddl.sql");
        assertNoFailed(result);
        assertIgnored(result, 0, "CREATE_EXTERNAL_TABLE_DDL");
        assertIgnored(result, 1, "CREATE_TABLE_DDL");
        Effects.CreateObject external = (Effects.CreateObject) unit(result, 0).getEffects().get(0);
        assertEquals("dwd.statement_external", external.getShape().getName().getDisplayName());
        assertEquals(5, external.getShape().getSchema().getSegments().stream()
                .map(segment -> ((KnownOutputSegment) segment).getSlots().size()).mapToInt(Integer::intValue).sum());
        Effects.CreateObject like = (Effects.CreateObject) unit(result, 1).getEffects().get(0);
        assertEquals("CREATE LIKE must retain unknown width until its source schema is inferred",
                com.panfan.hivelineage.v2.ir.quality.SchemaState.UNKNOWN, like.getShape().getSchema().getState());
        assertTrue(unit(result, 1).getWrites().isEmpty());
    }

    private ScriptAnalysisResult successful(String file) {
        ScriptAnalysisResult result = analyze(file, mode);
        assertNoFailed(result);
        for (StatementAnalysisResult statement : result.getStatements()) {
            assertEquals(file + ": " + statement.getDiagnostics().getDiagnostics(), StatementStatus.SUCCESS, statement.getStatus());
            assertTrue(statement.isFormalLineageProduced());
        }
        return result;
    }

    private ScriptAnalysisResult analyzeWithCatalog(String file) {
        LineageAnalyzeRequest legacy = request(file, mode);
        ScriptAnalysisRequest base = new DefaultRequestAdapter().adapt(legacy);
        AnalysisContract old = base.getContract();
        EnumSet<AnalysisCapability> capabilities = EnumSet.copyOf(old.getCapabilities().getCapabilities());
        capabilities.add(AnalysisCapability.CATALOG_SIMULATION);
        AnalysisContract contract = new AnalysisContract(new AnalysisCapabilities(capabilities), old.getVariablePolicy(), old.getDefinitionPolicy(),
                old.getStarMappingPolicy(), old.getPublicationPolicy(), old.isIncludeTemporaryLineage(), old.isIncludeIgnoredStatements(),
                old.isExpandViewLineage(), old.getMaxViewExpandDepth(), old.isFailFast(), old.getFallbackVariableValue());
        ScriptAnalysisRequest nativeRequest = new ScriptAnalysisRequest(base.getTask(), base.getSource(), base.getDialect(), base.getInitialSession(),
                base.getMetadataContext(), contract, base.getResourceLimits());
        WorkflowContext workflow = new WorkflowContext(base.getTask().getWorkflowId(), base.getMetadataContext(),
                new DefaultV1MetadataAdapterFactory().adapt(new LegacyMetadataBinding(Optional.of(legacy.getMetadataProvider()),
                        Optional.of(legacy.getMetadataCache()), base.getMetadataContext(), false)), new DefaultMetadataCache());
        return new DefaultScriptLineageAnalyzer().analyzeScript(nativeRequest, workflow);
    }

    private void assertWrite(ScriptAnalysisResult result, int index, BoundWrite.WriteMode writeMode, String... columns) {
        assertEquals(1, unit(result, index).getWrites().size());
        assertEquals(writeMode, write(result, index).getWriteMode());
        assertEquals(mode == LineageParseMode.TABLE_ONLY ? Collections.emptyList() : Arrays.asList(columns),
                write(result, index).getAssignments().stream().map(assignment -> assignment.getTargetField().getDisplayName()).collect(Collectors.toList()));
    }

    private void assertPartition(ScriptAnalysisResult result, PartitionScope.Kind kind, PartitionScope.AssignmentKind... assignments) {
        PartitionScope scope = write(result, 0).getPartitionScope();
        assertEquals(kind, scope.getKind());
        assertEquals(Arrays.asList(assignments), scope.getAssignments().stream().map(PartitionScope.Assignment::getKind).collect(Collectors.toList()));
    }

    private static void assertIgnored(ScriptAnalysisResult result, int index, String reason) {
        StatementAnalysisResult statement = result.getStatements().get(index);
        assertEquals(StatementStatus.IGNORED, statement.getStatus());
        assertFalse(statement.isFormalLineageProduced());
        assertEquals(reason, statement.getSkipReason().get());
    }

    private static void assertEffect(ScriptAnalysisResult result, int index, Effects.EffectKind kind) {
        assertTrue(kind.name(), unit(result, index).getEffects().stream().anyMatch(effect -> effect.getKind() == kind));
    }

    private static BoundAnalysisUnit unit(ScriptAnalysisResult result, int index) {
        return result.getStatements().get(index).getBoundUnit().orElseThrow(() -> new AssertionError("No bound unit for statement " + index));
    }

    private static BoundWrite write(ScriptAnalysisResult result, int index) {
        return unit(result, index).getWrites().get(0);
    }
}
