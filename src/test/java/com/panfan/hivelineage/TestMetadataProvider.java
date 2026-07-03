package com.panfan.hivelineage;

import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.metadata.TableIdentifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class TestMetadataProvider implements HiveMetadataProvider {
    private final Map<String, HiveTableMetadata> metadataByQualifiedName = new HashMap<>();
    private final AtomicInteger loadCount = new AtomicInteger();

    TestMetadataProvider addTable(String qualifiedName, String... columns) {
        String[] parts = splitQualifiedName(qualifiedName);
        metadataByQualifiedName.put(
            qualifiedName,
            HiveTableMetadata.table(
                TableIdentifier.of("default", parts[0], parts[1]),
                Arrays.asList(columns)
            )
        );
        return this;
    }

    TestMetadataProvider addView(String qualifiedName, String viewOriginalSql, String... columns) {
        String[] parts = splitQualifiedName(qualifiedName);
        metadataByQualifiedName.put(
            qualifiedName,
            HiveTableMetadata.view(
                TableIdentifier.of("default", parts[0], parts[1]),
                Arrays.asList(columns),
                viewOriginalSql
            )
        );
        return this;
    }

    @Override
    public HiveTableMetadata getTableMetadata(TableIdentifier tableIdentifier) {
        loadCount.incrementAndGet();
        return metadataByQualifiedName.get(tableIdentifier.getQualifiedName());
    }

    int getLoadCount() {
        return loadCount.get();
    }

    private String[] splitQualifiedName(String qualifiedName) {
        String[] parts = qualifiedName.split("\\.");
        if (parts.length == 1) {
            return new String[] {"default", parts[0]};
        }
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected db.table, got " + qualifiedName);
        }
        return parts;
    }
}
