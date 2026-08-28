package com.panfan.hivelineage.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
        assertInvalid(preprocessor.resolveUseDatabase("use `db\u2003name`", false));
        assertInvalid(preprocessor.resolveUseDatabase("use `db`name`", false));
        assertInvalid(preprocessor.resolveUseDatabase("use `db", false));
    }

    @Test
    public void identifiesUseCandidatesWithoutMatchingSimilarWords() {
        assertTrue(preprocessor.isUseDatabaseCandidate("use"));
        assertTrue(preprocessor.isUseDatabaseCandidate("  USE\tdb1  "));
        assertTrue(preprocessor.isUseDatabaseCandidate("use\u2003db"));
        assertTrue(preprocessor.isUseDatabaseCandidate("use.db"));
        assertInvalid(preprocessor.resolveUseDatabase("use\u2003db", false));
        assertInvalid(preprocessor.resolveUseDatabase("use.db", false));
        assertFalse(preprocessor.isUseDatabaseCandidate("user_table"));
        assertFalse(preprocessor.isUseDatabaseCandidate("useful"));
        assertFalse(preprocessor.isUseDatabaseCandidate("use_db"));
        assertFalse(preprocessor.isUseDatabaseCandidate(null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void validResolutionRejectsNullDatabase() {
        UseDatabaseResolution.valid(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void validResolutionRejectsBlankDatabase() {
        UseDatabaseResolution.valid("\u2003");
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidResolutionRejectsNullFailureMessage() {
        UseDatabaseResolution.invalid(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidResolutionRejectsBlankFailureMessage() {
        UseDatabaseResolution.invalid("\u2003");
    }

    private void assertInvalid(UseDatabaseResolution resolution) {
        assertFalse(resolution.isValid());
        assertNull(resolution.getDatabase());
        assertNotNull(resolution.getFailureMessage());
    }
}
