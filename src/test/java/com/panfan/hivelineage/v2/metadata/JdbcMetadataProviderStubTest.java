package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.metadata.JdbcMetadataColumnV2Provider;
import com.panfan.hivelineage.metadata.TableIdentifier;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.compat.DefaultLegacyResultProjector;
import com.panfan.hivelineage.v2.compat.HiveScriptLineageAdapter;
import com.panfan.hivelineage.v2.export.DefaultFormalLineageRecordExporter;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Uses the unchanged JDBC provider over real DriverManager/Connection/PreparedStatement calls.
 */
@RunWith(Parameterized.class)
public class JdbcMetadataProviderStubTest {
    private enum Engine {V1, V2}

    private final Engine engine;

    public JdbcMetadataProviderStubTest(Engine engine) {
        this.engine = engine;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> engines() {
        return Arrays.asList(new Object[]{Engine.V1}, new Object[]{Engine.V2});
    }

    @Test
    public void starExpandsActiveJdbcColumnsInDatabaseOrderThroughBothEntrypoints() throws Exception {
        try (JdbcMetadataConnectionStub stub = fixture()) {
            Result result = analyze(stub, "insert overwrite table dwd_it.user_profile_it select * from ods_it.user_base_it",
                    LineageAnalyzeOptions.builder().build());
            StatementLineageResult statement = result.legacy.getStatements().get(0);
            Assert.assertEquals(0, result.legacy.getFailedStatementCount());
            Assert.assertEquals(Integer.valueOf(0), statement.getParseStatusCode());
            Assert.assertEquals(Arrays.asList("dwd_it.user_profile_it.user_id", "dwd_it.user_profile_it.user_name"),
                    statement.getColumnLineages().stream().map(column -> column.getTargetColumn().getQualifiedName())
                            .collect(Collectors.toList()));
            Assert.assertEquals(Collections.singletonList("ods_it.user_base_it.user_id"), sources(statement.getColumnLineages().get(0)));
            Assert.assertEquals(Collections.singletonList("ods_it.user_base_it.user_name"), sources(statement.getColumnLineages().get(1)));
            Assert.assertTrue(statement.getWarnings().isEmpty());
            resources(stub);
        }
    }

    @Test
    public void jdbcSynthesizedViewRetainsLegacyBoundaryAndNativePhysicalSources() throws Exception {
        try (JdbcMetadataConnectionStub stub = fixture()) {
            Result result = analyze(stub, "insert overwrite table dwd_it.user_profile_it(user_id,user_name) "
                    + "select user_id,user_name from dim_it.v_user_it", LineageAnalyzeOptions.builder().expandViewLineage(true).build());
            StatementLineageResult statement = result.legacy.getStatements().get(0);
            Assert.assertEquals(0, result.legacy.getFailedStatementCount());
            Assert.assertEquals("dim_it.v_user_it", statement.getInputObjects().get(0).getQualifiedName());
            Assert.assertEquals(Collections.singletonList("dim_it.v_user_it.user_id"),
                    sources(column(statement, "dwd_it.user_profile_it.user_id")));
            Assert.assertEquals(Collections.singletonList("ods_it.user_base_it.user_id"),
                    sources(column(statement, "dim_it.v_user_it.user_id")));
            Assert.assertEquals("VIEW_DEFINITION", statement.getTableLineages().get(0).getOperation());
            if (result.nativeResult.isPresent()) {
                ScriptAnalysisResult nativeResult = result.nativeResult.get();
                List<MetadataReadRecord> reads = nativeResult.getBasis().get().getMetadataReads().getRecords().stream()
                        .filter(read -> "dim_it.v_user_it".equals(read.getKey().getObjectName().getDisplayName())).collect(Collectors.toList());
                Assert.assertFalse(reads.isEmpty());
                for (MetadataReadRecord read : reads) {
                    Assert.assertEquals(LookupStatus.FOUND, read.getStatus());
                    Assert.assertEquals(ProviderCapabilities.LookupShape.BUNDLED_LOOKUP, read.getLookupShape());
                    Assert.assertEquals(MetadataProvenance.Origin.SYNTHESIZED_DEFINITION, read.getProvenance().getOrigin());
                    Assert.assertTrue(read.getProvenance().getContentFingerprint().isPresent());
                    Assert.assertEquals(nativeResult.getBasis().get().getMetadataReads().getReadContext(), read.getKey().getReadContext());
                }
                Assert.assertEquals(MetadataReadManifest.Consistency.PER_OBJECT_CAPTURE,
                        nativeResult.getBasis().get().getMetadataReads().getConsistency());
                List<String> physical = new DefaultFormalLineageRecordExporter().exportColumns(nativeResult.getStatements().get(0)).stream()
                        .filter(field -> "dwd_it.user_profile_it".equals(field.getTarget().getObject().getDisplayName()))
                        .filter(field -> field.getTarget().getFieldPath().equals(Collections.singletonList("user_id")))
                        .flatMap(field -> field.getSources().stream()).map(field -> field.getObject().getDisplayName()).collect(Collectors.toList());
                Assert.assertTrue(physical.contains("ods_it.user_base_it"));
                Assert.assertFalse(physical.contains("ods_it.failed_source_it"));
            }
            resources(stub);
        }
    }

    @Test
    public void unqualifiedJoinFieldsBindToTheirJdbcDeclaredOwners() throws Exception {
        try (JdbcMetadataConnectionStub stub = fixture()) {
            Result result = analyze(stub, "select * from ods_it.join_left_it l join ods_it.join_right_it r on user_id = manager_id",
                    LineageAnalyzeOptions.builder().parseJoinConditions(true).build());
            JoinCondition join = result.legacy.getStatements().get(0).getJoinConditions().get(0);
            Assert.assertEquals(0, result.legacy.getFailedStatementCount());
            Assert.assertEquals("user_id = manager_id", join.getJoinExpression());
            Assert.assertEquals(Arrays.asList("ods_it.join_left_it.user_id", "ods_it.join_right_it.manager_id"),
                    join.getJoinColumns().stream().map(field -> field.getQualifiedName()).collect(Collectors.toList()));
            Assert.assertEquals(Arrays.asList("ods_it.join_left_it", "ods_it.join_right_it"),
                    join.getDirectJoinObjects().stream().map(object -> object.getQualifiedName()).collect(Collectors.toList()));
            Assert.assertEquals(Arrays.asList("user_id", "manager_id"),
                    join.getDirectJoinColumns().stream().map(field -> field.getQualifiedName()).collect(Collectors.toList()));
            resources(stub);
        }
    }

    @Test
    public void multipleJdbcViewSourcesAreSynthesizedAndRetainedInV2PhysicalProof() throws Exception {
        try (JdbcMetadataConnectionStub stub = fixture()) {
            stub.putView(22, "dim_it.v_user_it", "ods_it.user_other_it", false, 1);
            HiveTableMetadata metadata = provider(stub).getTableMetadata(TableIdentifier.of("default", "dim_it", "v_user_it"));
            Assert.assertEquals("select user_id, user_name from ods_it.user_base_it union all "
                    + "select user_id, user_name from ods_it.user_other_it", metadata.getViewOriginalSql());
            if (engine == Engine.V2) {
                Result result = analyze(stub, "insert overwrite table dwd_it.user_profile_it(user_id,user_name) "
                        + "select user_id,user_name from dim_it.v_user_it", LineageAnalyzeOptions.builder().expandViewLineage(true).build());
                Assert.assertEquals(Arrays.asList("ods_it.user_base_it.user_id", "ods_it.user_other_it.user_id"),
                        sources(column(result.legacy.getStatements().get(0), "dim_it.v_user_it.user_id")));
                List<String> physical = new DefaultFormalLineageRecordExporter().exportColumns(
                                result.nativeResult.get().getStatements().get(0)).stream()
                        .filter(field -> "dwd_it.user_profile_it".equals(field.getTarget().getObject().getDisplayName()))
                        .filter(field -> field.getTarget().getFieldPath().equals(Collections.singletonList("user_id")))
                        .flatMap(field -> field.getSources().stream()).map(field -> field.getObject().getDisplayName()).collect(Collectors.toList());
                Assert.assertTrue(physical.contains("ods_it.user_base_it"));
                Assert.assertTrue(physical.contains("ods_it.user_other_it"));
                Assert.assertFalse(physical.contains("ods_it.failed_source_it"));
            }
            resources(stub);
        }
    }

    @Test
    public void emptyJdbcRowsRemainNullAndAdapterDoesNotInventAuthoritativeAbsence() throws Exception {
        try (JdbcMetadataConnectionStub stub = fixture()) {
            JdbcMetadataColumnV2Provider provider = provider(stub);
            Assert.assertNull(provider.getTableMetadata(TableIdentifier.of("default", "ods_it", "missing_it")));
            MetadataReadContext context = new MetadataReadContext("jdbc-empty", "default", Optional.empty(), Collections.emptyMap());
            QualifiedName name = new QualifiedName(Arrays.asList("ods_it", "missing_it"));
            Assert.assertEquals(LookupStatus.UNKNOWN, adapter(provider, context, false).lookupRelation(name, context).getStatus());
            Assert.assertEquals(LookupStatus.NOT_FOUND, adapter(provider, context, true).lookupRelation(name, context).getStatus());
            Assert.assertNull(provider.getTableMetadata(TableIdentifier.of("default", "ods_it", "missing_it")));
            resources(stub);
        }
    }

    @Test
    public void sqlExceptionsKeepTheirProviderCauseAndAccessErrorClassification() throws Exception {
        try (JdbcMetadataConnectionStub stub = fixture()) {
            JdbcMetadataColumnV2Provider provider = provider(stub);
            TableIdentifier table = TableIdentifier.of("default", "ods_it", "user_base_it");
            SQLException connectionFailure = new SQLException("connection failure", "08006", 31);
            stub.failNextConnection(connectionFailure);
            IllegalStateException first = Assert.assertThrows(IllegalStateException.class, () -> provider.getTableMetadata(table));
            Assert.assertSame(connectionFailure, first.getCause());
            Assert.assertEquals("Failed to query Hive metadata for ods_it.user_base_it", first.getMessage());
            SQLException queryFailure = new SQLException("query failure", "42000", 32);
            stub.failNextQuery(queryFailure);
            IllegalStateException second = Assert.assertThrows(IllegalStateException.class, () -> provider.getTableMetadata(table));
            Assert.assertSame(queryFailure, second.getCause());
            Assert.assertEquals(32, ((SQLException) second.getCause()).getErrorCode());
            MetadataReadContext context = new MetadataReadContext("jdbc-fault", "default", Optional.empty(), Collections.emptyMap());
            MetadataProvider adapted = adapter(provider, context, false);
            WorkflowContext workflow = new WorkflowContext("wf", context, adapted, new DefaultMetadataCache());
            CapturedMetadataAccess access = new CapturedMetadataAccess(workflow, 60_000);
            stub.failNextQuery(new SQLException("recorded failure", "HY000", 33));
            QualifiedName name = new QualifiedName(Arrays.asList("ods_it", "user_base_it"));
            LookupResult<RelationMetadata> lookup = access.lookupRelation(name, context);
            Assert.assertEquals(LookupStatus.ACCESS_ERROR, lookup.getStatus());
            Assert.assertFalse(lookup.getValue().isPresent());
            Assert.assertEquals("METADATA_ACCESS_ERROR", lookup.getDiagnostics().getDiagnostics().get(0).getCode());
            Assert.assertEquals(second.getMessage(), lookup.getDiagnostics().getDiagnostics().get(0).getMessage());
            Assert.assertSame(lookup, access.lookupRelation(name, context));
            Assert.assertEquals(LookupStatus.ACCESS_ERROR, access.manifest().getRecords().get(0).getStatus());
            Assert.assertEquals(LookupStatus.ACCESS_ERROR,
                    new SnapshotMetadataServices(access.snapshot()).lookupRelation(name, context).getStatus());
            HiveTableMetadata recovered = provider.getTableMetadata(table);
            Assert.assertEquals(Arrays.asList("user_id", "user_name"), recovered.getColumns());
            resources(stub);
        }
    }

    private Result analyze(JdbcMetadataConnectionStub stub, String sql, LineageAnalyzeOptions options) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder().workflowId("wf_jdbc_stub").taskId("task_" + engine)
                .taskVersion("V1").defaultDatabase("default").rawScript(sql).options(options)
                .metadataProvider(provider(stub)).metadataCache(new HiveMetadataCache()).build();
        if (engine == Engine.V1) {
            return new Result(new HiveScriptLineageAnalyzer().analyzeScript(request), Optional.empty());
        }
        ScriptAnalysisResult nativeResult = new HiveScriptLineageAdapter().analyzeNative(request);
        return new Result(new DefaultLegacyResultProjector().project(nativeResult).getResult(), Optional.of(nativeResult));
    }

