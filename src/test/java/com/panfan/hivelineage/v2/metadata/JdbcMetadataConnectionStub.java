package com.panfan.hivelineage.v2.metadata;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Test-only JDBC boundary: executes the provider's exact metadata queries in memory. */
public final class JdbcMetadataConnectionStub implements AutoCloseable {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern COLUMN_DELETE_IN = Pattern.compile("delete from metadata_column_v2 where db_en_name in \\(([^)]+)\\) and tb_en_name in \\(([^)]+)\\)");
    private static final Pattern COLUMN_DELETE_EQUAL = Pattern.compile("delete from metadata_column_v2 where db_en_name = '([^']*)' and tb_en_name in \\(([^)]+)\\)");
    private static final Pattern VIEW_DELETE = Pattern.compile("delete from hive_view_parse_info where view_name in \\(([^)]+)\\)");
    private static final Pattern QUOTED_VALUE = Pattern.compile("'([^']*)'");
    private static final String COLUMN_SELECT = "select column_name from metadata_column_v2 where lower(db_en_name) = lower(?) and lower(tb_en_name) = lower(?) and (is_active is null or is_active <> b'0') order by integer_idx asc, id asc";
    private static final String VIEW_SELECT = "select view_source from hive_view_parse_info where (lower(view_name) = lower(?) or lower(view_name) = lower(?)) and (failed_status is null or failed_status <> b'1') and view_source is not null order by modified_time desc, id desc";
    private static final String COLUMN_INSERT = "insert into metadata_column_v2 (id, db_en_name, tb_en_name, column_name, column_type, integer_idx, is_active) values (?, ?, ?, ?, ?, ?, b'1')";
    private static final String VIEW_INSERT = "insert into hive_view_parse_info (id, view_tbl_id, view_db_id, view_name, view_source, failed_status) values (?, ?, ?, ?, ?, b'0')";
    private static final String[] PROPERTY_KEYS = { "lineage.mysql.url", "lineage.mysql.user", "lineage.mysql.password" };
    private static final Object PROPERTY_LOCK = new Object();
    private static final Deque<PropertyScope> PROPERTY_SCOPES = new ArrayDeque<>();
    private final String url = "jdbc:lineage-stub:" + UUID.randomUUID();
    private final Driver driver = new StubDriver();
    private final Map<Long, ColumnRow> columns = new LinkedHashMap<>();
    private final Map<Long, ViewRow> views = new LinkedHashMap<>();
    private final List<PropertyScope> scopes = new ArrayList<>();
    private final int[] opened = new int[3];
    private final int[] closedResources = new int[3];
    private SQLException connectionFailure;
    private SQLException queryFailure;
    private int connectionAttempts;
    private int queries;
    private int updates;
    private boolean closed;

    private JdbcMetadataConnectionStub() {
    }

    public static JdbcMetadataConnectionStub open() throws SQLException {
        JdbcMetadataConnectionStub stub = new JdbcMetadataConnectionStub();
        DriverManager.registerDriver(stub.driver);
        return stub;
    }

    public String getUrl() {
        return url;
    }

    public synchronized SnapshotStats getStats() {
        return new SnapshotStats(opened.clone(), closedResources.clone(), connectionAttempts, queries, updates);
    }

    public synchronized void assertAllResourcesClosed() {
        if (!Arrays.equals(opened, closedResources)) {
            throw new AssertionError("JDBC stub resources remain open: connections=" + (opened[0] - closedResources[0])
                    + ", statements=" + (opened[1] - closedResources[1]) + ", resultSets=" + (opened[2] - closedResources[2]));
        }
    }

    public synchronized void failNextConnection(SQLException failure) {
        requireOpen();
        connectionFailure = Objects.requireNonNull(failure, "failure");
    }

    public synchronized void failNextQuery(SQLException failure) {
        requireOpen();
        queryFailure = Objects.requireNonNull(failure, "failure");
    }

    public synchronized void putColumn(long id, String database, String table, String name, Integer index, Boolean active) {
        requireOpen();
        if (columns.containsKey(id)) {
            throw new IllegalArgumentException("duplicate stub column row identity");
        }
        columns.put(id, new ColumnRow(id, database, table, name, index, active));
    }

