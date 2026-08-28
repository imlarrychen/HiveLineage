package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.WarningCode;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsColumn;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsSource;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HiveSessionDatabaseResolutionTest {
    @Test
    public void defaultDatabaseQualifiesBareTablesAndColumns() {
        StatementLineageResult statement = analyze(
            "whc",
            "insert into target_t(id) select s.id from source_t s;",
            LineageAnalyzeOptions.builder().parseMode(LineageParseMode.TABLE_AND_COLUMN).build(),
            null
        ).getStatements().get(0);

        assertEquals("whc.target_t", statement.getOutputObject().getQualifiedName());
        assertContainsSource(statement, "whc.source_t");
        assertEquals("whc.target_t.id", statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName());
        assertContainsColumn(statement.getColumnLineages().get(0).getSourceColumns(), "whc.source_t.id");
    }

    @Test
    public void explicitDatabaseWinsOverSessionDatabase() {
        StatementLineageResult statement = analyze(
            "db1",
            "insert into db2.target_t(id) select id from db3.source_t;",
            LineageAnalyzeOptions.builder().build(),
            null
        ).getStatements().get(0);

        assertEquals("db2.target_t", statement.getOutputObject().getQualifiedName());
        assertContainsSource(statement, "db3.source_t");
    }

    @Test
    public void fullyQualifiedTablesDoNotRequireDefaultDatabase() {
        StatementLineageResult statement = analyze(
            null,
            "insert into db2.target_t(id) select id from db3.source_t;",
            LineageAnalyzeOptions.builder().build(),
            null
        ).getStatements().get(0);

        assertEquals(Integer.valueOf(0), statement.getParseStatusCode());
        assertEquals("db2.target_t", statement.getOutputObject().getQualifiedName());
        assertContainsSource(statement, "db3.source_t");
    }

    @Test
    public void backtickAliasQualifiesSourceColumn() {
        StatementLineageResult statement = analyze(
            "d",
            "insert into out_t(id) select `S`.`ID` from source_t `S`;",
            LineageAnalyzeOptions.builder().parseMode(LineageParseMode.TABLE_AND_COLUMN).build(),
            null
        ).getStatements().get(0);

        assertEquals(Integer.valueOf(0), statement.getParseStatusCode());
        assertContainsColumn(statement.getColumnLineages().get(0).getSourceColumns(), "d.source_t.id");
    }

    @Test
    public void quotedKeywordAliasQualifiesSourceColumn() {
        StatementLineageResult statement = analyze(
            "d",
            "insert into out_t(id) select `where`.`ID` from source_t as `where`;",
            LineageAnalyzeOptions.builder().parseMode(LineageParseMode.TABLE_AND_COLUMN).build(),
            null
        ).getStatements().get(0);

        assertEquals(Integer.valueOf(0), statement.getParseStatusCode());
        assertContainsColumn(statement.getColumnLineages().get(0).getSourceColumns(), "d.source_t.id");
    }

    @Test
    public void multipleUseStatementsAffectOnlyFollowingStatements() {
        LineageAnalyzeResult result = analyze(
            "db1",
            "insert into out_1(id) select id from in_1;"
                + "use db2;"
                + "insert into out_2(id) select id from in_2;"
                + "use db3;"
                + "insert into out_3(id) select id from in_3;",
            LineageAnalyzeOptions.builder().build(),
            null
        );

        assertEquals(5, result.getTotalStatementCount());
        assertEquals(2, result.getIgnoredStatementCount());
        assertEquals(0, result.getFailedStatementCount());
        assertEquals(3, result.getStatements().size());
        assertEquals("db1.out_1", result.getStatements().get(0).getOutputObject().getQualifiedName());
        assertContainsSource(result.getStatements().get(0), "db1.in_1");
        assertEquals("db2.out_2", result.getStatements().get(1).getOutputObject().getQualifiedName());
        assertContainsSource(result.getStatements().get(1), "db2.in_2");
        assertEquals("db3.out_3", result.getStatements().get(2).getOutputObject().getQualifiedName());
        assertContainsSource(result.getStatements().get(2), "db3.in_3");
    }

    @Test
    public void useSupportsVariableBackticksAndIgnoredRecord() {
        LineageAnalyzeResult result = analyze(
            "db1",
            "set target_db=whc; use `${target_db}`; insert into out_t(id) select id from in_t;",
            LineageAnalyzeOptions.builder().includeIgnoredStatements(true).build(),
            null
        );

        assertEquals(3, result.getTotalStatementCount());
        assertEquals(2, result.getIgnoredStatementCount());
        assertEquals(0, result.getFailedStatementCount());
        assertEquals(3, result.getStatements().size());
        assertEquals("SET", result.getStatements().get(0).getStatementType());
        StatementLineageResult useStatement = result.getStatements().get(1);
        assertEquals("USE", useStatement.getStatementType());
        assertEquals(Integer.valueOf(3), useStatement.getParseStatusCode());
        assertEquals("USE_DATABASE", useStatement.getSkipReason());
        assertEquals("whc.out_t", result.getStatements().get(2).getOutputObject().getQualifiedName());
        assertContainsSource(result.getStatements().get(2), "whc.in_t");
    }

    @Test
    public void statementVariableCanResolveToUseDatabase() {
        LineageAnalyzeResult result = analyze(
            "db1",
            "set command=use db2; ${command}; insert into out_t(id) select id from in_t;",
            LineageAnalyzeOptions.builder().includeIgnoredStatements(true).build(),
            null
        );

        assertEquals(3, result.getTotalStatementCount());
        assertEquals(2, result.getIgnoredStatementCount());
        assertEquals(0, result.getFailedStatementCount());
        assertEquals(3, result.getStatements().size());
        StatementLineageResult useStatement = result.getStatements().get(1);
        assertEquals("USE", useStatement.getStatementType());
        assertEquals(Integer.valueOf(3), useStatement.getParseStatusCode());
        assertEquals("USE_DATABASE", useStatement.getSkipReason());
        assertEquals("db2.out_t", result.getStatements().get(2).getOutputObject().getQualifiedName());
        assertContainsSource(result.getStatements().get(2), "db2.in_t");
    }

    @Test
    public void bareTableWithoutDatabaseFailsButLaterUseRecovers() {
        LineageAnalyzeResult result = analyze(
            null,
            "insert into failed_out(id) select id from failed_in;"
                + "use recovered_db;"
                + "insert into good_out(id) select id from good_in;",
            LineageAnalyzeOptions.builder().build(),
            null
        );

        assertEquals(3, result.getTotalStatementCount());
        assertEquals(1, result.getIgnoredStatementCount());
        assertEquals(1, result.getFailedStatementCount());
        assertEquals(2, result.getStatements().size());
        assertEquals("DATABASE_NOT_RESOLVED", result.getStatements().get(0).getFailureCode());
        assertEquals(Integer.valueOf(2), result.getStatements().get(0).getParseStatusCode());
        assertEquals("recovered_db.good_out", result.getStatements().get(1).getOutputObject().getQualifiedName());
        assertContainsSource(result.getStatements().get(1), "recovered_db.good_in");
    }

    @Test
    public void invalidUseFailsAndKeepsPreviousDatabase() {
        LineageAnalyzeResult result = analyze(
            "db1",
            "use; use db2 extra; use bad-name; insert into out_t(id) select id from in_t;",
            LineageAnalyzeOptions.builder().build(),
            null
        );

        assertEquals(4, result.getTotalStatementCount());
        assertEquals(0, result.getIgnoredStatementCount());
        assertEquals(3, result.getFailedStatementCount());
        assertEquals(4, result.getStatements().size());
        for (int i = 0; i < 3; i++) {
            assertEquals("USE", result.getStatements().get(i).getStatementType());
            assertEquals("INVALID_USE_DATABASE", result.getStatements().get(i).getFailureCode());
        }
        assertEquals("db1.out_t", result.getStatements().get(3).getOutputObject().getQualifiedName());
        assertContainsSource(result.getStatements().get(3), "db1.in_t");
    }

    @Test
    public void unresolvedUseVariableFailsAndKeepsPreviousDatabase() {
        LineageAnalyzeResult result = analyze(
            "db1",
            "use ${missing_db}; insert into out_t(id) select id from in_t;",
            LineageAnalyzeOptions.builder().build(),
            null
        );

        assertEquals(2, result.getTotalStatementCount());
        assertEquals(0, result.getIgnoredStatementCount());
        assertEquals(1, result.getFailedStatementCount());
        StatementLineageResult failedUse = result.getStatements().get(0);
        assertEquals("USE", failedUse.getStatementType());
        assertEquals("INVALID_USE_DATABASE", failedUse.getFailureCode());
        assertTrue(failedUse.getWarnings().stream()
            .anyMatch(warning -> warning.getWarningCode() == WarningCode.UNRESOLVED_VARIABLE));
        assertEquals("db1.out_t", result.getStatements().get(1).getOutputObject().getQualifiedName());
    }

    @Test
    public void analyzerDoesNotShareUseDatabaseAcrossRequests() {
        HiveScriptLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        LineageAnalyzeOptions options = LineageAnalyzeOptions.builder().build();

        LineageAnalyzeResult first = analyzer.analyzeScript(request(
            null,
            "use db3; insert into out_t(id) select id from in_t;",
            options,
            null
        ));
        LineageAnalyzeResult second = analyzer.analyzeScript(request(
            null,
            "insert into out_t(id) select id from in_t;",
            options,
            null
        ));

        assertEquals("db3.out_t", first.getStatements().get(0).getOutputObject().getQualifiedName());
        assertEquals(1, second.getFailedStatementCount());
        assertEquals("DATABASE_NOT_RESOLVED", second.getStatements().get(0).getFailureCode());
    }

    @Test
    public void pureSelectJoinWithoutDatabaseFailsWhenJoinParsingIsOn() {
        LineageAnalyzeResult result = analyze(
            null,
            "select a.id from left_t a join right_t b on a.id=b.id;",
            LineageAnalyzeOptions.builder().parseJoinConditions(true).build(),
            null
        );

        assertEquals(1, result.getTotalStatementCount());
        assertEquals(0, result.getIgnoredStatementCount());
        assertEquals(1, result.getFailedStatementCount());
        assertEquals(1, result.getStatements().size());
        assertEquals("SELECT", result.getStatements().get(0).getStatementType());
        assertEquals("DATABASE_NOT_RESOLVED", result.getStatements().get(0).getFailureCode());
    }

    @Test
    public void fallbackJoinWithoutDatabaseFailsWhenTablesAreBare() {
        LineageAnalyzeResult result = analyze(
            null,
            "select ??? from left_t a join right_t b on a.id=b.id;",
            LineageAnalyzeOptions.builder().parseJoinConditions(true).build(),
            null
        );

        assertEquals(1, result.getFailedStatementCount());
        assertEquals(1, result.getStatements().size());
        assertEquals("SELECT", result.getStatements().get(0).getStatementType());
        assertEquals("DATABASE_NOT_RESOLVED", result.getStatements().get(0).getFailureCode());
    }

    @Test
    public void fallbackJoinWithQualifiedTablesDoesNotRequireDatabase() {
        LineageAnalyzeResult result = analyze(
            null,
            "select ??? from db1.left_t a join db2.right_t b on a.id=b.id;",
            LineageAnalyzeOptions.builder().parseJoinConditions(true).build(),
            null
        );

        assertEquals(0, result.getFailedStatementCount());
        assertEquals(1, result.getStatements().size());
        assertEquals(Integer.valueOf(0), result.getStatements().get(0).getParseStatusCode());
    }

    @Test
    public void pureSelectWithoutDatabaseRemainsIgnoredWhenJoinParsingIsOff() {
        LineageAnalyzeResult result = analyze(
            null,
            "select a.id from left_t a join right_t b on a.id=b.id;",
            LineageAnalyzeOptions.builder().build(),
            null
        );

        assertEquals(1, result.getTotalStatementCount());
        assertEquals(1, result.getIgnoredStatementCount());
        assertEquals(0, result.getFailedStatementCount());
        assertTrue(result.getStatements().isEmpty());
    }

    private LineageAnalyzeResult analyze(
        String defaultDatabase,
        String script,
        LineageAnalyzeOptions options,
        HiveMetadataProvider metadataProvider
    ) {
        return new HiveScriptLineageAnalyzer().analyzeScript(request(defaultDatabase, script, options, metadataProvider));
    }

    private LineageAnalyzeRequest request(
        String defaultDatabase,
        String script,
        LineageAnalyzeOptions options,
        HiveMetadataProvider metadataProvider
    ) {
        return LineageAnalyzeRequest.builder()
            .workflowId("wf_database")
            .taskId("task_database")
            .taskVersion("V1")
            .defaultDatabase(defaultDatabase)
            .rawScript(script)
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .options(options)
            .build();
    }
}
