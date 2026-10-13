package com.panfan.hivelineage;

import com.panfan.hivelineage.metadata.HiveMetadataProvider;

/** Same-package bridge; the existing provider and SQL files remain the sole input fixtures. */
public final class GeneratedV2MetadataFixture {
    private GeneratedV2MetadataFixture() {
    }

    public static HiveMetadataProvider provider() {
        return GeneratedSqlTestSetTest.generatedMetadataProvider();
    }
}
