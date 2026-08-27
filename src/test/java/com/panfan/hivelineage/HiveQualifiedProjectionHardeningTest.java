package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.core.HiveAstParserAdapter;
import com.panfan.hivelineage.core.HiveParserAstAdapter;
import com.panfan.hivelineage.core.ParsedHiveStatement;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.WarningCode;
import java.util.List;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.*;

public class HiveQualifiedProjectionHardeningTest {
    @Test
    public void unionBranchesExpandSameAliasWithinEachBranch() {
        assertUnionStarLineage("x", "x");
    }

    @Test
    public void unionBranchesExpandDistinctAliasesWithinEachBranch() {
        assertUnionStarLineage("x", "y");
    }

    @Test
    public void unresolvedFirstBranchAliasDoesNotBindLaterUnionAlias() {
        assertUnresolvedFirstUnionStar(
            "x.* from db1.a a union all select x.* from db2.b x",
            "later alias"
        );
    }

    @Test
    public void unresolvedFirstBranchSimpleNameDoesNotBindLaterUnionTable() {
        assertUnresolvedFirstUnionStar(
            "b.* from db1.a union all select b.* from db2.b",
            "later simple name"
        );
    }

    @Test
    public void ambiguousQualifiedColumnDoesNotGuessSimpleTableOwner() {
        MapMetadataProvider metadata = baseMetadata()
            .addTable("db1.b", "id")
            .addTable("db2.b", "id");
        StatementLineageResult statement = analyze(
            metadata,
            "insert into target.c select b.id from db1.b join db2.b on db1.b.id = db2.b.id;"
        );

        assertUnknownEmptySource(statement, "ambiguous qualified column");
    }

    @Test
    public void unknownQualifiedColumnInComplexExpressionDoesNotBecomePhysicalOwner() {
        StatementLineageResult statement = analyze(
            baseMetadata().addTable("db1.a", "id"),
            "insert into target.c select missing.id + 1 from db1.a;"
        );

        assertUnknownEmptySource(statement, "unknown qualified column");
    }

    @Test
    public void unsupportedFourPartTokenIsNotSplitIntoFabricatedSources() {
        StatementLineageResult statement = analyze(
            baseMetadata().addTable("db1.a", "id"),
            "insert into target.c select catalog.db1.a.id + 1 from db1.a;"
        );

        assertUnknownEmptySource(statement, "four-part qualified column");
    }

    @Test
    public void threePartTokenRequiresKnownTwoPartOwner() {
        StatementLineageResult statement = analyze(
            baseMetadata().addTable("db1.a", "payload"),
            "insert into target.c select a.payload.value + 1 from db1.a a;"
        );

        assertUnknownEmptySource(statement, "unknown two-part owner");
    }

    private void assertUnionStarLineage(String firstAlias, String secondAlias) {
        MapMetadataProvider metadata = new MapMetadataProvider()
            .addTable("db1.a", "id", "name")
            .addTable("db2.b", "id", "name")
            .addTable("target.union_c", "id", "name");
        StatementLineageResult statement = analyze(
            metadata,
            "insert into target.union_c select " + firstAlias + ".* from db1.a " + firstAlias
                + " union all select " + secondAlias + ".* from db2.b " + secondAlias + ";"
        );

        List<ColumnLineage> columns = statement.getColumnLineages();
        TestSupport.assertEquals(2, columns.size(), "union qualified star column count");
        assertContainsColumn(columns.get(0).getSourceColumns(), "db1.a.id");
        assertContainsColumn(columns.get(0).getSourceColumns(), "db2.b.id");
        assertContainsColumn(columns.get(1).getSourceColumns(), "db1.a.name");
        assertContainsColumn(columns.get(1).getSourceColumns(), "db2.b.name");
        TestSupport.assertEquals("union_all(db1.a.id, db2.b.id)", columns.get(0).getExpression(), "union id expression");
        TestSupport.assertEquals("union_all(db1.a.name, db2.b.name)", columns.get(1).getExpression(), "union name expression");
    }

    private void assertUnresolvedFirstUnionStar(String unionProjection, String message) {
        MapMetadataProvider metadata = new MapMetadataProvider()
            .addTable("db1.a", "id")
            .addTable("db2.b", "id", "name")
            .addTable("target.union_c", "id");
        StatementLineageResult statement = analyze(
            metadata,
            "insert into target.union_c select " + unionProjection + ";",
            new HiveScriptLineageAnalyzer(new PreservingUnionStarParser(unionProjection.substring(0, 1) + ".*"))
        );

        assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
        TestSupport.assertEquals(1, statement.getColumnLineages().size(), message + " lineage count");
        ColumnLineage column = statement.getColumnLineages().get(0);
        TestSupport.assertEquals(0, column.getSourceColumns().size(), message + " source count");
    }

    private MapMetadataProvider baseMetadata() {
        return new MapMetadataProvider().addTable("target.c", "id");
    }

    private StatementLineageResult analyze(MapMetadataProvider metadata, String sql) {
        return analyze(metadata, sql, new HiveScriptLineageAnalyzer());
    }

    private StatementLineageResult analyze(
        MapMetadataProvider metadata,
        String sql,
        HiveScriptLineageAnalyzer analyzer
    ) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_qualified_projection_hardening")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .rawScript(sql)
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();
        return analyzer.analyzeScript(request).getStatements().get(0);
    }

    private void assertUnknownEmptySource(StatementLineageResult statement, String message) {
        assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
        TestSupport.assertEquals(1, statement.getColumnLineages().size(), message + " lineage count");
        TestSupport.assertEquals(0, statement.getColumnLineages().get(0).getSourceColumns().size(), message + " sources");
    }

    private static final class PreservingUnionStarParser implements HiveAstParserAdapter {
        private final HiveParserAstAdapter delegate = new HiveParserAstAdapter();
        private final String firstProjection;

        private PreservingUnionStarParser(String firstProjection) {
            this.firstProjection = firstProjection;
        }

        @Override
        public ParsedHiveStatement parse(String sql) {
            ParsedHiveStatement parsed = delegate.parse(sql);
            return new ParsedHiveStatement(
                parsed.getStatementType(),
                parsed.getOutputObject(),
                parsed.getInputObjects(),
                parsed.getTargetColumns(),
                java.util.Collections.singletonList(firstProjection),
                parsed.getSourceAliases(),
                parsed.getColumnAliasSources(),
                parsed.getColumnAliasExpressions(),
                parsed.getSetOperationSourceColumnsBySelectIndex(),
                parsed.getSetOperationExpressionsBySelectIndex(),
                parsed.getStaticPartitionValues(),
                parsed.getJoinConditions(),
                parsed.getNormalizedSql()
            );
        }
    }
}