    private static JdbcMetadataColumnV2Provider provider(JdbcMetadataConnectionStub stub) {
        return new JdbcMetadataColumnV2Provider(stub.getUrl(), "stub_user", "stub_password");
    }

    private static MetadataProvider adapter(JdbcMetadataColumnV2Provider provider, MetadataReadContext context, boolean authoritativeNull) {
        LegacyMetadataBinding binding = new LegacyMetadataBinding(Optional.of(provider), Optional.empty(), context, authoritativeNull);
        return new DefaultV1MetadataAdapterFactory().adapt(binding);
    }

    private static ColumnLineage column(StatementLineageResult statement, String target) {
        return statement.getColumnLineages().stream().filter(field -> target.equals(field.getTargetColumn().getQualifiedName())).findFirst().get();
    }

    private static List<String> sources(ColumnLineage column) {
        return column.getSourceColumns().stream().map(field -> field.getQualifiedName()).collect(Collectors.toList());
    }

    private static JdbcMetadataConnectionStub fixture() throws Exception {
        JdbcMetadataConnectionStub stub = JdbcMetadataConnectionStub.open();
        stub.putColumn(1, "ods_it", "user_base_it", "user_name", 2, true);
        stub.putColumn(2, "ods_it", "user_base_it", "user_id", 1, true);
        stub.putColumn(3, "ods_it", "user_base_it", "inactive_column", 0, false);
        stub.putColumn(4, "dwd_it", "user_profile_it", "user_id", 1, true);
        stub.putColumn(5, "dwd_it", "user_profile_it", "user_name", 2, true);
        stub.putColumn(6, "dim_it", "v_user_it", "user_id", 1, true);
        stub.putColumn(7, "dim_it", "v_user_it", "user_name", 2, true);
        stub.putColumn(8, "ods_it", "user_other_it", "user_id", 1, true);
        stub.putColumn(9, "ods_it", "user_other_it", "user_name", 2, true);
        stub.putColumn(10, "ods_it", "join_left_it", "user_id", 1, true);
        stub.putColumn(11, "ods_it", "join_right_it", "manager_id", 1, true);
        stub.putView(20, "dim_it.v_user_it", "ods_it.user_base_it", false, 3);
        stub.putView(21, "v_user_it", "ODS_IT.USER_BASE_IT", false, 2);
        stub.putView(23, "dim_it.v_user_it", "ods_it.failed_source_it", true, 4);
        return stub;
    }

    private static void resources(JdbcMetadataConnectionStub stub) {
        Assert.assertTrue(stub.getStats().getOpenedConnections() > 0);
        Assert.assertEquals(0, stub.getStats().getOpenConnections());
        Assert.assertEquals(0, stub.getStats().getOpenStatements());
        Assert.assertEquals(0, stub.getStats().getOpenResultSets());
        stub.assertAllResourcesClosed();
    }

    private static final class Result {
        private final LineageAnalyzeResult legacy;
        private final Optional<ScriptAnalysisResult> nativeResult;

        private Result(LineageAnalyzeResult legacy, Optional<ScriptAnalysisResult> nativeResult) {
            this.legacy = legacy;
            this.nativeResult = nativeResult;
        }
    }
}
