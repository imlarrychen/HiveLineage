package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.StatementLineageResult;
import org.junit.Test;

public class HivePositionalStarScopeTest {
    @Test
    public void derivedTableStarKeepsExistingProjectionBehavior() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("db.target", "b", "a")
            .addTable("db.source", "a", "b", "c");

        StatementLineageResult statement = analyze(
            metadata,
            "insert overwrite table db.target "
                + "select * from (select b, a from db.source) q"
        );

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "derived star parse status");
        TestSupport.assertEquals(2, statement.getColumnLineages().size(), "derived star lineage count");
    }

    @Test
    public void multiSourceBareStarKeepsExistingUnresolvedBehavior() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("db.target", "left_id", "right_id")
            .addTable("db.left_source", "left_id")
            .addTable("db.right_source", "right_id");

        StatementLineageResult statement = analyze(
            metadata,
            "insert overwrite table db.target select * from db.left_source l "
                + "join db.right_source r on l.left_id = r.right_id"
        );

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "multi-source bare star parse status");
        TestSupport.assertEquals(null, statement.getFailureCode(), "multi-source bare star failure code");
    }

    @Test
    public void missingSourceMetadataDoesNotBecomeColumnCountMismatch() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("db.target", "id", "name");

        StatementLineageResult statement = analyze(
            metadata,
            "insert overwrite table db.target select * from db.missing_source"
        );

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "missing source star parse status");
        TestSupport.assertEquals(null, statement.getFailureCode(), "missing source star failure code");
    }

    @Test
    public void explicitTargetColumnsKeepExistingStarBehavior() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("db.target", "age")
            .addTable("db.source", "id", "age");

        StatementLineageResult statement = analyze(
            metadata,
            "insert overwrite table db.target(age) select * from db.source"
        );

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "explicit target star parse status");
        TestSupport.assertEquals(1, statement.getColumnLineages().size(), "explicit target star lineage count");
        TestSupport.assertEquals(
            "db.source.age",
            statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName(),
            "explicit target star source"
        );
    }

    private StatementLineageResult analyze(TestMetadataProvider metadata, String sql) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_positional_star_scope")
            .taskId("task_positional_star_scope")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .rawScript(sql)
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();
        return new HiveScriptLineageAnalyzer().analyzeScript(request).getStatements().get(0);
    }
}
