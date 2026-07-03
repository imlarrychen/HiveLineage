package com.panfan.hivelineage.metadata;

public interface HiveMetadataProvider {
    HiveTableMetadata getTableMetadata(TableIdentifier tableIdentifier);
}
