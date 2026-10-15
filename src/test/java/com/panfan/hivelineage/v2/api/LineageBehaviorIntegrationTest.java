package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.v2.analysis.JoinFact;
import com.panfan.hivelineage.v2.analysis.JoinFacts;
import com.panfan.hivelineage.v2.analysis.JoinKind;
import com.panfan.hivelineage.v2.compat.DefaultLegacyResultProjector;
import com.panfan.hivelineage.v2.compat.DefaultRequestAdapter;
import com.panfan.hivelineage.v2.compat.HiveScriptLineageAdapter;
import com.panfan.hivelineage.v2.dialect.hive.HiveDialectFactory;
import com.panfan.hivelineage.v2.dialect.hive.HiveParseArtifact;
import com.panfan.hivelineage.v2.export.ColumnAddress;
import com.panfan.hivelineage.v2.export.DefaultJoinConditionRecordExporter;
import com.panfan.hivelineage.v2.export.JoinConditionRecord;
import com.panfan.hivelineage.v2.frontend.DefaultScriptPreprocessor;
import com.panfan.hivelineage.v2.frontend.ScriptPreprocessor;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataAccessFactory;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.DefaultV1MetadataAdapterFactory;
import com.panfan.hivelineage.v2.metadata.LegacyMetadataBinding;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;
import com.panfan.hivelineage.v2.semantic.DefaultSemanticBinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Executable semantic assertions for the old OpenSpec, not a parse-success census.
 */
public class LineageBehaviorIntegrationTest {
    @Test
    public void defaultDatabaseNormalizesOnPhysicalResolutionAndQualifiedObjectsBypassInvalidDefaults() {
        LineageAnalyzeOptions options = options(true, LineageParseMode.TABLE_AND_COLUMN, false);
        HiveMetadataProvider metadata = table -> HiveTableMetadata.table(table, Collections.singletonList("id"));
        for (String database : Arrays.asList(" `Dw` ", "DW")) {
            LineageAnalyzeResult result = new HiveScriptLineageAdapter().analyzeScript(request(
                    "insert into t(id) select id from a", options, metadata, database));
            assertEquals(database, 0, result.getFailedStatementCount());
            StatementLineageResult statement = result.getStatements().get(0);
            assertEquals("dw.t", statement.getOutputObject().getQualifiedName());
            assertEquals("dw.a", statement.getInputObjects().get(0).getQualifiedName());
            assertSources(column(statement, "id"), "dw.a.id");
        }
        for (String database : Arrays.asList("db.foo", "d b", "`Dw", "Dw`", "``", "db\u00a0foo")) {
            LineageAnalyzeResult invalidSource = new HiveScriptLineageAdapter().analyzeScript(request(
                    "insert into dwd.t(id) select id from a", options, metadata, database));
            assertEquals(database, 1, invalidSource.getFailedStatementCount());
            LineageAnalyzeResult invalidTarget = new HiveScriptLineageAdapter().analyzeScript(request(
                    "insert into t(id) select id from ods.a", options, metadata, database));
            assertEquals(database, 1, invalidTarget.getFailedStatementCount());
            LineageAnalyzeResult qualified = new HiveScriptLineageAdapter().analyzeScript(request(
                    "insert into dwd.t(id) select id from ods.a", options, metadata, database));
            assertEquals(database, 0, qualified.getFailedStatementCount());
            assertEquals("dwd.t", qualified.getStatements().get(0).getOutputObject().getQualifiedName());
            assertSources(column(qualified.getStatements().get(0), "id"), "ods.a.id");
        }
        HiveMetadataProvider noReads = table -> {
            throw new AssertionError("Ignored query must not resolve physical objects");
        };
        LineageAnalyzeResult ignored = new HiveScriptLineageAdapter().analyzeScript(request(
                "select id from a", options, noReads, "db.foo"));
        assertEquals(0, ignored.getFailedStatementCount());
        assertEquals(1, ignored.getIgnoredStatementCount());
    }

    @Test
    public void explicitlyDifferentDatabaseIsNotShadowedByTemporaryBasename() {
        LineageAnalyzeResult result = analyze("create temporary table original_db.tmp as select id from ods.a; "
                        + "insert into dwd.t(id) select id from another_db.tmp; "
                        + "insert into dwd.t(id) select id from original_db.tmp; insert into dwd.t(id) select id from tmp",
                options(true, LineageParseMode.TABLE_AND_COLUMN, false),
                table -> table.getQualifiedName().equals("another_db.tmp") ? HiveTableMetadata.table(table, Collections.singletonList("id"))
                        : provider().getTableMetadata(table));
        assertEquals(0, result.getFailedStatementCount());
        assertSources(column(result.getStatements().get(1), "id"), "another_db.tmp.id");
        assertSources(column(result.getStatements().get(2), "id"), "ods.a.id");
        assertSources(column(result.getStatements().get(3), "id"), "ods.a.id");
    }

    @Test
    public void temporaryNamespaceNormalizesBusinessIdsWithoutChangingTheirIdentity() {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder().workflowId("  WF-`A` / !! ")
                .taskId("Task.中文///").taskVersion("V1").defaultDatabase("default")
                .rawScript("create temporary table source_db.tmp as select id from ods.a")
                .options(options(true, LineageParseMode.TABLE_AND_COLUMN, false)).metadataProvider(provider()).build();
        StatementLineageResult result = new HiveScriptLineageAdapter().analyzeScript(request).getStatements().get(0);
        assertEquals("seele_tmp_db_wf_a_task_中文.tmp", result.getOutputObject().getQualifiedName());
        assertEquals("  WF-`A` / !! ", result.getWorkflowId());
        assertEquals("Task.中文///", result.getTaskId());
    }

