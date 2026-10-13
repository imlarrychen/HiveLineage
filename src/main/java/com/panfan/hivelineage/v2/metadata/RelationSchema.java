package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import java.util.List;

/** Ordered schema data; UNKNOWN and NOT_REQUESTED differ from a known empty schema. */
public final class RelationSchema {
    private final SchemaState state;
    private final List<ColumnMetadata> columns;
    private final MetadataProvenance provenance;
    private final MetadataVersion version;

    public RelationSchema(SchemaState state, List<ColumnMetadata> columns,
                          MetadataProvenance provenance, MetadataVersion version) {
        this.state = ModelChecks.value(state, "state");
        this.columns = ModelCollections.immutableList(columns);
        this.provenance = ModelChecks.value(provenance, "provenance");
        this.version = ModelChecks.value(version, "version");
        if (state == SchemaState.NOT_REQUESTED && !columns.isEmpty()) {
            throw new IllegalArgumentException("unrequested schema cannot contain columns");
        }
    }

    public SchemaState getState() {
        return state;
    }

    public List<ColumnMetadata> getColumns() {
        return columns;
    }

    public MetadataProvenance getProvenance() {
        return provenance;
    }

    public MetadataVersion getVersion() {
        return version;
    }
}

