package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ModelChecks;

/** Bundled provider payload; accepting it does not imply all attributes are known. */
public final class RelationMetadata {
    private final ObjectDescriptor object;
    private final RelationSchema schema;

    public RelationMetadata(ObjectDescriptor object, RelationSchema schema) {
        this.object = ModelChecks.value(object, "object");
        this.schema = ModelChecks.value(schema, "schema");
    }

    public ObjectDescriptor getObject() {
        return object;
    }

    public RelationSchema getSchema() {
        return schema;
    }
}