    @Test
    public void correlatedCommaWhereDoesNotCreateAnImplicitJoinCondition() {
        StatementLineageResult result = only("select * from ods.a x join ods.b y on x.id=y.id where exists("
                        + "select 1 from ods.c c,ods.d d where c.id=x.id and c.id=d.id)",
                options(false, LineageParseMode.TABLE_ONLY, true), provider());
        assertEquals(2, result.getJoinConditions().size());
        assertEquals("x.id=y.id", result.getJoinConditions().get(0).getJoinExpression());
        assertEquals("c.id=d.id", result.getJoinConditions().get(1).getJoinExpression());
        assertEquals("root.where.exists[1]", result.getJoinConditions().get(1).getQueryBlockPath());
        assertEquals(Arrays.asList("ods.c.id", "ods.d.id"), names(result.getJoinConditions().get(1).getJoinColumns()));
    }

    @Test
    public void specVariablePolicyFulfillsBuiltinUnknownAndEnvironmentRulesTogether() {
        HiveMetadataProvider provider = provider();
        ScriptAnalysisRequest base = new DefaultRequestAdapter().adapt(LineageAnalyzeRequest.builder()
                .workflowId("wf").taskId("task").taskVersion("business-v1").defaultDatabase("default")
                .metadataProvider(provider).runtimeVariables(Collections.singletonMap("env:env_name", "actual_supplied_env"))
                .rawScript("insert into dwd.t(x,y,z) select '${bizdate}','${unknown_var}','@{env_name}' from ods.a")
                .options(options(false, LineageParseMode.TABLE_AND_COLUMN, false)).build());
        AnalysisContract old = base.getContract();
        AnalysisContract contract = new AnalysisContract(old.getCapabilities(), VariablePolicy.SPEC_VARIABLES, old.getDefinitionPolicy(),
                old.getStarMappingPolicy(), old.getPublicationPolicy(), old.isIncludeTemporaryLineage(), old.isIncludeIgnoredStatements(),
                old.isExpandViewLineage(), old.getMaxViewExpandDepth(), old.isFailFast(), old.getFallbackVariableValue());
        ScriptAnalysisRequest request = new ScriptAnalysisRequest(base.getTask(), base.getSource(), base.getDialect(), base.getInitialSession(),
                base.getMetadataContext(), contract, base.getResourceLimits());
        WorkflowContext workflow = new WorkflowContext("wf", base.getMetadataContext(),
                new DefaultV1MetadataAdapterFactory().adapt(new LegacyMetadataBinding(Optional.of(provider), Optional.empty(), base.getMetadataContext(), false)),
                new DefaultMetadataCache());
        ScriptAnalysisResult result = new DefaultScriptLineageAnalyzer().analyzeScript(request, workflow);
        assertEquals(0, result.getCounts().getFailed());
        String normalized = result.getStatements().get(0).getNormalizedSql().getText();
        assertTrue(normalized, normalized.contains("'fallback_value','${unknown_var}','fallback_value'"));
        assertFalse(normalized.contains("actual_supplied_env"));
        assertEquals(1, result.getDiagnostics().getDiagnostics().stream().filter(diagnostic -> "UNRESOLVED_VARIABLE".equals(diagnostic.getCode())).count());
    }

    @Test
    public void standaloneJoinRecordsActuallyCarryReadableDirectAndPhysicalPresentation() {
        ScriptAnalysisResult result = new HiveScriptLineageAdapter().analyzeNative(request(
                "select * from ods.a a join ods.b b on a.id=b.id", options(false, LineageParseMode.TABLE_ONLY, true), provider()));
        JoinConditionRecord record = new DefaultJoinConditionRecordExporter().export(result.getStatements().get(0)).get(0);
        assertTrue(record.getPresentation().isPresent());
        assertEquals(1, record.getPresentation().get().getTypeCode());
        assertEquals(Arrays.asList("a.id", "b.id"), addresses(record.getPresentation().get().getDirectColumns()));
        assertEquals(Arrays.asList("ods.a.id", "ods.b.id"), addresses(record.getPresentation().get().getPhysicalColumns()));
        assertEquals("a.id=b.id", record.getPresentation().get().getExpression().getText().get());
    }

    @Test
    public void reservedAntiUnknownAndCrossKindsRetainTheirNumericExportContract() {
        ScriptAnalysisResult original = new HiveScriptLineageAdapter().analyzeNative(request(
                "select * from ods.a a join ods.b b on a.id=b.id", options(false, LineageParseMode.TABLE_ONLY, true), provider()));
        StatementAnalysisResult statement = original.getStatements().get(0);
        JoinFact existing = statement.getJoins().get().getJoins().get(0);
        JoinKind[] kinds = {JoinKind.UNKNOWN, JoinKind.LEFT_ANTI, JoinKind.CROSS, JoinKind.IMPLICIT};
        int[] codes = {0, 6, 7, 7};
        for (int index = 0; index < kinds.length; index++) {
            JoinFact typed = new JoinFact(existing.getJoinIndex(), existing.getQueryBlockId(), existing.getQueryBlockPath(), kinds[index],
                    existing.getPredicateId(), existing.getDirectReferences(), existing.getPhysicalReferences(), existing.getLexicalReferences(),
                    existing.getDiagnostics(), existing.getConditionSource());
            StatementAnalysisResult adapted = new StatementAnalysisResult(statement.getIdentity(), statement.getStatementType(),
                    statement.getRawSql(), statement.getNormalizedSql(), statement.getStatus(), statement.isFormalLineageProduced(),
                    statement.getLineage(), Optional.of(new JoinFacts(Collections.singletonList(typed), statement.getJoins().get().getDiagnostics())),
                    statement.getBoundUnit(), statement.getSkipReason(), statement.getFailureCode(), statement.getFailureMessage(),
                    statement.getDiagnostics(), statement.getFrontendEvidence());
            assertEquals(codes[index], new DefaultJoinConditionRecordExporter().export(adapted).get(0).getPresentation().get().getTypeCode());
        }
    }

