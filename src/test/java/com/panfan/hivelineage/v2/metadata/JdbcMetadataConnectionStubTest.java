package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.metadata.JdbcMetadataColumnV2Provider;
import com.panfan.hivelineage.metadata.TableIdentifier;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Objects;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JdbcMetadataConnectionStubTest {
    private static final String COLUMN_QUERY = "select column_name from metadata_column_v2 where lower(db_en_name) = lower(?)"
            + " and lower(tb_en_name) = lower(?) and (is_active is null or is_active <> b'0') order by integer_idx asc, id asc";
    private static final String COLUMN_INSERT = "insert into metadata_column_v2 "
            + "(id, db_en_name, tb_en_name, column_name, column_type, integer_idx, is_active) values (?, ?, ?, ?, ?, ?, b'1')";
    private static final String VIEW_INSERT = "insert into hive_view_parse_info "
            + "(id, view_tbl_id, view_db_id, view_name, view_source, failed_status) values (?, ?, ?, ?, ?, b'0')";

    @Test
    public void realProviderUsesCaseInsensitiveFilteringActiveNullAndIndexThenIdOrder() throws Exception {
        try (JdbcMetadataConnectionStub stub = JdbcMetadataConnectionStub.open()) {
            stub.putColumn(4, "ODS", "USERS", "later-id", 1, true);
            stub.putColumn(3, "ods", "users", "earlier-id", 1, null);
            stub.putColumn(2, "ods", "users", "first-index", 0, true);
            stub.putColumn(1, "ods", "users", "null-index", null, true);
            stub.putColumn(5, "ods", "users", "inactive", -10, false);
            stub.putColumn(6, "other", "users", "other-database", -10, true);
            stub.putColumn(7, "ods", "other", "other-table", -10, true);
            HiveTableMetadata metadata = provider(stub).getTableMetadata(table("ods", "users"));
            assertEquals(Arrays.asList("null-index", "first-index", "earlier-id", "later-id"), metadata.getColumns());
            assertNull(provider(stub).getTableMetadata(table("ods", "missing")));
            assertEquals(4, stub.getStats().getQueryCount());
            stub.assertAllResourcesClosed();
        }
    }

    @Test
    public void realViewQueryExcludesFailedAndNullSourcesAndOrdersNewestThenIdDescending() throws Exception {
        try (JdbcMetadataConnectionStub stub = JdbcMetadataConnectionStub.open()) {
            stub.putColumn(1, "dim", "v", "id", 0, true);
            stub.putView(3, "DIM.V", "ODS.OLDER", null, 10);
            stub.putView(7, "v", "ods.middle", false, 20);
            stub.putView(8, "dim.v", "ods.newest", false, 20);
            stub.putView(9, "dim.v", "ods.newest", false, 20);
            stub.putView(10, "dim.v", "ods.failed", true, 100);
            stub.putView(11, "dim.v", null, false, 100);
            stub.putView(12, "other.v", "ods.other", false, 100);
            stub.putView(13, "dim.v", " ", false, 100);
            HiveTableMetadata metadata = provider(stub).getTableMetadata(table("dim", "v"));
            assertEquals("select id from ods.newest union all select id from ods.middle union all select id from ods.older", metadata.getViewOriginalSql());
            stub.assertAllResourcesClosed();
        }
    }

    @Test
    public void exactQueriesCannotLoseFiltersOrOrderingAndUnsupportedOperationsThrow() throws Exception {
        try (JdbcMetadataConnectionStub stub = JdbcMetadataConnectionStub.open(); Connection connection = connect(stub)) {
            assertThrows(SQLException.class, () -> connection.prepareStatement(COLUMN_QUERY.replace(" and (is_active is null or is_active <> b'0')", "")));
            assertThrows(SQLException.class, () -> connection.prepareStatement(COLUMN_QUERY.replace(" order by integer_idx asc, id asc", "")));
            assertThrows(SQLException.class, () -> connection.prepareStatement("select column_name from metadata_column_v2"));
            assertThrows(SQLException.class, () -> connection.commit());
            try (Statement statement = connection.createStatement()) {
                assertThrows(SQLException.class, () -> statement.executeUpdate("delete from metadata_column_v2"));
                assertThrows(SQLException.class, () -> statement.execute("select 1"));
            }
        }
    }

    @Test
    public void preparedParametersCursorStateAndCascadingResourceClosureAreEnforced() throws Exception {
        try (JdbcMetadataConnectionStub stub = JdbcMetadataConnectionStub.open()) {
            stub.putColumn(1, "ods", "users", "id", 0, true);
            Connection connection = connect(stub);
            PreparedStatement statement = connection.prepareStatement(COLUMN_QUERY);
            statement.setString(1, "ods");
            assertThrows(SQLException.class, statement::executeQuery);
            assertThrows(SQLException.class, () -> statement.setString(3, "invalid"));
            statement.setString(2, "users");
            ResultSet result = statement.executeQuery();
            assertThrows(SQLException.class, () -> result.getString("column_name"));
            assertTrue(result.next());
            assertEquals("id", result.getString(1));
            assertThrows(SQLException.class, () -> result.getString("unknown_column"));
            assertFalse(result.next());
            connection.close();
            connection.close();
            assertTrue(statement.isClosed());
            assertTrue(result.isClosed());
            assertThrows(SQLException.class, result::next);
            assertEquals(1, stub.getStats().getOpenedConnections());
            assertEquals(1, stub.getStats().getClosedConnections());
            assertEquals(1, stub.getStats().getClosedStatements());
            assertEquals(1, stub.getStats().getClosedResultSets());
            stub.assertAllResourcesClosed();
        }
    }

    @Test
    public void connectionAndQueryFailuresAreOneShotAndKeepTheProvidersOriginalCause() throws Exception {
        try (JdbcMetadataConnectionStub stub = JdbcMetadataConnectionStub.open()) {
            stub.putColumn(1, "ods", "users", "id", 0, true);
            SQLException connectionFailure = new SQLException("stub connection failure", "08001");
            stub.failNextConnection(connectionFailure);
            assertSame(connectionFailure, assertThrows(IllegalStateException.class,
                    () -> provider(stub).getTableMetadata(table("ods", "users"))).getCause());
            assertEquals(0, stub.getStats().getOpenedConnections());
            assertEquals(1, provider(stub).getTableMetadata(table("ods", "users")).getColumns().size());
            SQLException queryFailure = new SQLException("stub query failure", "42000");
            stub.failNextQuery(queryFailure);
            assertSame(queryFailure, assertThrows(IllegalStateException.class,
                    () -> provider(stub).getTableMetadata(table("ods", "users"))).getCause());
            assertEquals(1, provider(stub).getTableMetadata(table("ods", "users")).getColumns().size());
            assertEquals(4, stub.getStats().getConnectionAttempts());
            stub.assertAllResourcesClosed();
        }
    }

    @Test
    public void dedicatedDriversAndNestedPropertyScopesRestoreEveryPriorValue() throws Exception {
        String[] previous = properties();
        String url;
        try (JdbcMetadataConnectionStub outer = JdbcMetadataConnectionStub.open(); JdbcMetadataConnectionStub.PropertyScope outerScope = outer.installLegacyProperties()) {
            url = outer.getUrl();
            outer.putColumn(1, "ods", "users", "outer", 0, true);
            assertEquals(url, System.getProperty("lineage.mysql.url"));
            try (JdbcMetadataConnectionStub inner = JdbcMetadataConnectionStub.open(); JdbcMetadataConnectionStub.PropertyScope innerScope = inner.installLegacyProperties()) {
                inner.putColumn(1, "ods", "users", "inner", 0, true);
                assertEquals(inner.getUrl(), System.getProperty("lineage.mysql.url"));
                assertEquals("outer", provider(outer).getTableMetadata(table("ods", "users")).getColumns().get(0));
                assertEquals("inner", provider(inner).getTableMetadata(table("ods", "users")).getColumns().get(0));
                outer.assertAllResourcesClosed();
                inner.assertAllResourcesClosed();
            }
            assertEquals(url, System.getProperty("lineage.mysql.url"));
        }
        String[] after = properties();
        for (int index = 0; index < previous.length; index++) {
            assertTrue("Prior system property must be restored without exposing its value", Objects.equals(previous[index], after[index]));
        }
        assertThrows(SQLException.class, () -> DriverManager.getDriver(url));
    }

    @Test
    public void oldFixtureDeletesAndTypedPreparedInsertsAffectOnlyTheirMatchedRows() throws Exception {
        try (JdbcMetadataConnectionStub stub = JdbcMetadataConnectionStub.open(); Connection connection = connect(stub);
             Statement delete = connection.createStatement(); PreparedStatement insert = connection.prepareStatement(COLUMN_INSERT)) {
            stub.putColumn(1, "ods_it", "user_base_it", "obsolete", 0, true);
            stub.putColumn(2, "other", "user_base_it", "keep", 0, true);
            assertEquals(1, delete.executeUpdate("delete from metadata_column_v2 where db_en_name in ('ods_it', 'dwd_it', 'dim_it')"
                    + " and tb_en_name in ('user_base_it', 'user_profile_it', 'v_user_it')"));
            insert.setLong(1, 3L);
            insert.setString(2, "ods_it");
            insert.setString(3, "user_base_it");
            insert.setString(4, "new");
            insert.setString(5, "string");
            insert.setInt(6, 1);
            assertEquals(1, insert.executeUpdate());
            assertThrows(SQLException.class, insert::executeUpdate);
            assertEquals("new", provider(stub).getTableMetadata(table("ods_it", "user_base_it")).getColumns().get(0));
            assertEquals("keep", provider(stub).getTableMetadata(table("other", "user_base_it")).getColumns().get(0));
            stub.putColumn(4, "ods_it", "join_left_it", "remove", 0, true);
            assertEquals(1, delete.executeUpdate("delete from metadata_column_v2 where db_en_name = 'ods_it' and tb_en_name in ('join_left_it', 'join_right_it')"));
            try (PreparedStatement view = connection.prepareStatement(VIEW_INSERT)) {
                view.setLong(1, 8);
                view.setLong(2, 9);
                view.setLong(3, 10);
                view.setString(4, "dim_it.v_user_it");
                view.setString(5, "ods_it.user_base_it");
                assertEquals(1, view.executeUpdate());
            }
            assertEquals(1, delete.executeUpdate("delete from hive_view_parse_info where view_name in ('dim_it.v_user_it', 'v_user_it')"));
        }
    }

    @Test
    public void leakedResourcesFailTheAssertionAndDriverIsStillUnregistered() throws Exception {
        JdbcMetadataConnectionStub stub = JdbcMetadataConnectionStub.open();
        Connection connection = connect(stub);
        assertThrows(AssertionError.class, stub::assertAllResourcesClosed);
        assertThrows(AssertionError.class, stub::close);
        assertThrows(SQLException.class, () -> DriverManager.getDriver(stub.getUrl()));
        connection.close();
        stub.assertAllResourcesClosed();
        stub.close();
    }

    private static Connection connect(JdbcMetadataConnectionStub stub) throws SQLException {
        return DriverManager.getConnection(stub.getUrl(), "stub-user", "stub-password");
    }

    private static JdbcMetadataColumnV2Provider provider(JdbcMetadataConnectionStub stub) {
        return new JdbcMetadataColumnV2Provider(stub.getUrl(), "stub-user", "stub-password");
    }

    private static TableIdentifier table(String database, String table) {
        return TableIdentifier.of("default", database, table);
    }

    private static String[] properties() {
        return new String[]{System.getProperty("lineage.mysql.url"), System.getProperty("lineage.mysql.user"), System.getProperty("lineage.mysql.password")};
    }
}
