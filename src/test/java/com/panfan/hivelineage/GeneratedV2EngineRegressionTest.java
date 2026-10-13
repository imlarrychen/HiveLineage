package com.panfan.hivelineage;

import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.v2.api.StatementStatus;
import com.panfan.hivelineage.v2.api.V2AnalyzeResult;
import com.panfan.hivelineage.v2.compare.CorpusCase;
import com.panfan.hivelineage.v2.compare.DefaultGeneratedSqlCorpusLoader;
import com.panfan.hivelineage.v2.compat.DefaultLegacyResultProjector;
import com.panfan.hivelineage.v2.compat.HiveV2LineageAnalyzer;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Runs the actual existing SQL and metadata fixtures through all concrete V2 stages. */
@RunWith(Parameterized.class)
public class GeneratedV2EngineRegressionTest {
    private final CorpusCase item;
    private final LineageParseMode mode;

    public GeneratedV2EngineRegressionTest(CorpusCase item, LineageParseMode mode) {
        this.item = item;
        this.mode = mode;
    }

    @Parameterized.Parameters(name = "{0}: {1}")
    public static Collection<Object[]> cases() throws Exception {
        List<Object[]> cases = new ArrayList<>();
        for (CorpusCase item : new DefaultGeneratedSqlCorpusLoader().load(Paths.get("src/test/resources/hql/generated"))) {
            for (LineageParseMode mode : LineageParseMode.values()) {
                cases.add(new Object[] { item, mode });
            }
        }
        return cases;
    }

    @Test
    public void entireCorpusHasOnlyItsDeclaredFailureAndModeSpecificOutput() {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder().workflowId(item.getTask().getWorkflowId()).taskId(item.getTask().getTaskId())
            .taskVersion(item.getTask().getTaskVersion()).defaultDatabase("default").rawScript(item.getSource().getText())
            .metadataProvider(GeneratedV2MetadataFixture.provider()).options(LineageAnalyzeOptions.builder().parseMode(mode)
                .includeIgnoredStatements(true).includeTemporaryLineage(true).expandViewLineage(true).parseJoinConditions(true).fallbackVariableValue("fallback_value").build()).build();
        V2AnalyzeResult nativeResult = new HiveV2LineageAnalyzer().analyzeNative(request);
        assertEquals(item.getRelativePath() + " failures: " + nativeResult.getDiagnostics().getDiagnostics().stream()
            .map(diagnostic -> diagnostic.getCode() + ":" + diagnostic.getMessage()).collect(java.util.stream.Collectors.joining(";")),
            item.getExpectedFailureCount(), nativeResult.getCounts().getFailed());
        LineageAnalyzeResult projected = new DefaultLegacyResultProjector().project(nativeResult).getResult();
        assertEquals(nativeResult.getCounts().getTotal(), projected.getTotalStatementCount());
        assertEquals(nativeResult.getCounts().getTotal(), nativeResult.getStatements().size());
        assertEquals(projected.getTotalStatementCount(), projected.getStatements().size());
        for (StatementLineageResult statement : projected.getStatements()) {
            assertEquals(item.getTask().getWorkflowId(), statement.getWorkflowId());
            assertEquals(item.getTask().getTaskVersion(), statement.getTaskVersion());
            assertEquals(mode, statement.getParseMode());
            if (mode == LineageParseMode.TABLE_ONLY) {
                assertTrue(statement.getColumnLineages().isEmpty());
            }
            if (mode == LineageParseMode.COLUMN_ONLY) {
                assertTrue(statement.getTableLineages().isEmpty());
            }
            if (statement.isFormalLineageProduced()) {
                assertFalse(statement.getInputObjects().isEmpty());
                assertTrue(statement.getOutputObject() != null);
            }
            statement.getColumnLineages().forEach(column -> {
                String target = column.getTargetColumn().getQualifiedName();
                boolean primaryTarget = target.startsWith(statement.getOutputObject().getQualifiedName() + ".");
                boolean definitionTarget = nativeResult.getStatements().get(statement.getStatementIndex()).getBoundUnit().get().getObjects().values().stream()
                    .anyMatch(object -> (object.getKind() == com.panfan.hivelineage.v2.ir.statement.BoundObjectReference.ObjectKind.VIEW
                        || object.getKind() == com.panfan.hivelineage.v2.ir.statement.BoundObjectReference.ObjectKind.TEMPORARY)
                        && target.startsWith(object.getName().getDisplayName() + "."));
                assertTrue(target + " must address the write or an expanded definition", primaryTarget || definitionTarget);
                assertFalse(column.getExpression() == null || column.getExpression().isEmpty());
            });
        }
        if (item.getRelativePath().equals("lineage_case_11.sql")) {
            assertEquals(StatementStatus.FAILED, nativeResult.getStatements().get(0).getStatus());
            assertTrue(nativeResult.getStatements().get(1).isFormalLineageProduced());
            assertEquals("dwd.parse_recovery_target", projected.getStatements().get(1).getOutputObject().getQualifiedName());
        }
    }
}