    @Test
    public void unavailableExpressionTextDoesNotChangeProvenDependenciesOrClaimExtraction() {
        ScriptAnalysisResult original = new HiveScriptLineageAdapter().analyzeNative(request(
                "insert into dwd.t(x) select complex_udf(named_struct('k',id),amount) from ods.a",
                options(false, LineageParseMode.TABLE_AND_COLUMN, false), provider()));
        ScriptAnalysisResult withoutText = new ScriptAnalysisResult(original.getTask(), original.getEngine(), original.getStatements(),
                original.getCounts(), original.getDiagnostics(), original.getBasis());
        StatementLineageResult projected = new DefaultLegacyResultProjector().project(withoutText).getResult().getStatements().get(0);
        ColumnLineage output = column(projected, "x");
        assertSources(output, "ods.a.id", "ods.a.amount");
        assertEquals(LineageType.DERIVED, output.getLineageType());
        assertFalse(output.isExpressionExtracted());
    }

    @Test
    public void metadataAndDefinitionSyntaxAreReusedAcrossTasksAndVersions() {
        AtomicInteger definitionParses = new AtomicInteger();
        AtomicInteger metadataReads = new AtomicInteger();
        SyntaxFrontend nativeFrontend = new DialectSyntaxFrontend<HiveParseArtifact>(new HiveDialectFactory());
        SyntaxFrontend counted = (unit, dialect) -> {
            if (unit.getExpandedText().equals("select id,name from ods.a")) {
                definitionParses.incrementAndGet();
            }
            return nativeFrontend.parse(unit, dialect);
        };
        HiveMetadataProvider metadata = table -> {
            if (table.getQualifiedName().equals("dim.v")) {
                metadataReads.incrementAndGet();
                return HiveTableMetadata.view(table, Arrays.asList("id", "name"), "select id,name from ods.a");
            }
            return provider().getTableMetadata(table);
        };
        HiveScriptLineageAdapter analyzer = new HiveScriptLineageAdapter(new DefaultScriptLineageAnalyzer(counted,
                new DefaultSemanticBinder(), new DefaultMetadataAccessFactory()), new DefaultRequestAdapter(), new DefaultLegacyResultProjector());
        LineageAnalyzeOptions options = LineageAnalyzeOptions.builder().expandViewLineage(true).build();
        LineageAnalyzeRequest first = request("insert into dwd.t(id,name) select id,name from dim.v; insert into dwd.t(id,name) select id,name from dim.v", options, metadata);
        assertEquals(0, analyzer.analyzeNative(first).getCounts().getFailed());
        LineageAnalyzeRequest second = LineageAnalyzeRequest.builder().workflowId("wf").taskId("another-task").taskVersion("business-v2")
                .defaultDatabase("default").rawScript(first.getRawScript()).options(options).metadataProvider(metadata)
                .metadataCache(first.getMetadataCache()).build();
        assertEquals(0, analyzer.analyzeNative(second).getCounts().getFailed());
        assertEquals(1, definitionParses.get());
        assertEquals(1, metadataReads.get());
    }

    @Test
    public void customPreprocessorIsInvokedForScriptAndExternalDefinition() {
        List<String> inputScripts = new ArrayList<>();
        ScriptPreprocessor preprocessor = (sourceId, script, options) -> {
            inputScripts.add(script);
            return new DefaultScriptPreprocessor().preprocess(sourceId, script, options);
        };
        HiveMetadataProvider metadata = table -> table.getQualifiedName().equals("dim.v")
                ? HiveTableMetadata.view(table, Collections.singletonList("id"), "select id from ods.a") : provider().getTableMetadata(table);
        HiveScriptLineageAdapter analyzer = new HiveScriptLineageAdapter(new DefaultScriptLineageAnalyzer(
                new DialectSyntaxFrontend<HiveParseArtifact>(new HiveDialectFactory()), new DefaultSemanticBinder(),
                new DefaultMetadataAccessFactory(), preprocessor), new DefaultRequestAdapter(), new DefaultLegacyResultProjector());
        String script = "insert into dwd.t(id) select id from dim.v";
        assertEquals(0, analyzer.analyzeNative(request(script, LineageAnalyzeOptions.builder().expandViewLineage(true).build(), metadata)).getCounts().getFailed());
        assertTrue(inputScripts.contains(script));
        assertTrue(inputScripts.contains("select id from ods.a"));
    }

    @Test
    public void specWildcardMappingIsNamedAndNeverSilentlyAppliedToPositionalRequests() {
        HiveMetadataProvider metadata = table -> table.getQualifiedName().equals("ods.user")
                ? HiveTableMetadata.table(table, Arrays.asList("user_id", "user_name", "status", "dt", "source_only"))
                : HiveTableMetadata.table(table, Arrays.asList("user_id", "user_name", "status", "dt"));
        LineageAnalyzeRequest request = request("insert overwrite table dwd.user_snapshot select * from ods.`user`",
                options(false, LineageParseMode.TABLE_AND_COLUMN, false), metadata);
        HiveScriptLineageAdapter spec = new HiveScriptLineageAdapter(new DefaultScriptLineageAnalyzer(),
                new DefaultRequestAdapter(StarMappingPolicy.SPEC_TARGET_SCHEMA_MAPPING), new DefaultLegacyResultProjector());
        StatementLineageResult mapped = spec.analyzeScript(request).getStatements().get(0);
        assertEquals(4, mapped.getColumnLineages().size());
        assertSources(column(mapped, "status"), "ods.user.status");
        assertTrue(mapped.getColumnLineages().stream().noneMatch(value -> value.getTargetColumn().getQualifiedName().endsWith(".source_only")));
        ScriptAnalysisResult nativeResult = spec.analyzeNative(request);
        assertTrue(nativeResult.getDiagnostics().getDiagnostics().stream().anyMatch(diagnostic -> "SPEC_TARGET_SCHEMA_MAPPING".equals(diagnostic.getCode())));
        assertEquals(1, new HiveScriptLineageAdapter().analyzeNative(request).getCounts().getFailed());
    }

