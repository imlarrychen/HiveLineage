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
}
