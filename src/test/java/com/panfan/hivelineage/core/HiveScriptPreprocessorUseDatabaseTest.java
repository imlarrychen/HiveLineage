package com.panfan.hivelineage.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HiveScriptPreprocessorUseDatabaseTest {
    private final HiveScriptPreprocessor preprocessor = new HiveScriptPreprocessor();

    @Test
    public void parsesPlainDatabase() {
        UseDatabaseResolution resolution = preprocessor.resolveUseDatabase("use db1", false);

        assertTrue(resolution.isValid());
        assertEquals("db1", resolution.getDatabase());
    }

    @Test
    public void parsesAndNormalizesBacktickDatabase() {
        UseDatabaseResolution resolution = preprocessor.resolveUseDatabase("USE `WHC`", false);

        assertTrue(resolution.isValid());
        assertEquals("whc", resolution.getDatabase());
    }

    @Test
    public void rejectsIncompleteUse() {
        assertInvalid(preprocessor.resolveUseDatabase("use", false));
    }

    @Test
    public void rejectsUseWithExtraToken() {
        assertInvalid(preprocessor.resolveUseDatabase("use db1 extra", false));
    }

    @Test
    public void rejectsUseWithUnresolvedVariable() {
        assertInvalid(preprocessor.resolveUseDatabase("use __fallback__", true));
    }

    @Test
    public void rejectsMalformedDatabaseIdentifiers() {
        assertInvalid(preprocessor.resolveUseDatabase("use db1.other", false));
        assertInvalid(preprocessor.resolveUseDatabase("use ``", false));
        assertInvalid(preprocessor.resolveUseDatabase("use `db one`", false));
        assertInvalid(preprocessor.resolveUseDatabase("use `db`name`", false));
        assertInvalid(preprocessor.resolveUseDatabase("use `db", false));
    }

    @Test
    public void identifiesUseCandidatesWithoutMatchingSimilarWords() {
        assertTrue(preprocessor.isUseDatabaseCandidate("use"));
        assertTrue(preprocessor.isUseDatabaseCandidate("  USE\tdb1  "));
        assertFalse(preprocessor.isUseDatabaseCandidate("user_table"));
        assertFalse(preprocessor.isUseDatabaseCandidate(null));
    }

    private void assertInvalid(UseDatabaseResolution resolution) {
        assertFalse(resolution.isValid());
        assertNotNull(resolution.getFailureMessage());
    }
}