    @Test
    public void explicitLegacyWildcardMappingUsesNamesAndDoesNotTruncateOrdinaryProjection() {
        StatementLineageResult result = only("insert into dwd.t(amount) select * from ods.a",
                options(false, LineageParseMode.TABLE_AND_COLUMN, false), provider());
        assertSources(column(result, "amount"), "ods.a.amount");
        ScriptAnalysisResult exact = new HiveScriptLineageAdapter().analyzeNative(request("insert into dwd.t(amount) select id,amount from ods.a",
                options(false, LineageParseMode.TABLE_AND_COLUMN, false), provider()));
        assertEquals(1, exact.getCounts().getFailed());
        assertTrue(exact.getDiagnostics().getDiagnostics().stream().anyMatch(diagnostic -> "COLUMN_COUNT_MISMATCH".equals(diagnostic.getCode())));
    }

    @Test
    public void viewsKeepBoundaryEdgesAndExpandUnderlyingSourcesWhenEnabled() {
        HiveMetadataProvider metadata = table -> table.getQualifiedName().equals("dim.v_user")
                ? HiveTableMetadata.view(table, Arrays.asList("id", "name"), "select id,name from ods.a") : provider().getTableMetadata(table);
        LineageAnalyzeResult result = analyze("insert into dwd.t(id,name) select id,name from dim.v_user",
                LineageAnalyzeOptions.builder().expandViewLineage(true).includeTemporaryLineage(true).build(), metadata);
        assertEquals(0, result.getFailedStatementCount());
        StatementLineageResult write = result.getStatements().get(0);
        assertTrue(write.getColumnLineages().stream().anyMatch(value -> value.getTargetColumn().getQualifiedName().equals("dim.v_user.id")
                && names(value.getSourceColumns()).contains("ods.a.id")));
        assertTrue(names(column(write, "id").getSourceColumns()).contains("dim.v_user.id")
                || write.getColumnLineages().stream().anyMatch(value -> value.getTargetColumn().getQualifiedName().equals("dwd.t.id")
                && names(value.getSourceColumns()).contains("dim.v_user.id")));
    }

    @Test
    public void missingViewDefinitionIsRecoverableWithSpecificWarning() {
        HiveMetadataProvider missing = table -> table.getQualifiedName().equals("dim.v")
                ? HiveTableMetadata.view(table, Collections.singletonList("id"), null) : provider().getTableMetadata(table);
        LineageAnalyzeResult noDefinition = analyze("insert into dwd.t(id) select id from dim.v; insert into dwd.t(id) select id from ods.a",
                LineageAnalyzeOptions.builder().expandViewLineage(true).build(), missing);
        assertEquals(0, noDefinition.getFailedStatementCount());
        assertEquals(2, noDefinition.getStatements().size());
        assertTrue(noDefinition.getWarnings().stream().anyMatch(warning -> "VIEW_METADATA_NOT_FOUND".equals(warning.getWarningCode().name())));
    }

    @Test
    public void viewCycleAndDepthAreRecoverableWithSpecificWarnings() {
        HiveMetadataProvider cycle = table -> table.getQualifiedName().equals("dim.v")
                ? HiveTableMetadata.view(table, Collections.singletonList("id"), "select id from dim.w")
                : table.getQualifiedName().equals("dim.w")
                  ? HiveTableMetadata.view(table, Collections.singletonList("id"), "select id from dim.v") : provider().getTableMetadata(table);
        LineageAnalyzeResult cyclic = analyze("insert into dwd.t(id) select id from dim.v",
                LineageAnalyzeOptions.builder().expandViewLineage(true).maxViewExpandDepth(5).build(), cycle);
        assertEquals(0, cyclic.getFailedStatementCount());
        assertTrue(cyclic.getWarnings().stream().anyMatch(warning -> "VIEW_EXPANSION_CYCLE".equals(warning.getWarningCode().name())));
        LineageAnalyzeResult depth = analyze("insert into dwd.t(id) select id from dim.v",
                LineageAnalyzeOptions.builder().expandViewLineage(true).maxViewExpandDepth(1).build(), cycle);
        assertEquals(0, depth.getFailedStatementCount());
        assertTrue(depth.getWarnings().stream().anyMatch(warning -> "VIEW_EXPANSION_DEPTH_EXCEEDED".equals(warning.getWarningCode().name())));
    }

    @Test
    public void statementPublicationCountsRecoveryAndRawIdentity() {
        String script = "set hivevar:VARI='xx'; select 1; insert into dwd.t(id) select '${hivevar:VARI}' from ods.a; "
                + "insert into dwd.t(id) select from; insert into dwd.t(id) select id from ods.a;";
        LineageAnalyzeResult result = analyze(script, options(false, LineageParseMode.TABLE_AND_COLUMN, false), provider());
        assertEquals(5, result.getTotalStatementCount());
        assertEquals(2, result.getIgnoredStatementCount());
        assertEquals(1, result.getFailedStatementCount());
        assertEquals(3, result.getStatements().size());
        StatementLineageResult successful = result.getStatements().get(0);
        assertEquals("wf", successful.getWorkflowId());
        assertEquals("task", successful.getTaskId());
        assertEquals("business-v1", successful.getTaskVersion());
        assertEquals(2, successful.getStatementIndex());
        assertTrue(successful.getRawSql().contains("${hivevar:VARI}"));
        assertTrue(successful.getNormalizedSql().contains("'xx'"));
        assertTrue(successful.isFormalLineageProduced());
        assertEquals(Integer.valueOf(2), result.getStatements().get(1).getParseStatusCode());
        assertEquals(4, result.getStatements().get(2).getStatementIndex());
    }

