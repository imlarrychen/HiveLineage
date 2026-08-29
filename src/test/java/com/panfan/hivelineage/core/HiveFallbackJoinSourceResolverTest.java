package com.panfan.hivelineage.core;

import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HiveFallbackJoinSourceResolverTest {
    private final HiveFallbackJoinSourceResolver resolver =
        new HiveFallbackJoinSourceResolver();

    @Test
    public void preservesRepeatedSourceOccurrences() {
        String sql = "select ??? from t join db1.t on 1=1";

        List<FallbackJoinSource> sources = resolver.resolve(
            sql,
            Collections.<String>emptySet()
        );

        assertEquals(2, sources.size());
        assertSource(sources.get(0), "t", sql.indexOf("t join"));
        assertSource(sources.get(1), "db1.t", sql.indexOf("db1.t"));
        assertEquals(sources.get(0).getQueryBlockPath(), sources.get(1).getQueryBlockPath());
    }

    @Test
    public void skipsStringsCommentsAndNormalizesQuotedRelations() {
        String sql = "select 'from ignored', \"join ignored\" /* from ghost */ "
            + "from `DB1`.`LEFT_T` a -- join line_ghost\n"
            + "join `DB2`.`RIGHT_T` b on a.note='join phantom'";

        List<FallbackJoinSource> sources = resolver.resolve(
            sql,
            Collections.<String>emptySet()
        );

        assertEquals(2, sources.size());
        assertEquals("db1.left_t", sources.get(0).getQualifiedName());
        assertEquals("db2.right_t", sources.get(1).getQualifiedName());
    }

    @Test
    public void skipsEscapedAndDoubledQuotesInsideStrings() {
        String sql = "select 'value \\' from ghost', 'value '' join ghost', "
            + "\"value \"\" from ghost\" from db1.real_t";

        List<FallbackJoinSource> sources = resolver.resolve(
            sql,
            Collections.<String>emptySet()
        );

        assertEquals(1, sources.size());
        assertEquals("db1.real_t", sources.get(0).getQualifiedName());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void returnedSourcesAreUnmodifiable() {
        List<FallbackJoinSource> sources = resolver.resolve(
            "select ??? from t",
            Collections.<String>emptySet()
        );

        sources.add(new FallbackJoinSource("another", "q0", 0,
            FallbackJoinSource.Kind.PHYSICAL));
    }

    @Test
    public void sourceNodeExposesItsImmutableContract() {
        FallbackJoinSource source = new FallbackJoinSource(
            "db.t",
            "q0",
            12,
            FallbackJoinSource.Kind.PHYSICAL
        );

        assertEquals("db.t", source.getQualifiedName());
        assertEquals("q0", source.getQueryBlockPath());
        assertEquals(12, source.getStartOffset());
        assertEquals(FallbackJoinSource.Kind.PHYSICAL, source.getKind());
        assertTrue(source.isPhysical());
        assertFalse(new FallbackJoinSource("c", "q0", 1,
            FallbackJoinSource.Kind.CTE).isPhysical());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullSqlIsRejected() {
        resolver.resolve(null, Collections.<String>emptySet());
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptySourceNameIsRejected() {
        new FallbackJoinSource("", "q0", 0, FallbackJoinSource.Kind.PHYSICAL);
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyQueryBlockPathIsRejected() {
        new FallbackJoinSource("t", "", 0, FallbackJoinSource.Kind.PHYSICAL);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeSourceOffsetIsRejected() {
        new FallbackJoinSource("t", "q0", -1, FallbackJoinSource.Kind.PHYSICAL);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullSourceKindIsRejected() {
        new FallbackJoinSource("t", "q0", 0, null);
    }

    private void assertSource(FallbackJoinSource source, String name, int offset) {
        assertEquals(name, source.getQualifiedName());
        assertEquals(FallbackJoinSource.Kind.PHYSICAL, source.getKind());
        assertTrue(source.isPhysical());
        assertFalse(source.getQueryBlockPath().isEmpty());
        assertEquals(offset, source.getStartOffset());
    }
}