    public synchronized void putView(long id, String name, String source, Boolean failed, long modifiedTime) {
        requireOpen();
        if (views.containsKey(id)) {
            throw new IllegalArgumentException("duplicate stub view row identity");
        }
        views.put(id, new ViewRow(id, name, source, failed, modifiedTime));
    }

    public PropertyScope installLegacyProperties() {
        synchronized (PROPERTY_LOCK) {
            requireOpen();
            PropertyScope scope = new PropertyScope(this);
            PROPERTY_SCOPES.push(scope);
            scopes.add(scope);
            System.setProperty(PROPERTY_KEYS[0], url);
            System.setProperty(PROPERTY_KEYS[1], "lineage-stub-user");
            System.setProperty(PROPERTY_KEYS[2], "lineage-stub-password");
            return scope;
        }
    }

    @Override
    public void close() throws SQLException {
        synchronized (PROPERTY_LOCK) {
            for (int index = scopes.size() - 1; index >= 0; index--) {
                scopes.get(index).close();
            }
        }
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            DriverManager.deregisterDriver(driver);
            assertAllResourcesClosed();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("JDBC stub is closed");
        }
    }

    private static String normalize(String sql) throws SQLException {
        if (sql == null) {
            throw new SQLException("SQL is required", "42000");
        }
        return WHITESPACE.matcher(sql.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    private static SQLException unknownSql() {
        return new SQLException("JDBC stub rejected unrecognized SQL", "42000");
    }

    private static SQLException unsupported(Method method) {
        return new SQLFeatureNotSupportedException("JDBC stub does not implement " + method.getDeclaringClass().getSimpleName() + "." + method.getName());
    }

    private static Set<String> literals(String text) throws SQLException {
        Set<String> values = new LinkedHashSet<>();
        for (String part : text.split(",")) {
            Matcher matcher = QUOTED_VALUE.matcher(part.trim());
            if (!matcher.matches()) {
                throw unknownSql();
            }
            values.add(matcher.group(1));
        }
        return values;
    }

    private final class StubDriver implements Driver {
        @Override
        public Connection connect(String requestedUrl, Properties properties) throws SQLException {
            if (!acceptsURL(requestedUrl)) {
                return null;
            }
            synchronized (JdbcMetadataConnectionStub.this) {
                connectionAttempts++;
                if (closed) {
                    throw new SQLException("JDBC stub is closed", "08003");
                }
                if (connectionFailure != null) {
                    SQLException failure = connectionFailure;
                    connectionFailure = null;
                    throw failure;
                }
                return new ConnectionHandle().proxy;
            }
        }

        @Override
        public boolean acceptsURL(String requestedUrl) {
            return url.equals(requestedUrl);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String requestedUrl, Properties properties) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(JdbcMetadataConnectionStub.class.getName());
        }
    }

    private abstract class Handle implements InvocationHandler {
        private final int kind;
        protected boolean resourceClosed;

        private Handle(int kind) {
            this.kind = kind;
            opened[kind]++;
        }

        @Override
        public final Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            synchronized (JdbcMetadataConnectionStub.this) {
                String name = method.getName();
                if (method.getDeclaringClass() == Object.class) {
                    if ("equals".equals(name)) {
                        return proxy == arguments[0];
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    return "LineageJdbcStub." + getClass().getSimpleName();
                }
                if ("close".equals(name)) {
                    closeResource();
                    return null;
                }
                if ("isClosed".equals(name)) {
                    return resourceClosed;
                }
                if (resourceClosed || closed) {
                    throw new SQLException("JDBC stub resource is closed", "08003");
                }
                if ("isWrapperFor".equals(name)) {
                    return ((Class<?>) arguments[0]).isInstance(proxy);
                }
                if ("unwrap".equals(name)) {
                    if (((Class<?>) arguments[0]).isInstance(proxy)) {
                        return proxy;
                    }
                    throw new SQLException("JDBC stub cannot unwrap requested type");
                }
                return operation(method, arguments == null ? new Object[0] : arguments);
            }
        }

        protected void closeResource() throws SQLException {
            if (!resourceClosed) {
                closeChildren();
                resourceClosed = true;
                closedResources[kind]++;
            }
        }

        protected void closeChildren() throws SQLException {
        }

        protected abstract Object operation(Method method, Object[] arguments) throws SQLException;
    }

    private final class ConnectionHandle extends Handle {
        private final Connection proxy;
        private final List<StatementHandle> statements = new ArrayList<>();

        private ConnectionHandle() {
            super(0);
            proxy = proxy(Connection.class, this);
        }

        @Override
        protected Object operation(Method method, Object[] arguments) throws SQLException {
            if ("createStatement".equals(method.getName()) && arguments.length == 0) {
                StatementHandle statement = new StatementHandle(this, null);
                statements.add(statement);
                return statement.proxy;
            }
            if ("prepareStatement".equals(method.getName()) && arguments.length == 1 && arguments[0] instanceof String) {
                String sql = normalize((String) arguments[0]);
                if (!Arrays.asList(COLUMN_SELECT, VIEW_SELECT, COLUMN_INSERT, VIEW_INSERT).contains(sql)) {
                    throw unknownSql();
                }
                StatementHandle statement = new StatementHandle(this, sql);
                statements.add(statement);
                return statement.proxy;
            }
            if ("getAutoCommit".equals(method.getName())) {
                return true;
            }
            if ("setAutoCommit".equals(method.getName()) && Boolean.TRUE.equals(arguments[0])) {
                return null;
            }
            throw unsupported(method);
        }

        @Override
        protected void closeChildren() throws SQLException {
            for (StatementHandle statement : statements) {
                statement.closeResource();
            }
        }
    }

    private final class StatementHandle extends Handle {
        private final ConnectionHandle connection;
        private final String sql;
        private final Statement proxy;
        private final Map<Integer, Object> parameters = new LinkedHashMap<>();
        private ResultHandle result;

        private StatementHandle(ConnectionHandle connection, String sql) {
            super(1);
            this.connection = connection;
            this.sql = sql;
            proxy = sql == null ? proxy(Statement.class, this) : proxy(PreparedStatement.class, this);
        }

        @Override
        protected Object operation(Method method, Object[] arguments) throws SQLException {
            String name = method.getName();
            if ("getConnection".equals(name)) {
                return connection.proxy;
            }
            if (sql != null && Arrays.asList("setString", "setLong", "setInt", "setBoolean", "setNull").contains(name)) {
                int index = (Integer) arguments[0];
                int count = sql.equals(COLUMN_INSERT) ? 6 : sql.equals(VIEW_INSERT) ? 5 : 2;
                if (index < 1 || index > count) {
                    throw new SQLException("JDBC stub parameter index is out of bounds", "07009");
                }
                parameters.put(index, "setNull".equals(name) ? null : arguments[1]);
                return null;
            }
            if (sql != null && "clearParameters".equals(name)) {
                parameters.clear();
                return null;
            }
            if ("executeQuery".equals(name) && sql != null && arguments.length == 0) {
                checkParameters(2);
                if (!COLUMN_SELECT.equals(sql) && !VIEW_SELECT.equals(sql)) {
                    throw unknownSql();
                }
                queries++;
                if (queryFailure != null) {
                    SQLException failure = queryFailure;
                    queryFailure = null;
                    throw failure;
                }
                closeChildren();
                result = new ResultHandle(this, sql.equals(COLUMN_SELECT) ? "column_name" : "view_source", select());
                return result.proxy;
            }
            if ("executeUpdate".equals(name) && sql == null && arguments.length == 1 && arguments[0] instanceof String) {
                int count = delete(normalize((String) arguments[0]));
                updates++;
                return count;
            }
            if ("executeUpdate".equals(name) && sql != null && arguments.length == 0) {
                int count = insert();
                updates++;
                return count;
            }
            throw unsupported(method);
        }

        private void checkParameters(int count) throws SQLException {
            if (parameters.size() != count) {
                throw new SQLException("JDBC stub requires every parameter", "07001");
            }
            for (int index = 1; index <= count; index++) {
                if (!parameters.containsKey(index)) {
                    throw new SQLException("JDBC stub requires every parameter", "07001");
                }
            }
        }

        private List<String> select() throws SQLException {
            String first = string(1);
            String second = string(2);
            List<String> values = new ArrayList<>();
            if (COLUMN_SELECT.equals(sql)) {
                List<ColumnRow> rows = new ArrayList<>();
                for (ColumnRow row : columns.values()) {
                    if (matches(row.database, first) && matches(row.table, second) && !Boolean.FALSE.equals(row.active)) {
                        rows.add(row);
                    }
                }
                rows.sort(Comparator.comparing((ColumnRow row) -> row.index, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingLong(row -> row.id));
                rows.forEach(row -> values.add(row.name));
            } else {
                List<ViewRow> rows = new ArrayList<>();
                for (ViewRow row : views.values()) {
                    if ((matches(row.name, first) || matches(row.name, second)) && !Boolean.TRUE.equals(row.failed) && row.source != null) {
                        rows.add(row);
                    }
                }
                rows.sort(Comparator.comparingLong((ViewRow row) -> row.modifiedTime).reversed()
                        .thenComparing(Comparator.comparingLong((ViewRow row) -> row.id).reversed()));
                rows.forEach(row -> values.add(row.source));
            }
            return values;
        }

        private int insert() throws SQLException {
            if (COLUMN_INSERT.equals(sql)) {
                checkParameters(6);
                long id = number(1).longValue();
                if (columns.containsKey(id)) {
                    throw new SQLException("duplicate stub column row identity", "23000");
                }
                string(5);
                columns.put(id, new ColumnRow(id, string(2), string(3), string(4), number(6).intValue(), true));
            } else if (VIEW_INSERT.equals(sql)) {
                checkParameters(5);
                long id = number(1).longValue();
                if (views.containsKey(id)) {
                    throw new SQLException("duplicate stub view row identity", "23000");
                }
                number(2);
                number(3);
                views.put(id, new ViewRow(id, string(4), string(5), false, updates));
            } else {
                throw unknownSql();
            }
            return 1;
        }

        private String string(int index) throws SQLException {
            Object value = parameters.get(index);
            if (value != null && !(value instanceof String)) {
                throw new SQLException("JDBC stub expected a string parameter", "07006");
            }
            return (String) value;
        }

        private Number number(int index) throws SQLException {
            Object value = parameters.get(index);
            if (!(value instanceof Number)) {
                throw new SQLException("JDBC stub expected a numeric parameter", "07006");
            }
            return (Number) value;
        }

        @Override
        protected void closeChildren() throws SQLException {
            if (result != null) {
                result.closeResource();
            }
        }
    }

    private int delete(String sql) throws SQLException {
        Matcher in = COLUMN_DELETE_IN.matcher(sql);
        Matcher equal = COLUMN_DELETE_EQUAL.matcher(sql);
        Matcher view = VIEW_DELETE.matcher(sql);
        if (in.matches() || equal.matches()) {
            Set<String> databases = in.matches() ? literals(in.group(1)) : new LinkedHashSet<>(Arrays.asList(equal.group(1)));
            Set<String> tables = literals(in.matches() ? in.group(2) : equal.group(2));
            int before = columns.size();
            columns.values().removeIf(row -> databases.contains(lower(row.database)) && tables.contains(lower(row.table)));
            return before - columns.size();
        }
        if (view.matches()) {
            Set<String> names = literals(view.group(1));
            int before = views.size();
            views.values().removeIf(row -> names.contains(lower(row.name)));
            return before - views.size();
        }
        throw unknownSql();
    }

    private final class ResultHandle extends Handle {
        private final StatementHandle statement;
        private final String column;
        private final List<String> values;
        private final ResultSet proxy;
        private int cursor = -1;
        private boolean wasNull;

        private ResultHandle(StatementHandle statement, String column, List<String> values) {
            super(2);
            this.statement = statement;
            this.column = column;
            this.values = values;
            proxy = proxy(ResultSet.class, this);
        }

        @Override
        protected Object operation(Method method, Object[] arguments) throws SQLException {
            if ("next".equals(method.getName())) {
                cursor = Math.min(values.size(), cursor + 1);
                return cursor < values.size();
            }
            if ("getStatement".equals(method.getName())) {
                return statement.proxy;
            }
            if ("wasNull".equals(method.getName())) {
                return wasNull;
            }
            if ("getString".equals(method.getName())) {
                boolean known = arguments[0] instanceof String && column.equalsIgnoreCase((String) arguments[0])
                        || arguments[0] instanceof Integer && ((Integer) arguments[0]) == 1;
                if (!known) {
                    throw new SQLException("JDBC stub has no requested result column", "S0022");
                }
                if (cursor < 0 || cursor >= values.size()) {
                    throw new SQLException("JDBC stub cursor is not on a result row", "24000");
                }
                String value = values.get(cursor);
                wasNull = value == null;
                return value;
            }
            throw unsupported(method);
        }
    }

    private static boolean matches(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static String lower(String text) {
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(JdbcMetadataConnectionStub.class.getClassLoader(), new Class<?>[] { type }, handler));
    }

    private static final class ColumnRow {
        private final long id;
        private final String database;
        private final String table;
        private final String name;
        private final Integer index;
        private final Boolean active;

        private ColumnRow(long id, String database, String table, String name, Integer index, Boolean active) {
            this.id = id;
            this.database = database;
            this.table = table;
            this.name = name;
            this.index = index;
            this.active = active;
        }
    }

    private static final class ViewRow {
        private final long id;
        private final String name;
        private final String source;
        private final Boolean failed;
        private final long modifiedTime;

        private ViewRow(long id, String name, String source, Boolean failed, long modifiedTime) {
            this.id = id;
            this.name = name;
            this.source = source;
            this.failed = failed;
            this.modifiedTime = modifiedTime;
        }
    }

    /** Snapshot counts contain no URL, username, password or parameter values. */
    public static final class SnapshotStats {
        private final int[] opened;
        private final int[] closed;
        private final int connectionAttempts;
        private final int queries;
        private final int updates;

        private SnapshotStats(int[] opened, int[] closed, int connectionAttempts, int queries, int updates) {
            this.opened = opened;
            this.closed = closed;
            this.connectionAttempts = connectionAttempts;
            this.queries = queries;
            this.updates = updates;
        }

        public int getOpenedConnections() {
            return opened[0];
        }

        public int getClosedConnections() {
            return closed[0];
        }

        public int getOpenConnections() {
            return opened[0] - closed[0];
        }

        public int getOpenedStatements() {
            return opened[1];
        }

        public int getClosedStatements() {
            return closed[1];
        }

        public int getOpenStatements() {
            return opened[1] - closed[1];
        }

        public int getOpenedResultSets() {
            return opened[2];
        }

        public int getClosedResultSets() {
            return closed[2];
        }

        public int getOpenResultSets() {
            return opened[2] - closed[2];
        }

        public int getConnectionAttempts() {
            return connectionAttempts;
        }

        public int getQueryCount() {
            return queries;
        }

        public int getUpdateCount() {
            return updates;
        }
    }

    public static final class PropertyScope implements AutoCloseable {
        private final JdbcMetadataConnectionStub owner;
        private final String[] previous = new String[PROPERTY_KEYS.length];
        private boolean closed;

        private PropertyScope(JdbcMetadataConnectionStub owner) {
            this.owner = owner;
            for (int index = 0; index < PROPERTY_KEYS.length; index++) {
                previous[index] = System.getProperty(PROPERTY_KEYS[index]);
            }
        }

        @Override
        public void close() {
            synchronized (PROPERTY_LOCK) {
                if (closed) {
                    return;
                }
                if (PROPERTY_SCOPES.peek() != this) {
                    throw new IllegalStateException("JDBC stub property scopes must close in nesting order");
                }
                PROPERTY_SCOPES.pop();
                for (int index = 0; index < PROPERTY_KEYS.length; index++) {
                    if (previous[index] == null) {
                        System.clearProperty(PROPERTY_KEYS[index]);
                    } else {
                        System.setProperty(PROPERTY_KEYS[index], previous[index]);
                    }
                }
                owner.scopes.remove(this);
                closed = true;
            }
        }
    }
}