    @Test
    public void auditedIgnoredStatementsKeepNoWritesAndTaskVersionsStayDistinct() {
        String script = "use ods; select id from a; insert into dwd.t values(1)";
        LineageAnalyzeResult audited = analyze(script, options(true, LineageParseMode.TABLE_AND_COLUMN, false), provider());
        assertEquals(3, audited.getStatements().size());
        for (StatementLineageResult statement : audited.getStatements()) {
            assertFalse(statement.isFormalLineageProduced());
            assertTrue(statement.getTableLineages().isEmpty());
            assertTrue(statement.getColumnLineages().isEmpty());
        }
        HiveScriptLineageAdapter analyzer = new HiveScriptLineageAdapter();
        LineageAnalyzeRequest first = request("insert into dwd.t(id) select id from ods.a", options(false, LineageParseMode.TABLE_AND_COLUMN, false), provider());
        LineageAnalyzeRequest second = LineageAnalyzeRequest.builder().workflowId("wf").taskId("task").taskVersion("business-v2")
                .defaultDatabase("default").rawScript(first.getRawScript()).metadataProvider(first.getMetadataProvider()).build();
        assertNotEquals(analyzer.analyzeScript(first).getStatements().get(0).getStatementId(),
                analyzer.analyzeScript(second).getStatements().get(0).getStatementId());
    }

    @Test
    public void formalInsertAndModesKeepTableAndColumnCapabilitiesOrthogonal() {
        for (LineageParseMode mode : LineageParseMode.values()) {
            StatementLineageResult result = only("insert overwrite table dwd.t(id) select id from ods.a", options(false, mode, false), provider());
            assertEquals(Integer.valueOf(0), result.getParseStatusCode());
            assertEquals(mode != LineageParseMode.COLUMN_ONLY, !result.getTableLineages().isEmpty());
            assertEquals(mode != LineageParseMode.TABLE_ONLY, !result.getColumnLineages().isEmpty());
            if (mode != LineageParseMode.TABLE_ONLY) {
                assertSources(column(result, "id"), "ods.a.id");
            }
        }
    }

