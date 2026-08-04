package com.panfan.hivelineage;

import com.panfan.hivelineage.core.*;
import com.panfan.hivelineage.metadata.*;
import com.panfan.hivelineage.request.*;
import com.panfan.hivelineage.result.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class AnalyzerTestSupport {
    private AnalyzerTestSupport() {
    }

    static String readWorkspaceFile(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    static void assertContainsSource(List<DataObjectRef> inputObjects, String qualifiedName) {
        StringBuilder actualSources = new StringBuilder();
        for (DataObjectRef sourceObject : inputObjects) {
            if (actualSources.length() > 0) {
                actualSources.append(", ");
            }
            actualSources.append(sourceObject.getQualifiedName());
            if (qualifiedName.equals(sourceObject.getQualifiedName())) {
                return;
            }
        }
        throw new AssertionError("Expected source object " + qualifiedName + ", actual: [" + actualSources + "]");
    }

    static void assertContainsSource(StatementLineageResult statement, String qualifiedName) {
        assertContainsSource(statement.getInputObjects(), qualifiedName);
    }

    static void assertNotContainsSource(StatementLineageResult statement, String qualifiedName) {
        for (DataObjectRef sourceObject : statement.getInputObjects()) {
            if (qualifiedName.equals(sourceObject.getQualifiedName())) {
                throw new AssertionError("Did not expect source object " + qualifiedName);
            }
        }
    }

    static void assertContainsColumn(List<ColumnRef> columns, String qualifiedName) {
        StringBuilder actualColumns = new StringBuilder();
        for (ColumnRef column : columns) {
            if (actualColumns.length() > 0) {
                actualColumns.append(", ");
            }
            actualColumns.append(column.getQualifiedName());
            if (qualifiedName.equals(column.getQualifiedName())) {
                return;
            }
        }
        throw new AssertionError("Expected source column " + qualifiedName + ", actual: [" + actualColumns + "]");
    }

    static boolean containsColumn(List<ColumnRef> columns, String qualifiedName) {
        for (ColumnRef column : columns) {
            if (qualifiedName.equals(column.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    static boolean containsSource(List<DataObjectRef> inputObjects, String qualifiedName) {
        for (DataObjectRef sourceObject : inputObjects) {
            if (qualifiedName.equals(sourceObject.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    static JoinCondition findJoinCondition(StatementLineageResult statement, String expression) {
        for (JoinCondition joinCondition : statement.getJoinConditions()) {
            if (expression.equals(joinCondition.getJoinExpression())) {
                return joinCondition;
            }
        }
        throw new AssertionError("Expected join expression " + expression);
    }

    static void assertContainsWarning(List<LineageWarning> warnings, WarningCode warningCode) {
        StringBuilder actualWarnings = new StringBuilder();
        for (LineageWarning warning : warnings) {
            if (actualWarnings.length() > 0) {
                actualWarnings.append(", ");
            }
            actualWarnings.append(warning.getWarningCode());
            if (warningCode == warning.getWarningCode()) {
                return;
            }
        }
        throw new AssertionError("Expected warning " + warningCode + ", actual: [" + actualWarnings + "]");
    }

    static final class CountingMetadataProvider implements HiveMetadataProvider {
        private final HiveTableMetadata metadata;
        private final AtomicInteger loadCount = new AtomicInteger();

        CountingMetadataProvider(HiveTableMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public HiveTableMetadata getTableMetadata(TableIdentifier tableIdentifier) {
            loadCount.incrementAndGet();
            return metadata;
        }

        int getLoadCount() {
            return loadCount.get();
        }
    }

    static final class ExpiringMetadataProvider implements HiveMetadataProvider {
        private final HiveTableMetadata first;
        private final HiveTableMetadata second;
        private final AtomicInteger loadCount = new AtomicInteger();

        ExpiringMetadataProvider(HiveTableMetadata first, HiveTableMetadata second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public HiveTableMetadata getTableMetadata(TableIdentifier tableIdentifier) {
            return loadCount.incrementAndGet() == 1 ? first : second;
        }

        int getLoadCount() {
            return loadCount.get();
        }
    }

    static final class MapMetadataProvider implements HiveMetadataProvider {
        private final Map<String, HiveTableMetadata> metadataByQualifiedName = new HashMap<>();
        private final AtomicInteger loadCount = new AtomicInteger();

        MapMetadataProvider addTable(String qualifiedName, String... columns) {
            String[] parts = qualifiedName.split("\\.");
            metadataByQualifiedName.put(
                qualifiedName,
                HiveTableMetadata.table(
                    TableIdentifier.of("default", parts[0], parts[1]),
                    Arrays.asList(columns)
                )
            );
            return this;
        }

        MapMetadataProvider addView(String qualifiedName, String viewOriginalSql, String... columns) {
            String[] parts = qualifiedName.split("\\.");
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
    }
}
