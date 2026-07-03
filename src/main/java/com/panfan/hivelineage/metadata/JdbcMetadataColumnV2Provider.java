package com.panfan.hivelineage.metadata;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class JdbcMetadataColumnV2Provider implements HiveMetadataProvider {
    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String tableName;
    private final String viewInfoTableName;

    public JdbcMetadataColumnV2Provider(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, "metadata_column_v2", "hive_view_parse_info");
    }

    public JdbcMetadataColumnV2Provider(String jdbcUrl, String username, String password, String tableName) {
        this(jdbcUrl, username, password, tableName, "hive_view_parse_info");
    }

    public JdbcMetadataColumnV2Provider(
        String jdbcUrl,
        String username,
        String password,
        String tableName,
        String viewInfoTableName
    ) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.tableName = normalizeTableName(tableName);
        this.viewInfoTableName = normalizeTableName(viewInfoTableName);
    }

    @Override
    public HiveTableMetadata getTableMetadata(TableIdentifier tableIdentifier) {
        List<String> columns = new ArrayList<>();
        String sql = "select column_name from " + tableName
            + " where lower(db_en_name) = lower(?)"
            + " and lower(tb_en_name) = lower(?)"
            + " and (is_active is null or is_active <> b'0')"
            + " order by integer_idx asc, id asc";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tableIdentifier.getDatabaseName());
                statement.setString(2, tableIdentifier.getTableName());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        columns.add(resultSet.getString("column_name"));
                    }
                }
            }
            List<String> viewSources = queryViewSources(connection, tableIdentifier);
            if (!viewSources.isEmpty()) {
                return HiveTableMetadata.view(tableIdentifier, columns, buildViewOriginalSql(columns, viewSources));
            }
            if (columns.isEmpty()) {
                return null;
            }
            return HiveTableMetadata.table(tableIdentifier, columns);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to query Hive metadata for " + tableIdentifier.getQualifiedName(), ex);
        }
    }

    private List<String> queryViewSources(Connection connection, TableIdentifier tableIdentifier) throws SQLException {
        String sql = "select view_source from " + viewInfoTableName
            + " where (lower(view_name) = lower(?) or lower(view_name) = lower(?))"
            + " and (failed_status is null or failed_status <> b'1')"
            + " and view_source is not null"
            + " order by modified_time desc, id desc";
        Set<String> sources = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableIdentifier.getQualifiedName());
            statement.setString(2, tableIdentifier.getTableName());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String viewSource = resultSet.getString("view_source");
                    if (viewSource != null && !viewSource.trim().isEmpty()) {
                        sources.add(viewSource.trim().toLowerCase());
                    }
                }
            }
        }
        return new ArrayList<>(sources);
    }

    private String buildViewOriginalSql(List<String> columns, List<String> viewSources) {
        String selectList = columns.isEmpty() ? "*" : joinWithComma(columns);
        StringBuilder builder = new StringBuilder();
        for (String viewSource : viewSources) {
            if (builder.length() > 0) {
                builder.append(" union all ");
            }
            builder.append("select ")
                .append(selectList)
                .append(" from ")
                .append(viewSource);
        }
        return builder.toString();
    }

    private String joinWithComma(List<String> parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(part);
        }
        return builder.toString();
    }

    private String normalizeTableName(String configuredTableName) {
        String normalized = configuredTableName == null || configuredTableName.trim().isEmpty()
            ? "metadata_column_v2"
            : configuredTableName.trim();
        if (!SAFE_TABLE_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("metadata table name contains illegal characters: " + configuredTableName);
        }
        return normalized;
    }
}
