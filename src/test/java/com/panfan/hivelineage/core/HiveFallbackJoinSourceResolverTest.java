package com.panfan.hivelineage.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    @Test
    public void classifiesCteSubqueryPhysicalAndTemporaryOccurrencesPerScope() {
        String sql = "with c as (select id from bare_cte_base) "
            + "select ??? from c "
            + "join (select id from db2.inner_t) shadow on 1=1 "
            + "join shadow physical_shadow on 1=1 "
            + "join tmp_t x on 1=1";

        List<FallbackJoinSource> sources = resolver.resolve(
            sql,
            Collections.singleton("tmp_t")
        );

        assertSources(
            sources,
            source("bare_cte_base", FallbackJoinSource.Kind.PHYSICAL),
            source("c", FallbackJoinSource.Kind.CTE),
            source("db2.inner_t", FallbackJoinSource.Kind.PHYSICAL),
            source("shadow", FallbackJoinSource.Kind.SUBQUERY),
            source("shadow", FallbackJoinSource.Kind.PHYSICAL),
            source("tmp_t", FallbackJoinSource.Kind.TEMPORARY)
        );
        FallbackJoinSource subquery = findKind(
            sources,
            "shadow",
            FallbackJoinSource.Kind.SUBQUERY
        );
        FallbackJoinSource physical = findKind(
            sources,
            "shadow",
            FallbackJoinSource.Kind.PHYSICAL
        );
        assertNotEquals(subquery.getStartOffset(), physical.getStartOffset());
        assertEquals("q0", subquery.getQueryBlockPath());
        assertEquals("q0", physical.getQueryBlockPath());
        assertTrue(findKind(sources, "bare_cte_base", FallbackJoinSource.Kind.PHYSICAL)
            .getQueryBlockPath().startsWith("q0.q"));
        assertTrue(findKind(sources, "db2.inner_t", FallbackJoinSource.Kind.PHYSICAL)
            .getQueryBlockPath().startsWith("q0.q"));
    }

    @Test
    public void parsesCommaSourcesWithoutTreatingExpressionCommasAsRelations() {
        List<FallbackJoinSource> sources = resolver.resolve(
            "select ??? from db1.a a, bare_b b join db2.c c "
                + "on coalesce(a.id,b.id)=c.id "
                + "where concat(a.id,b.id)<>''",
            Collections.<String>emptySet()
        );

        assertNames(sources, "db1.a", "bare_b", "db2.c");
    }

    @Test
    public void extractFunctionFromIsNotARelation() {
        List<FallbackJoinSource> sources = resolver.resolve(
            "select ??? from db1.a a join db2.b b "
                + "on extract(day from event_ts)=b.day",
            Collections.<String>emptySet()
        );

        assertNames(sources, "db1.a", "db2.b");
    }

    @Test
    public void retainsPhysicalSourcesAcrossMultipleNestedQueryBlocks() {
        List<FallbackJoinSource> sources = resolver.resolve(
            "select ??? from (select id from "
                + "(select id from db3.deep_t) middle_query) outer_query "
                + "join db4.outer_t o on 1=1",
            Collections.<String>emptySet()
        );

        assertSources(
            sources,
            source("db3.deep_t", FallbackJoinSource.Kind.PHYSICAL),
            source("middle_query", FallbackJoinSource.Kind.SUBQUERY),
            source("outer_query", FallbackJoinSource.Kind.SUBQUERY),
            source("db4.outer_t", FallbackJoinSource.Kind.PHYSICAL)
        );
        String deepPath = findKind(
            sources,
            "db3.deep_t",
            FallbackJoinSource.Kind.PHYSICAL
        ).getQueryBlockPath();
        String middlePath = findKind(
            sources,
            "middle_query",
            FallbackJoinSource.Kind.SUBQUERY
        ).getQueryBlockPath();
        assertTrue(deepPath.startsWith(middlePath + ".q"));
    }

    @Test
    public void makesEarlierCtesVisibleToLaterCtesAndTheMainQueryOnly() {
        List<FallbackJoinSource> sources = resolver.resolve(
            "with first_cte as (select id from db1.base_t), "
                + "second_cte as (select id from first_cte) "
                + "select ??? from second_cte "
                + "join (with local_name as (select id from db2.local_base) "
                + "select id from local_name) nested_query on 1=1 "
                + "join local_name physical_local on 1=1",
            Collections.<String>emptySet()
        );

        assertSources(
            sources,
            source("db1.base_t", FallbackJoinSource.Kind.PHYSICAL),
            source("first_cte", FallbackJoinSource.Kind.CTE),
            source("second_cte", FallbackJoinSource.Kind.CTE),
            source("db2.local_base", FallbackJoinSource.Kind.PHYSICAL),
            source("local_name", FallbackJoinSource.Kind.CTE),
            source("nested_query", FallbackJoinSource.Kind.SUBQUERY),
            source("local_name", FallbackJoinSource.Kind.PHYSICAL)
        );
    }

    @Test
    public void nullAndEmptyTemporaryTablesAreEquivalent() {
        String sql = "select ??? from tmp_t";

        List<FallbackJoinSource> nullSources = resolver.resolve(sql, null);
        List<FallbackJoinSource> emptySources = resolver.resolve(
            sql,
            Collections.<String>emptySet()
        );

        assertSources(nullSources, source("tmp_t", FallbackJoinSource.Kind.PHYSICAL));
        assertSources(emptySources, source("tmp_t", FallbackJoinSource.Kind.PHYSICAL));
    }

    @Test
    public void normalizesTemporaryNamesButKeepsQualifiedRelationsPhysical() {
        Set<String> temporaryTables = new HashSet<>();
        temporaryTables.add("TMP_T");
        temporaryTables.add("`QUOTED_TMP`");

        List<FallbackJoinSource> sources = resolver.resolve(
            "select ??? from `TMP_T` t "
                + "join quoted_tmp q on 1=1 "
                + "join db1.tmp_t qualified_tmp on 1=1",
            temporaryTables
        );

        assertSources(
            sources,
            source("tmp_t", FallbackJoinSource.Kind.TEMPORARY),
            source("quoted_tmp", FallbackJoinSource.Kind.TEMPORARY),
            source("db1.tmp_t", FallbackJoinSource.Kind.PHYSICAL)
        );
    }

    @Test
    public void skipsCommentsBetweenSourceKeywordAndRelation() {
        List<FallbackJoinSource> sources = resolver.resolve(
            "select ??? from /* source */ db1.a a "
                + "join -- next source\n db2.b b on 1=1",
            Collections.<String>emptySet()
        );

        assertNames(sources, "db1.a", "db2.b");
    }

    @Test
    public void quotedKeywordRelationsAndAliasesAreNotClauseBoundaries() {
        List<FallbackJoinSource> sources = resolver.resolve(
            "select ??? from `where` `group` "
                + "join db2.`order` `limit` on 1=1",
            Collections.<String>emptySet()
        );

        assertNames(sources, "where", "db2.order");
    }

    @Test(expected = IllegalArgumentException.class)
    public void unclosedQuotedRelationIsRejected() {
        resolver.resolve(
            "select ??? from `broken",
            Collections.<String>emptySet()
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void subqueryWithoutAliasBeforeJoinIsRejected() {
        resolver.resolve(
            "select ??? from (select id from db1.inner_t) "
                + "join db2.outer_t o on 1=1",
            Collections.<String>emptySet()
        );
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

    private ExpectedSource source(String name, FallbackJoinSource.Kind kind) {
        return new ExpectedSource(name, kind);
    }

    private void assertSources(
        List<FallbackJoinSource> actual,
        ExpectedSource... expected
    ) {
        assertEquals("source count", expected.length, actual.size());
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index].name, actual.get(index).getQualifiedName());
            assertEquals(expected[index].kind, actual.get(index).getKind());
            if (index > 0) {
                assertTrue(
                    "sources must be ordered by SQL offset",
                    actual.get(index - 1).getStartOffset()
                        <= actual.get(index).getStartOffset()
                );
            }
        }
    }

    private void assertNames(List<FallbackJoinSource> actual, String... expected) {
        assertEquals("source count", expected.length, actual.size());
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], actual.get(index).getQualifiedName());
        }
    }

    private FallbackJoinSource findKind(
        List<FallbackJoinSource> sources,
        String name,
        FallbackJoinSource.Kind kind
    ) {
        for (FallbackJoinSource source : sources) {
            if (name.equals(source.getQualifiedName()) && kind == source.getKind()) {
                return source;
            }
        }
        fail("Missing source " + name + " with kind " + kind);
        throw new AssertionError("unreachable");
    }

    private static final class ExpectedSource {
        private final String name;
        private final FallbackJoinSource.Kind kind;

        private ExpectedSource(String name, FallbackJoinSource.Kind kind) {
            this.name = name;
            this.kind = kind;
        }
    }
}