    @Test
    public void tableOnlyMissingMetadataNeverLoadsColumnsOrWarns() {
        AtomicInteger reads = new AtomicInteger();
        HiveMetadataProvider forbidden = table -> {
            reads.incrementAndGet();
            throw new AssertionError("TABLE_ONLY requested metadata");
        };
        StatementLineageResult result = only("insert into dwd.t select * from ods.a", options(false, LineageParseMode.TABLE_ONLY, false), forbidden);
        assertEquals(0, reads.get());
        assertEquals(1, result.getTableLineages().size());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    public void arithmeticFunctionConstantAndUnknownUdfKeepExactDependencies() {
        StatementLineageResult result = only("insert into dwd.t(x,y,z,w) select id+amount,nvl(name,'?'),'fixed',my_udf(id,amount) from ods.a",
                options(false, LineageParseMode.TABLE_AND_COLUMN, false), provider());
        assertSources(column(result, "x"), "ods.a.id", "ods.a.amount");
        assertEquals(LineageType.DERIVED, column(result, "x").getLineageType());
        assertEquals("id+amount", column(result, "x").getExpression());
        assertTrue(column(result, "x").isExpressionExtracted());
        assertSources(column(result, "y"), "ods.a.name");
        assertTrue(column(result, "y").getFunctionNames().contains("nvl"));
        assertSources(column(result, "z"));
        assertEquals("constant('fixed')", column(result, "z").getExpression());
        assertEquals(LineageType.CONSTANT, column(result, "z").getLineageType());
        assertSources(column(result, "w"), "ods.a.id", "ods.a.amount");
        assertEquals(Integer.valueOf(0), result.getParseStatusCode());
    }

    @Test
    public void partitionsKeepStaticAndNoFieldUdfOutputsFreeOfFakeSources() {
        StatementLineageResult result = only("insert overwrite table dwd.partition_target partition(source_system='profile',dt) "
                        + "select id,name,ETL_HQT.bdpdatefunc('20261012',1,'yyyyMMdd',-1) from ods.a where dt='20261011'",
                options(false, LineageParseMode.TABLE_AND_COLUMN, false), provider());
        assertSources(column(result, "id"), "ods.a.id");
        assertSources(column(result, "name"), "ods.a.name");
        assertSources(column(result, "dt"));
        assertSources(column(result, "source_system"));
        assertEquals("constant('profile')", column(result, "source_system").getExpression());
        assertEquals(LineageType.CONSTANT, column(result, "source_system").getLineageType());
    }

    @Test
    public void unionAllDistinctAndConstantsHaveBranchExpressions() {
        StatementLineageResult all = only("insert into dwd.t(x) select a.id from ods.a a union all select b.id from ods.b b",
                options(false, LineageParseMode.TABLE_AND_COLUMN, false), provider());
        assertSources(column(all, "x"), "ods.a.id", "ods.b.id");
        assertEquals("union_all(ods.a.id, ods.b.id)", column(all, "x").getExpression());
        StatementLineageResult distinct = only("insert into dwd.t(x) select a.id from ods.a a union select b.id from ods.b b",
                options(false, LineageParseMode.TABLE_AND_COLUMN, false), provider());
        assertEquals("union_distinct(ods.a.id, ods.b.id)", column(distinct, "x").getExpression());
        StatementLineageResult constants = only("insert into dwd.t(x) select 'A' from ods.a union all select 'B' from ods.b",
                options(false, LineageParseMode.TABLE_AND_COLUMN, false), provider());
        assertSources(column(constants, "x"));
        assertEquals("union_all(constant('A'), constant('B'))", column(constants, "x").getExpression());
        assertEquals(LineageType.CONSTANT, column(constants, "x").getLineageType());
    }

    @Test
    public void aggregatesAndWindowsExposeOnlyRealFunctions() {
        StatementLineageResult aggregate = only("insert into dwd.t(id,amount) select id,sum(amount) from ods.a group by id",
                options(false, LineageParseMode.TABLE_AND_COLUMN, false), provider());
        assertSources(column(aggregate, "amount"), "ods.a.amount");
        assertEquals(Collections.singletonList("sum"), column(aggregate, "amount").getFunctionNames());
        StatementLineageResult window = only("insert into dwd.t(id,rn) select id,row_number() over(partition by id order by dt desc) from ods.a",
                options(false, LineageParseMode.TABLE_AND_COLUMN, false), provider());
        assertSources(column(window, "rn"), "ods.a.id", "ods.a.dt");
        assertEquals(Collections.singletonList("row_number"), column(window, "rn").getFunctionNames());
        assertEquals(LineageType.DERIVED, column(window, "rn").getLineageType());
    }

    @Test
    public void variableNamespaceFallbackRawTextAndEnvironmentPlaceholder() {
        LineageAnalyzeResult result = analyze("set bizdate=20260629; set hivevar:VARI='xx'; insert into dwd.t(x,y,z,w,day) "
                        + "select '${hiveconf:bizdate}','${hivevar:VARI}','${unknown_var}','@{env_name}','${work_day}' from ods.a",
                options(false, LineageParseMode.TABLE_AND_COLUMN, false), provider());
        StatementLineageResult statement = result.getStatements().get(0);
        assertTrue(statement.getRawSql().contains("${hiveconf:bizdate}"));
        assertTrue(statement.getNormalizedSql().contains("'20260629'"));
        assertTrue(statement.getNormalizedSql().contains("'xx'"));
        assertTrue(statement.getNormalizedSql().contains("'fallback_value'"));
        assertFalse(statement.getNormalizedSql().contains("@{env_name}"));
        assertTrue(statement.getWarnings().stream().noneMatch(warning -> "UNRESOLVED_VARIABLE".equals(warning.getWarningCode().name())));
    }

    @Test
    public void temporaryObjectsNormalizeNamesAndTakePriorityOverMetadata() {
        HiveMetadataProvider collision = table -> table.getTableName().equals("tmp_user")
                ? HiveTableMetadata.view(table, Collections.singletonList("id"), "select id from ods.wrong_source") : provider().getTableMetadata(table);
        LineageAnalyzeResult result = analyze("create temporary table source_db.tmp_user as select id from ods.a; "
                        + "insert into dwd.t(id) select id from source_db.tmp_user; insert into dwd.t(id) select id from tmp_user",
                options(true, LineageParseMode.TABLE_AND_COLUMN, false), collision);
        assertEquals("seele_tmp_db_wf_task.tmp_user", result.getStatements().get(0).getOutputObject().getQualifiedName());
        assertSources(column(result.getStatements().get(1), "id"), "ods.a.id");
        assertSources(column(result.getStatements().get(2), "id"), "ods.a.id");
        assertTrue(result.getStatements().get(1).getInputObjects().stream().noneMatch(object -> object.getQualifiedName().contains("wrong_source")));
    }

    @Test
    public void pureQueryCteCanBeReusedWithoutCreatingPhysicalCteObjects() {
        LineageAnalyzeResult result = analyze("with x as(select id from ods.a) select * from x; insert into dwd.t(id) select id from x",
                options(false, LineageParseMode.TABLE_AND_COLUMN, false), provider());
        assertEquals(1, result.getStatements().size());
        StatementLineageResult write = result.getStatements().get(0);
        assertSources(column(write, "id"), "ods.a.id");
        assertTrue(write.getInputObjects().stream().noneMatch(object -> object.getQualifiedName().equals("default.x") || object.getQualifiedName().contains("seele_tmp_db")));
    }

    @Test
    public void ctasCteExpandsFieldsWithoutPhysicalOrTemporaryCteObject() {
        StatementLineageResult result = only("create table dwd.profile as with x as(select id,name from ods.a) select * from x",
                options(false, LineageParseMode.TABLE_AND_COLUMN, false), provider());
        assertSources(column(result, "id"), "ods.a.id");
        assertTrue(result.getInputObjects().stream().noneMatch(object -> object.getQualifiedName().equals("default.x") || object.getQualifiedName().contains("seele_tmp_db")));
    }

    @Test
    public void missingMetadataWarnsButKeepsKnownTableLineage() {
        StatementLineageResult result = only("insert into dwd.t select * from ods.no_metadata",
                options(false, LineageParseMode.TABLE_AND_COLUMN, false), provider());
        assertEquals(Integer.valueOf(0), result.getParseStatusCode());
        assertEquals(1, result.getTableLineages().size());
        assertTrue(result.getWarnings().stream().anyMatch(warning -> "METADATA_NOT_FOUND".equals(warning.getWarningCode().name())));
        assertTrue(result.getColumnLineages().stream().allMatch(column -> column.getSourceColumns().isEmpty()));
    }

    @Test
    public void joinDefaultsAndJoinOnlyPublicationAreIndependentOfFormalLineage() {
        String sql = "select a.* from ods.a a join ods.b b on a.id=b.id";
        assertTrue(analyze(sql, options(false, LineageParseMode.TABLE_ONLY, false), provider()).getStatements().isEmpty());
        ScriptAnalysisResult nativeResult = new HiveScriptLineageAdapter().analyzeNative(request(sql, options(false, LineageParseMode.TABLE_ONLY, true), provider()));
        assertFalse(nativeResult.getStatements().get(0).isFormalLineageProduced());
        List<JoinConditionRecord> records = new DefaultJoinConditionRecordExporter().export(nativeResult.getStatements().get(0));
        assertEquals(1, records.size());
        assertEquals(nativeResult.getStatements().get(0).getIdentity(), records.get(0).getStatement());
        StatementLineageResult legacy = only(sql, options(false, LineageParseMode.TABLE_ONLY, true), provider());
        assertTrue(legacy.getTableLineages().isEmpty());
        assertTrue(legacy.getColumnLineages().isEmpty());
    }

    @Test
    public void fullOnFunctionsSingleSidedAndRepeatedFieldsStayInOneRecord() {
        StatementLineageResult result = only("select * from ods.a a left join ods.b b on split(a.name,'_')[1]=cast(b.id as string) "
                + "and a.id is not null where a.dt='x'", options(false, LineageParseMode.TABLE_ONLY, true), provider());
        JoinCondition join = result.getJoinConditions().get(0);
        assertEquals("LEFT_JOIN", join.getJoinTypeName());
        assertEquals("split(a.name,'_')[1]=cast(b.id as string) and a.id is not null", join.getJoinExpression());
        assertEquals(Arrays.asList("ods.a.name", "ods.b.id", "ods.a.id"), names(join.getJoinColumns()));
        assertFalse(names(join.getJoinColumns()).contains("ods.a.dt"));
        JoinCondition single = only("select * from ods.a a join ods.b b on b.dt='x'",
                options(false, LineageParseMode.TABLE_ONLY, true), provider()).getJoinConditions().get(0);
        assertEquals(Collections.singletonList("ods.b.dt"), names(single.getJoinColumns()));
        JoinCondition repeated = only("select * from ods.a a join ods.b b on a.id=b.id and a.id is not null",
                options(false, LineageParseMode.TABLE_ONLY, true), provider()).getJoinConditions().get(0);
        assertEquals(Arrays.asList("ods.a.id", "ods.b.id"), names(repeated.getJoinColumns()));
    }

    @Test
    public void joinTypesNormalizeWithoutInventingCrossConditions() {
        String[] types = {"join", "inner join", "left join", "left outer join", "right join", "right outer join", "full join", "full outer join", "left semi join"};
        int[] codes = {1, 1, 2, 2, 3, 3, 4, 4, 5};
        for (int index = 0; index < types.length; index++) {
            JoinCondition condition = only("select * from ods.a a " + types[index] + " ods.b b on a.id=b.id",
                    options(false, LineageParseMode.TABLE_ONLY, true), provider()).getJoinConditions().get(0);
            assertEquals(types[index], codes[index], condition.getJoinType());
        }
        LineageAnalyzeResult cross = analyze("select * from ods.a a cross join ods.b b", options(true, LineageParseMode.TABLE_ONLY, true), provider());
        assertTrue(cross.getStatements().get(0).getJoinConditions().isEmpty());
    }

    @Test
    public void cteDerivedAndExistsJoinsHaveStableGlobalIndicesAndDeclaredPaths() {
        StatementLineageResult cte = only("with x as(select a.id from ods.a a join ods.b b on a.id=b.id), "
                        + "y as(select c.id from ods.c c join ods.d d on c.id=d.id) select * from x join y on x.id=y.id",
                options(false, LineageParseMode.TABLE_ONLY, true), provider());
        assertEquals(Arrays.asList("cte.x", "cte.y", "root"), paths(cte));
        for (int index = 0; index < cte.getJoinConditions().size(); index++) {
            assertEquals(index + 1, cte.getJoinConditions().get(index).getJoinIndex());
        }
        StatementLineageResult nested = only("select * from (select a.id from ods.a a join ods.b b on a.id=b.id) x "
                        + "join ods.c c on x.id=c.id where exists(select 1 from ods.d d join ods.e e on d.id=e.id)",
                options(false, LineageParseMode.TABLE_ONLY, true), provider());
        assertEquals(Arrays.asList("root", "root.subquery[1]", "root.where.exists[2]"), paths(nested));
    }

    @Test
    public void ambiguousCteRetainsDirectReferenceWithoutInventingPhysicalField() {
        StatementLineageResult result = only("with x as(select * from ods.a a join ods.b b on a.id=b.id), "
                        + "y as(select id from ods.c) select * from x join y on x.id=y.id",
                options(false, LineageParseMode.TABLE_ONLY, true), provider());
        JoinCondition main = result.getJoinConditions().get(result.getJoinConditions().size() - 1);
        assertEquals("x.id=y.id", main.getJoinExpression());
        assertTrue(names(main.getDirectJoinColumns()).contains("x.id"));
        assertEquals(Collections.singletonList("ods.c.id"), names(main.getJoinColumns()));
        assertTrue(result.getWarnings().stream().anyMatch(warning -> "UNKNOWN_COLUMN_LINEAGE".equals(warning.getWarningCode().name())));
    }

    @Test
    public void temporaryJoinRetainsDirectBoundaryAndDoesNotReplayHistoricalJoins() {
        LineageAnalyzeResult result = analyze("create temporary table tmp as select a.id from ods.a a join ods.b b on a.id=b.id; "
                + "select * from tmp x join ods.c c on x.id=c.id", options(true, LineageParseMode.TABLE_ONLY, true), provider());
        assertEquals(0, result.getFailedStatementCount());
        assertEquals(2, result.getStatements().size());
        StatementLineageResult query = result.getStatements().get(1);
        assertEquals(1, query.getJoinConditions().size());
        JoinCondition condition = query.getJoinConditions().get(0);
        assertEquals("root", condition.getQueryBlockPath());
        assertTrue(condition.getDirectJoinObjects().stream().anyMatch(object -> object.getQualifiedName().equals("seele_tmp_db_wf_task.tmp")));
        assertEquals(Arrays.asList("ods.a.id", "ods.c.id"), names(condition.getJoinColumns()));
    }

    @Test
    public void unknownTemporaryFieldRetainsOnlyProvablePhysicalJoinSource() {
        LineageAnalyzeResult result = analyze("create temporary table tmp as select * from ods.no_metadata; "
                + "select * from tmp x join ods.c c on x.id=c.id", options(true, LineageParseMode.TABLE_ONLY, true), provider());
        assertEquals(0, result.getFailedStatementCount());
        JoinCondition condition = result.getStatements().get(1).getJoinConditions().get(0);
        assertTrue(names(condition.getDirectJoinColumns()).contains("x.id"));
        assertEquals(Collections.singletonList("ods.c.id"), names(condition.getJoinColumns()));
        assertTrue(result.getWarnings().stream().anyMatch(warning -> "UNKNOWN_COLUMN_LINEAGE".equals(warning.getWarningCode().name())));
    }

    @Test
    public void unqualifiedAmbiguousJoinUsesMetadataAndKeepsTheConditionText() {
        AtomicInteger reads = new AtomicInteger();
        HiveMetadataProvider metadata = table -> {
            reads.incrementAndGet();
            return provider().getTableMetadata(table);
        };
        JoinCondition condition = only("select * from ods.a a join ods.b b on id=b.id",
                options(false, LineageParseMode.TABLE_ONLY, true), metadata).getJoinConditions().get(0);
        assertEquals("id=b.id", condition.getJoinExpression());
        assertTrue(reads.get() >= 2);
        assertEquals(Collections.singletonList("ods.b.id"), names(condition.getJoinColumns()));
    }

    @Test
    public void sequentialJoinsKeepInnerToOuterOrderAndOneBasedIndices() {
        StatementLineageResult result = only("select * from ods.a a join ods.b b on a.id=b.id left join ods.c c on b.id=c.id",
                options(false, LineageParseMode.TABLE_ONLY, true), provider());
        assertEquals(2, result.getJoinConditions().size());
        assertEquals("a.id=b.id", result.getJoinConditions().get(0).getJoinExpression());
        assertEquals("b.id=c.id", result.getJoinConditions().get(1).getJoinExpression());
        assertEquals(1, result.getJoinConditions().get(0).getJoinIndex());
        assertEquals(2, result.getJoinConditions().get(1).getJoinIndex());
    }

    @Test
    public void commaWhereEmitsOnlyCrossSourceConjunctsAndPreservesFullOn() {
        JoinCondition comma = only("select * from ods.a a, ods.b b where a.id=b.id and a.dt='x'",
                options(false, LineageParseMode.TABLE_ONLY, true), provider()).getJoinConditions().get(0);
        assertEquals(7, comma.getJoinType());
        assertEquals("IMPLICIT_JOIN", comma.getJoinTypeName());
        assertEquals("root", comma.getQueryBlockPath());
        assertEquals("a.id=b.id", comma.getJoinExpression());
        JoinCondition explicit = only("select * from ods.a a join ods.b b on a.id=b.id and a.dt=b.dt",
                options(false, LineageParseMode.TABLE_ONLY, true), provider()).getJoinConditions().get(0);
        assertEquals("a.id=b.id and a.dt=b.dt", explicit.getJoinExpression());
    }

    private LineageAnalyzeOptions options(boolean audit, LineageParseMode mode, boolean joins) {
        return LineageAnalyzeOptions.builder().parseMode(mode).parseJoinConditions(joins).includeIgnoredStatements(audit)
                .includeTemporaryLineage(false).expandViewLineage(false).fallbackVariableValue("fallback_value").build();
    }

    private LineageAnalyzeRequest request(String sql, LineageAnalyzeOptions options, HiveMetadataProvider metadata) {
        return request(sql, options, metadata, "default");
    }

    private LineageAnalyzeRequest request(String sql, LineageAnalyzeOptions options, HiveMetadataProvider metadata,
                                          String database) {
        return LineageAnalyzeRequest.builder().workflowId("wf").taskId("task").taskVersion("business-v1")
                .defaultDatabase(database).rawScript(sql).options(options).metadataProvider(metadata).build();
    }

    private LineageAnalyzeResult analyze(String sql, LineageAnalyzeOptions options, HiveMetadataProvider metadata) {
        return new HiveScriptLineageAdapter().analyzeScript(request(sql, options, metadata));
    }

    private StatementLineageResult only(String sql, LineageAnalyzeOptions options, HiveMetadataProvider metadata) {
        LineageAnalyzeResult result = analyze(sql, options, metadata);
        assertEquals(sql + " " + result.getStatements().stream().map(StatementLineageResult::getFailureMessage).collect(java.util.stream.Collectors.joining(";")), 0, result.getFailedStatementCount());
        assertEquals(sql, 1, result.getStatements().size());
        return result.getStatements().get(0);
    }

    private ColumnLineage column(StatementLineageResult statement, String name) {
        for (ColumnLineage column : statement.getColumnLineages()) {
            if (column.getTargetColumn().getQualifiedName().endsWith("." + name)) {
                return column;
            }
        }
        throw new AssertionError("Missing target field " + name + " failure=" + statement.getFailureMessage());
    }

    private void assertSources(ColumnLineage column, String... names) {
        assertEquals(Arrays.asList(names), names(column.getSourceColumns()));
    }

    private List<String> names(List<ColumnRef> references) {
        List<String> names = new ArrayList<>();
        for (ColumnRef reference : references) {
            names.add(reference.getQualifiedName());
        }
        return names;
    }

    private List<String> addresses(List<ColumnAddress> references) {
        List<String> names = new ArrayList<>();
        for (ColumnAddress reference : references) {
            names.add(reference.getObject().getDisplayName() + "." + String.join(".", reference.getFieldPath()));
        }
        return names;
    }

    private List<String> paths(StatementLineageResult statement) {
        List<String> paths = new ArrayList<>();
        for (JoinCondition condition : statement.getJoinConditions()) {
            paths.add(condition.getQueryBlockPath());
        }
        return paths;
    }

    private HiveMetadataProvider provider() {
        Map<String, List<String>> tables = new LinkedHashMap<>();
        for (String name : Arrays.asList("ods.a", "ods.b", "ods.c", "ods.d", "ods.e")) {
            tables.put(name, Arrays.asList("id", "name", "amount", "dt", "tags"));
        }
        tables.put("dwd.t", Arrays.asList("id", "name", "amount", "dt"));
        tables.put("dwd.partition_target", Arrays.asList("id", "name", "source_system", "dt"));
        return table -> tables.containsKey(table.getQualifiedName()) ? HiveTableMetadata.table(table, tables.get(table.getQualifiedName())) : null;
    }
}
