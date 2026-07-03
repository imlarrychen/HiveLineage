package com.panfan.hivelineage;

public interface HiveMetadataProvider {
    HiveTableMetadata getTableMetadata(TableIdentifier tableIdentifier);
}
