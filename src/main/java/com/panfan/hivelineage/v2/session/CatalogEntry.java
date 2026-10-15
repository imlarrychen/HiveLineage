package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.metadata.ObjectDescriptor;
import com.panfan.hivelineage.v2.metadata.RelationSchema;

import java.util.List;
import java.util.Optional;

/**
 * Local object and declared lookup names; name resolution is performed elsewhere.
 */
public final class CatalogEntry {
    private final ObjectDescriptor object;
    private final List<QualifiedName> lookupNames;
    private final Optional<RelationSchema> schema;
    private final Optional<LogicalDefinition> definition;

    public CatalogEntry(ObjectDescriptor object, List<QualifiedName> lookupNames,
                        Optional<RelationSchema> schema, Optional<LogicalDefinition> definition) {
        this.object = ModelChecks.value(object, "object");
        this.lookupNames = ModelCollections.immutableList(lookupNames);
        this.schema = ModelChecks.value(schema, "schema");
        this.definition = ModelChecks.value(definition, "definition");
        if (lookupNames.isEmpty()) {
            throw new IllegalArgumentException("catalog entry must declare a lookup name");
        }
    }

    public ObjectDescriptor getObject() {
        return object;
    }

    public List<QualifiedName> getLookupNames() {
        return lookupNames;
    }

    public Optional<RelationSchema> getSchema() {
        return schema;
    }

    public Optional<LogicalDefinition> getDefinition() {
        return definition;
    }
}

