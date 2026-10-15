package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.api.StatementAnalysisResult;
import com.panfan.hivelineage.v2.api.StatementStatus;
import com.panfan.hivelineage.v2.frontend.StageOutcome;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.relation.KnownOutputSegment;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.syntax.ColumnDefinitionSyntax;
import com.panfan.hivelineage.v2.syntax.CommandStatementSyntax;
import com.panfan.hivelineage.v2.syntax.DataTypeSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionBodySyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionStatementSyntax;
import com.panfan.hivelineage.v2.syntax.DelimitedRowFormatSyntax;
import com.panfan.hivelineage.v2.syntax.FunctionCommandSyntax;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.LiteralSyntax;
import com.panfan.hivelineage.v2.syntax.OtherStatementSyntax;
import com.panfan.hivelineage.v2.syntax.SortDirectionSyntax;
import com.panfan.hivelineage.v2.syntax.StorageSpecificationSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxName;
import com.panfan.hivelineage.v2.syntax.SyntaxResult;
import com.panfan.hivelineage.v2.testing.GeneratedSqlSupport;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.analyzeWithCatalog;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertColumnSources;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertInputs;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertNoFailed;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.statement;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.syntax;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Actual file-backed frontend structure and native audit/effect contracts in all three V2 modes.
 */
@RunWith(Parameterized.class)
public class HiveDdlSyntaxIntegrationTest {
    private final LineageParseMode mode;

