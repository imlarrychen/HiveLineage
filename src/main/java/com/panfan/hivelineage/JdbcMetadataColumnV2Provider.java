package com.panfan.hivelineage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class JdbcMetadataColumnV2Provider implements HiveMetadataProvider {
    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String tableName;

    public JdbcMetadataColumnV2Provider(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, "metadata_column_v2");
    }

    public JdbcMetadataColumnV2Provider(String jdbcUrl, String username, String password, String tableName) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.tableName = normalizeTableName(tableName);
    }

    @Override
    public HiveTableMetadata getTableMetadata(TableIdentifier tableIdentifier) {
        List<String> columns = new ArrayList<>();
        String sql = "select column_name from " + tableName
            + " where lower(db_en_name) = lower(?)"
            + " and lower(tb_en_name) = lower(?)"
            + " and (is_active is null or is_active <> b'0')"
            + " order by integer_idx asc, id asc";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableIdentifier.getDatabaseName());
            statement.setString(2, tableIdentifier.getTableName());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString("column_name"));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to query Hive metadata columns for " + tableIdentifier.getQualifiedName(), ex);
        }
        if (columns.isEmpty()) {
            return null;
        }
        return HiveTableMetadata.table(tableIdentifier, columns);
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
