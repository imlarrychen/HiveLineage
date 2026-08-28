package com.panfan.hivelineage.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class HivePhysicalNameResolverTest {
    private final HivePhysicalNameResolver resolver = new HivePhysicalNameResolver();

    @Test
    public void bareTableUsesStatementDatabase() {
        assertEquals("whc.t1", resolver.qualifyTable("t1", "whc"));
    }

    @Test
    public void explicitDatabaseWins() {
        assertEquals("whc_02.t2", resolver.qualifyTable("whc_02.t2", "whc"));
    }

    @Test
    public void identifiersAreNormalized() {
        assertEquals("whc.t1", resolver.qualifyTable("`WHC`.`T1`", "`WHC`"));
    }

    @Test(expected = DatabaseResolutionException.class)
    public void bareTableWithoutDatabaseFails() {
        resolver.qualifyTable("t1", null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void qualifiedTableWithTrailingDotFails() {
        resolver.qualifyTable("db.t.", "whc");
    }

    @Test(expected = IllegalArgumentException.class)
    public void bareTableWithTrailingDotFails() {
        resolver.qualifyTable("t.", "whc");
    }

    @Test(expected = IllegalArgumentException.class)
    public void databaseWithTrailingDotFails() {
        resolver.qualifyTable("t", "db.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void databaseWithMultiplePartsFails() {
        resolver.qualifyTable("t", "db.other");
    }

    @Test(expected = IllegalArgumentException.class)
    public void databaseWithInternalWhitespaceFails() {
        resolver.qualifyTable("t", "db other");
    }

    @Test(expected = IllegalArgumentException.class)
    public void databaseWithUnpairedBacktickFails() {
        resolver.qualifyTable("t", "`db");
    }

    @Test(expected = IllegalArgumentException.class)
    public void databaseWithOnlyTrailingBacktickFails() {
        resolver.qualifyTable("t", "db`");
    }

    @Test(expected = IllegalArgumentException.class)
    public void tableWithOnlyTrailingBacktickFails() {
        resolver.qualifyTable("table`", "db");
    }

    @Test
    public void emptyDatabaseVariantsFailDatabaseResolution() {
        assertDatabaseResolutionFailure("");
        assertDatabaseResolutionFailure("   ");
        assertDatabaseResolutionFailure("``");
    }

    @Test(expected = DatabaseResolutionException.class)
    public void nullTableNameFailsDatabaseResolution() {
        resolver.qualifyTable(null, "db");
    }

    @Test(expected = DatabaseResolutionException.class)
    public void emptyTableNameFailsDatabaseResolution() {
        resolver.qualifyTable("", "db");
    }

    private void assertDatabaseResolutionFailure(String database) {
        try {
            resolver.qualifyTable("t", database);
        } catch (DatabaseResolutionException expected) {
            return;
        }
        throw new AssertionError("Expected DatabaseResolutionException for database: " + database);
    }
}