    public HiveDdlSyntaxIntegrationTest(LineageParseMode mode) {
        this.mode = mode;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> modes() {
        return Arrays.stream(LineageParseMode.values()).map(mode -> new Object[]{mode}).collect(Collectors.toList());
    }

    public static Set<String> scenarioFiles() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                "v2_ddl_syntax_primitive_parameterized_types.sql",
                "v2_ddl_syntax_nested_complex_types.sql",
                "v2_ddl_syntax_comments_partition_buckets_delimiters.sql",
                "v2_ddl_syntax_serde_class_properties.sql",
                "v2_ddl_syntax_generic_storage_formats.sql",
                "v2_ddl_syntax_temporary_external_flags.sql",
                "v2_ddl_syntax_if_exists_like_options.sql",
                "v2_ddl_syntax_view_explicit_columns_comments.sql",
                "v2_ddl_syntax_view_or_replace_definition.sql",
                "v2_ddl_syntax_custom_storage_audit_boundary.sql",
                "v2_ddl_syntax_skew_constraints_audit_boundary.sql",
                "v2_ddl_syntax_permanent_function_resources.sql",
                "v2_ddl_syntax_macros_reload_functions_audit.sql",
                "v2_ddl_syntax_database_schema_lifecycle_audit.sql",
                "v2_ddl_syntax_materialized_view_lifecycle_audit.sql",
                "v2_ddl_syntax_import_export_unsupported_boundary.sql",
                "v2_ddl_syntax_statistics_msck_audit.sql",
                "v2_ddl_syntax_show_describe_explain_audit.sql",
                "v2_ddl_syntax_bucket_without_sorted_columns.sql",
                "v2_ddl_syntax_malformed_nonquery_recovery_then_insert.sql")));
    }

    /**
     * Only this non-query malformed DDL is deliberately recovered as PARTIAL by the frontend.
     */
    public static Map<String, Set<Integer>> expectedFrontendPartial() {
        Map<String, Set<Integer>> expected = new LinkedHashMap<>();
        expected.put("v2_ddl_syntax_malformed_nonquery_recovery_then_insert.sql", Collections.singleton(0));
        return Collections.unmodifiableMap(expected);
    }

    @Test
    public void primitiveTypesAndNumericParametersRemainStructural() {
        String file = "v2_ddl_syntax_primitive_parameterized_types.sql";
        List<ColumnDefinitionSyntax> columns = definition(file, 0).getSchema().getColumns();
        assertEquals(Arrays.asList("boolean", "tinyint", "smallint", "int", "bigint", "float", "double", "decimal", "char",
                        "varchar", "string", "binary", "timestamp", "date"),
                columns.stream().map(column -> name(column.getType().getTypeName())).collect(Collectors.toList()));
        assertEquals(Arrays.asList(18, 4), columns.get(7).getType().getNumericArguments());
        assertEquals(Collections.singletonList(8), columns.get(8).getType().getNumericArguments());
        assertEquals(Collections.singletonList(64), columns.get(9).getType().getNumericArguments());
        assertDeclarationsIgnored(file, 1);
    }

    @Test
    public void recursiveArrayMapStructAndUnionDeclarationsKeepTheirChildren() {
        String file = "v2_ddl_syntax_nested_complex_types.sql";
        List<ColumnDefinitionSyntax> columns = definition(file, 0).getSchema().getColumns();
        assertEquals(5, columns.size());
        DataTypeSyntax tags = columns.get(1).getType();
        assertEquals("list", name(tags.getTypeName()));
        assertEquals("string", name(tags.getTypeArguments().get(0).getTypeName()));
        DataTypeSyntax attributes = columns.get(2).getType();
        assertEquals("map", name(attributes.getTypeName()));
        assertEquals(Arrays.asList(12, 2), attributes.getTypeArguments().get(1).getTypeArguments().get(0).getNumericArguments());
        DataTypeSyntax profile = columns.get(3).getType();
        assertEquals("struct", name(profile.getTypeName()));
        assertEquals(Arrays.asList("city", "account"), profile.getFields().stream().map(field -> field.getName().getValue()).collect(Collectors.toList()));
        assertEquals("labels", profile.getFields().get(1).getType().getFields().get(1).getName().getValue());
        DataTypeSyntax union = columns.get(4).getType();
        assertEquals("uniontype", name(union.getTypeName()));
        assertEquals(3, union.getTypeArguments().get(0).getTypeArguments().size());
        assertDeclarationsIgnored(file, 1);
        ScriptAnalysisResult catalog = GeneratedSqlSupport.analyzeWithCatalog(file, mode);
        GeneratedSqlSupport.assertNoFailed(catalog);
        Effects.CreateObject created = (Effects.CreateObject) catalog.getStatements().get(0).getBoundUnit().get().getEffects().get(0);
        List<TypeRef> types = created.getShape().getSchema().getSegments().stream()
                .map(segment -> (KnownOutputSegment) segment).flatMap(segment -> segment.getSlots().stream())
                .map(slot -> slot.getType()).collect(Collectors.toList());
        assertEquals(Arrays.asList(TypeRef.Kind.INTEGER, TypeRef.Kind.ARRAY, TypeRef.Kind.MAP, TypeRef.Kind.STRUCT, TypeRef.Kind.UNION),
                types.stream().map(TypeRef::getKind).collect(Collectors.toList()));
        assertEquals(TypeRef.Kind.STRING, types.get(1).getArguments().get(0).getKind());
        assertEquals(TypeRef.Kind.ARRAY, types.get(2).getArguments().get(1).getKind());
        assertEquals(TypeRef.Kind.DECIMAL, types.get(2).getArguments().get(1).getArguments().get(0).getKind());
        assertEquals(TypeRef.Kind.ARRAY, types.get(3).getFields().get(1).getType().getFields().get(1).getType().getKind());
        assertEquals(Arrays.asList(TypeRef.Kind.INTEGER, TypeRef.Kind.STRING, TypeRef.Kind.ARRAY),
                types.get(4).getArguments().stream().map(TypeRef::getKind).collect(Collectors.toList()));
        assertEquals(TypeRef.Kind.STRUCT, types.get(4).getArguments().get(2).getArguments().get(0).getKind());
    }

    @Test
    public void commentsPartitionBucketsAndEveryDelimiterSurviveNativeHiveWrappers() {
        String file = "v2_ddl_syntax_comments_partition_buckets_delimiters.sql";
        DefinitionBodySyntax body = definition(file, 0);
        assertTrue(body.getOptions().isExternal());
        assertTrue(body.getOptions().isIfNotExists());
        assertEquals("full DDL options", body.getOptions().getComment());
        assertEquals("user identifier", body.getSchema().getColumns().get(0).getComment());
        assertEquals("business date", body.getSchema().getPartitionColumns().get(0).getComment());
        assertEquals(Arrays.asList("dt", "region"), body.getSchema().getPartitionColumns().stream().map(column -> column.getName().getValue()).collect(Collectors.toList()));
        assertEquals("integration", body.getOptions().getProperties().get("owner"));
        assertEquals("30", body.getOptions().getProperties().get("retention"));
        assertEquals("hdfs:///warehouse/ddl_full_options", ((LiteralSyntax) body.getOptions().getLocation()).getDecodedValue());
        StorageSpecificationSyntax storage = body.getSchema().getStorage();
        assertEquals("TEXTFILE", name(storage.getFileFormat()).toUpperCase(java.util.Locale.ROOT));
        assertEquals(Collections.singletonList("id"), storage.getBucketColumns().stream().map(IdentifierPart::getValue).collect(Collectors.toList()));
        assertEquals("8", ((LiteralSyntax) storage.getBucketCount()).getDecodedValue());
        assertEquals(Arrays.asList(SortDirectionSyntax.DESC, SortDirectionSyntax.ASC), storage.getSortedBy().stream().map(item -> item.getDirection()).collect(Collectors.toList()));
        DelimitedRowFormatSyntax row = storage.getRowFormat();
        assertEquals(",", row.getFieldsTerminatedBy());
        assertEquals("\\", row.getEscapedBy());
        assertEquals(":", row.getCollectionItemsTerminatedBy());
        assertEquals("=", row.getMapKeysTerminatedBy());
        assertEquals("\\n", row.getLinesTerminatedBy());
        assertEquals("NULL", row.getNullDefinedAs());
        ScriptAnalysisResult result = assertDeclarationsIgnored(file, 1);
        Effects.CreateObject effect = (Effects.CreateObject) result.getStatements().get(0).getBoundUnit().get().getEffects().get(0);
        assertEquals("dwd.ddl_full_options", effect.getShape().getName().getDisplayName());
    }

    @Test
    public void serdeClassAndSerdePropertiesAreSeparateFromTableProperties() {
        String file = "v2_ddl_syntax_serde_class_properties.sql";
        DefinitionBodySyntax body = definition(file, 0);
        StorageSpecificationSyntax storage = body.getSchema().getStorage();
        assertEquals("example.JsonSerde", storage.getSerde().getClassName());
        assertEquals("|", storage.getSerde().getProperties().get("separator"));
        assertEquals("NA", storage.getSerde().getProperties().get("null.token"));
        assertEquals(Collections.singletonMap("owner", "integration"), body.getOptions().getProperties());
        assertEquals("ORC", name(storage.getFileFormat()).toUpperCase(java.util.Locale.ROOT));
        assertDeclarationsIgnored(file, 1);
    }

    @Test
    public void genericStoredAsFormatsAreNamesWithoutDataInputs() {
        String file = "v2_ddl_syntax_generic_storage_formats.sql";
        String[] formats = {"TEXTFILE", "SEQUENCEFILE", "RCFILE", "ORC", "PARQUET", "AVRO", "JSONFILE"};
        for (int index = 0; index < formats.length; index++) {
            assertEquals(formats[index], name(definition(file, index).getSchema().getStorage().getFileFormat()).toUpperCase(java.util.Locale.ROOT));
        }
        assertDeclarationsIgnored(file, formats.length);
    }

    @Test
    public void temporaryAndExternalDeclarationsKeepTheirIndependentFlags() {
        String file = "v2_ddl_syntax_temporary_external_flags.sql";
        assertTrue(definition(file, 0).getOptions().isTemporary());
        assertFalse(definition(file, 0).getOptions().isExternal());
        assertEquals("temporary identifier", definition(file, 0).getSchema().getColumns().get(0).getComment());
        assertTrue(definition(file, 1).getOptions().isExternal());
        assertFalse(definition(file, 1).getOptions().isTemporary());
        assertDeclarationsIgnored(file, 2);
    }

    @Test
    public void ifFlagsAndLikeSourceNameDoNotInventDataWrites() {
        String file = "v2_ddl_syntax_if_exists_like_options.sql";
        assertTrue(definition(file, 0).getOptions().isIfNotExists());
        DefinitionBodySyntax like = definition(file, 1);
        assertTrue(like.getOptions().isIfNotExists());
        assertTrue(like.getOptions().isExternal());
        assertEquals("ods.users", name(like.getSchema().getLikeTable()));
        assertTrue(like.getSchema().getColumns().isEmpty());
        assertTrue(definition(file, 2).getOptions().isIfExists());
        assertTrue(definition(file, 3).getOptions().isIfExists());
        assertDeclarationsIgnored(file, 4);
    }

    @Test
    public void explicitViewAliasesCommentsAndParenthesizedDerivedQueryProduceMappedLineage() {
        String file = "v2_ddl_syntax_view_explicit_columns_comments.sql";
        DefinitionBodySyntax body = definition(file, 1);
        assertEquals(Arrays.asList("user_key", "display_name"), body.getSchema().getColumns().stream().map(column -> column.getName().getValue()).collect(Collectors.toList()));
        assertEquals("view user key", body.getSchema().getColumns().get(0).getComment());
        assertEquals("aliased nested view", body.getOptions().getComment());
        assertEquals("integration", body.getOptions().getProperties().get("owner"));
        ScriptAnalysisResult result = analyzeWithCatalog(file, mode);
        assertNoFailed(result);
        assertEquals(3, result.getStatements().size());
        assertEquals(StatementStatus.IGNORED, result.getStatements().get(0).getStatus());
        assertEquals(StatementStatus.SUCCESS, result.getStatements().get(1).getStatus());
        assertTrue(result.getStatements().get(1).isFormalLineageProduced());
        assertTrue(result.getStatements().get(1).getBoundUnit().get().getEffects().stream()
                .allMatch(effect -> effect.getCertainty() == Effects.Certainty.CERTAIN));
        assertInputs(statement(result, 1), "ods.users");
        assertColumnSources(statement(result, 1), "dim.ddl_alias_view.user_key", "ods.users.id");
        assertColumnSources(statement(result, 1), "dim.ddl_alias_view.display_name", "ods.users.name");
        assertEquals(StatementStatus.SUCCESS, result.getStatements().get(2).getStatus());
        assertColumnSources(statement(result, 2), "dwd.result.id", "dim.ddl_alias_view.user_key");
        assertColumnSources(statement(result, 2), "dim.ddl_alias_view.user_key", "ods.users.id");
        assertColumnSources(statement(result, 2), "dwd.result.name", "dim.ddl_alias_view.display_name");
    }

    @Test
    public void orReplaceViewOptionAndReplacementAliasesFlowToLaterReads() {
        String file = "v2_ddl_syntax_view_or_replace_definition.sql";
        assertFalse(definition(file, 0).getOptions().isOrReplace());
        DefinitionBodySyntax body = definition(file, 1);
        assertTrue(body.getOptions().getOrReplace());
        assertEquals("replacement definition", body.getOptions().getComment());
        ScriptAnalysisResult result = analyzeWithCatalog(file, mode);
        assertNoFailed(result);
        assertEquals(3, result.getStatements().size());
        assertTrue(result.getStatements().stream().allMatch(item -> item.getStatus() == StatementStatus.SUCCESS && item.isFormalLineageProduced()));
        assertInputs(statement(result, 1), "ods.users_archive");
        assertColumnSources(statement(result, 1), "dim.ddl_replace_view.user_key", "ods.users_archive.id");
        assertColumnSources(statement(result, 2), "dwd.result.id", "dim.ddl_replace_view.user_key");
        assertColumnSources(statement(result, 2), "dim.ddl_replace_view.user_key", "ods.users_archive.id");
    }

    @Test
    public void customFormatsAndHandlersRemainExplicitlyUnmodeledStorageBoundaries() {
        String file = "v2_ddl_syntax_custom_storage_audit_boundary.sql";
        for (int index = 0; index < 2; index++) {
            StorageSpecificationSyntax storage = definition(file, index).getSchema().getStorage();
            assertNull(storage.getFileFormat());
            assertNull(storage.getSerde());
            assertNull(storage.getRowFormat());
        }
        assertDeclarationsIgnored(file, 2);
    }

    @Test
    public void constraintsCannotBeMisclassifiedAsColumnComments() {
        String file = "v2_ddl_syntax_skew_constraints_audit_boundary.sql";
        assertEquals(2, definition(file, 0).getSchema().getColumns().size());
        List<ColumnDefinitionSyntax> columns = definition(file, 1).getSchema().getColumns();
        assertNull(columns.get(0).getComment());
        assertNull(columns.get(1).getComment());
        assertEquals("actual amount comment", columns.get(2).getComment());
        assertDeclarationsIgnored(file, 2);
    }

    @Test
    public void permanentFunctionCommandsPreserveAllResourcesWithoutLoadingThem() {
        String file = "v2_ddl_syntax_permanent_function_resources.sql";
        List<SyntaxResult> parsed = normalized(file);
        FunctionCommandSyntax create = (FunctionCommandSyntax) ((CommandStatementSyntax) parsed.get(0).getStatement()).getCommand();
        assertFalse(create.isTemporary());
        assertFalse(create.isDrop());
        assertEquals("default.ddl_function", name(create.getName()));
        assertEquals("example.GeneratedFunction", create.getClassName());
        assertEquals(Arrays.asList("jar", "file", "archive"), create.getResources().stream().map(item -> item.getResourceType()).collect(Collectors.toList()));
        assertEquals(Arrays.asList("hdfs:///lib/generated.jar", "hdfs:///lib/generated.conf", "hdfs:///lib/generated.zip"), create.getResources().stream().map(item -> item.getUri()).collect(Collectors.toList()));
        FunctionCommandSyntax drop = (FunctionCommandSyntax) ((CommandStatementSyntax) parsed.get(1).getStatement()).getCommand();
        assertTrue(drop.isDrop());
        assertFalse(drop.isTemporary());
        ScriptAnalysisResult result = assertDeclarationsIgnored(file, 2);
        assertEquals("CREATE_FUNCTION", result.getStatements().get(0).getStatementType());
        assertEquals("DROP_FUNCTION", result.getStatements().get(1).getStatementType());
        assertTrue(result.getStatements().stream().allMatch(item -> item.getBoundUnit().get().getEffects().isEmpty()));
    }

    @Test
    public void macrosAndFunctionReloadAreAuditOnly() {
        assertOtherAudit("v2_ddl_syntax_macros_reload_functions_audit.sql", "CREATE_TEMPORARY_MACRO", "DROP_TEMPORARY_MACRO", "RELOADFUNCTIONS");
    }

    @Test
    public void databaseAndSchemaLifecycleDoesNotSimulateNamespacesOrPermissions() {
        assertOtherAudit("v2_ddl_syntax_database_schema_lifecycle_audit.sql", "CREATE_DATABASE", "CREATE_DATABASE",
                "ALTERDATABASE_PROPERTIES", "ALTERDATABASE_OWNER", "ALTERDATABASE_LOCATION", "DROP_DATABASE", "DROP_DATABASE");
    }

    @Test
    public void materializedViewWritesFailExplicitlyWhileDropRemainsAuditOnly() {
        String file = "v2_ddl_syntax_materialized_view_lifecycle_audit.sql";
        List<SyntaxResult> parsed = normalized(file);
        assertEquals("CREATE_MATERIALIZED_VIEW", ((OtherStatementSyntax) parsed.get(0).getStatement()).getClassification());
        assertEquals("ALTER_MATERIALIZED_VIEW_REBUILD", ((OtherStatementSyntax) parsed.get(1).getStatement()).getClassification());
        assertEquals("DROP_MATERIALIZED_VIEW", ((OtherStatementSyntax) parsed.get(2).getStatement()).getClassification());
        ScriptAnalysisResult result = analyzeWithCatalog(file, mode);
        assertEquals(3, result.getStatements().size());
        assertUnsupportedWithoutEffects(result.getStatements().get(0));
        assertUnsupportedWithoutEffects(result.getStatements().get(1));
        assertEquals(StatementStatus.IGNORED, result.getStatements().get(2).getStatus());
        assertFalse(result.getStatements().get(2).getBoundUnit().isPresent());
        assertFalse(result.getStatements().get(2).isFormalLineageProduced());
    }

    @Test
    public void importExportFailExplicitlyAndLaterOrdinaryInsertContinues() {
        String file = "v2_ddl_syntax_import_export_unsupported_boundary.sql";
        List<SyntaxResult> parsed = normalized(file);
        assertEquals("IMPORT", ((OtherStatementSyntax) parsed.get(0).getStatement()).getClassification());
        assertEquals("EXPORT", ((OtherStatementSyntax) parsed.get(1).getStatement()).getClassification());
        ScriptAnalysisResult result = analyzeWithCatalog(file, mode);
        assertEquals(3, result.getStatements().size());
        assertUnsupportedWithoutEffects(result.getStatements().get(0));
        assertUnsupportedWithoutEffects(result.getStatements().get(1));
        assertEquals(StatementStatus.SUCCESS, result.getStatements().get(2).getStatus());
        assertColumnSources(statement(result, 2), "dwd.result.id", "ods.users.id");
    }

    @Test
    public void statisticsAndMetastoreRepairAreAuditedWithoutExecution() {
        assertOtherAudit("v2_ddl_syntax_statistics_msck_audit.sql", "ANALYZE", "ANALYZE", "MSCK");
    }

    @Test
    public void showDescribeAndExplainDoNotPublishPlanQueriesAsWrites() {
        assertOtherAudit("v2_ddl_syntax_show_describe_explain_audit.sql", "SHOWDATABASES", "SHOWTABLES", "SHOWVIEWS",
                "SHOWFUNCTIONS", "SHOWPARTITIONS", "SHOW_TBLPROPERTIES", "SHOW_CREATETABLE", "DESCTABLE", "DESCDATABASE", "DESCFUNCTION", "EXPLAIN");
    }

    @Test
    public void bucketCountWithoutSortedByDoesNotBecomeAnInvalidSort() {
        String file = "v2_ddl_syntax_bucket_without_sorted_columns.sql";
        StorageSpecificationSyntax storage = definition(file, 0).getSchema().getStorage();
        assertEquals(Arrays.asList("id", "name"), storage.getBucketColumns().stream().map(IdentifierPart::getValue).collect(Collectors.toList()));
        assertTrue(storage.getSortedBy().isEmpty());
        assertEquals("4", ((LiteralSyntax) storage.getBucketCount()).getDecodedValue());
        assertDeclarationsIgnored(file, 1);
    }

    @Test
    public void malformedNonqueryDdlRecoveryRetainsDiagnosticsAndContinuesTheScript() {
        String file = "v2_ddl_syntax_malformed_nonquery_recovery_then_insert.sql";
        List<SyntaxResult> parsed = normalized(file);
        assertEquals(StageOutcome.PARTIAL, parsed.get(0).getOutcome());
        assertEquals("INVALID_DDL", ((OtherStatementSyntax) parsed.get(0).getStatement()).getUnsupportedReason());
        assertTrue(parsed.get(0).getDiagnostics().stream().anyMatch(item -> "HIVE_SYNTAX_ERROR".equals(item.getCode())));
        ScriptAnalysisResult result = analyzeWithCatalog(file, mode);
        assertNoFailed(result);
        assertEquals(StatementStatus.IGNORED, result.getStatements().get(0).getStatus());
        assertFalse(result.getStatements().get(0).isFormalLineageProduced());
        assertFalse(result.getStatements().get(0).getBoundUnit().isPresent());
        assertEquals(StatementStatus.SUCCESS, result.getStatements().get(1).getStatus());
        assertColumnSources(statement(result, 1), "dwd.result.id", "ods.users.id");
    }

    private DefinitionBodySyntax definition(String file, int index) {
        return ((DefinitionStatementSyntax) normalized(file).get(index).getStatement()).getDefinition();
    }

    private List<SyntaxResult> normalized(String file) {
        List<SyntaxResult> result = syntax(file);
        Set<Integer> partial = expectedFrontendPartial().getOrDefault(file, Collections.emptySet());
        for (int index = 0; index < result.size(); index++) {
            assertEquals(file + " statement " + index, partial.contains(index) ? StageOutcome.PARTIAL : StageOutcome.SUCCESS, result.get(index).getOutcome());
            assertNotNull(result.get(index).getStatement());
        }
        return result;
    }

    private ScriptAnalysisResult assertDeclarationsIgnored(String file, int count) {
        ScriptAnalysisResult result = analyzeWithCatalog(file, mode);
        assertNoFailed(result);
        assertEquals(count, result.getStatements().size());
        for (StatementAnalysisResult item : result.getStatements()) {
            assertEquals(StatementStatus.IGNORED, item.getStatus());
            assertFalse(item.isFormalLineageProduced());
            assertFalse(item.getLineage().isPresent());
            assertTrue(item.getBoundUnit().isPresent());
            assertTrue(item.getBoundUnit().get().getWrites().isEmpty());
        }
        return result;
    }

    private void assertOtherAudit(String file, String... kinds) {
        List<SyntaxResult> parsed = normalized(file);
        assertEquals(kinds.length, parsed.size());
        ScriptAnalysisResult result = analyzeWithCatalog(file, mode);
        assertNoFailed(result);
        assertEquals(kinds.length, result.getStatements().size());
        for (int index = 0; index < kinds.length; index++) {
            assertEquals(kinds[index], ((OtherStatementSyntax) parsed.get(index).getStatement()).getClassification());
            StatementAnalysisResult item = result.getStatements().get(index);
            assertEquals(StatementStatus.IGNORED, item.getStatus());
            assertFalse(item.isFormalLineageProduced());
            assertFalse(item.getBoundUnit().isPresent());
            assertFalse(item.getLineage().isPresent());
        }
    }

    private static String name(SyntaxName name) {
        return name.getParts().stream().map(IdentifierPart::getValue).collect(Collectors.joining("."));
    }

    private static void assertUnsupportedWithoutEffects(StatementAnalysisResult item) {
        assertEquals(StatementStatus.FAILED, item.getStatus());
        assertEquals("UNSUPPORTED_STATEMENT", item.getFailureCode().get());
        assertFalse(item.isFormalLineageProduced());
        assertFalse(item.getBoundUnit().isPresent());
        assertFalse(item.getLineage().isPresent());
    }
}
