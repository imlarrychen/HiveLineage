package com.panfan.hivelineage.core;

import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.LineageWarning;
import java.util.ArrayList;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ScriptAnalysisStateTest {
    private final HivePhysicalNameResolver nameResolver = new HivePhysicalNameResolver();

    @Test
    public void currentDatabaseStartsFromRequestAndCanSwitch() {
        ScriptAnalysisState state = new ScriptAnalysisState("`DB1`", nameResolver);

        assertEquals("db1", state.getCurrentDatabase());
        state.useDatabase("DB3");
        assertEquals("db3", state.getCurrentDatabase());
    }

    @Test
    public void blankDefaultDatabaseMeansNoCurrentDatabase() {
        assertNull(new ScriptAnalysisState("   ", nameResolver).getCurrentDatabase());
        assertNull(new ScriptAnalysisState("\u2003", nameResolver).getCurrentDatabase());
        assertNull(new ScriptAnalysisState("\u00a0", nameResolver).getCurrentDatabase());
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsNullNameResolver() {
        new ScriptAnalysisState("db1", null);
    }

    @Test
    public void switchingToNullOrBlankDatabaseIsRejectedWithoutChangingState() {
        ScriptAnalysisState state = new ScriptAnalysisState("db1", nameResolver);

        assertRejectedDatabaseSwitch(state, null);
        assertRejectedDatabaseSwitch(state, "   ");
        assertRejectedDatabaseSwitch(state, "\u00a0");
        assertRejectedDatabaseSwitch(state, "db\u00a0name");
    }

    @Test
    public void statementContextKeepsDatabaseSnapshot() {
        ScriptAnalysisState state = new ScriptAnalysisState("db1", nameResolver);
        StatementAnalysisContext context = new StatementAnalysisContext(
            LineageAnalyzeRequest.builder().rawScript("select 1").build(),
            "select 1",
            "select 1",
            0,
            state,
            state.getCurrentDatabase(),
            new ArrayList<LineageWarning>()
        );

        state.useDatabase("db3");

        assertEquals("db1", context.getStatementDatabase());
        assertEquals("db3", state.getCurrentDatabase());
    }

    private void assertRejectedDatabaseSwitch(ScriptAnalysisState state, String database) {
        try {
            state.useDatabase(database);
        } catch (IllegalArgumentException expected) {
            assertEquals("db1", state.getCurrentDatabase());
            return;
        }
        throw new AssertionError("Expected invalid database switch to be rejected");
    }
}
