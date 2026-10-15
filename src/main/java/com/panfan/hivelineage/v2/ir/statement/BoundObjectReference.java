package com.panfan.hivelineage.v2.ir.statement;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bound object declaration used for display and export without consulting a Provider.
 * Object identity is opaque and must never be parsed to recover the qualified name.
 */
public final class BoundObjectReference {
    public enum ObjectKind {TABLE, VIEW, TEMPORARY, EXTERNAL, UNKNOWN}

    private final ObjectId id;
    private final QualifiedName name;
    private final ObjectKind kind;
    private final SchemaState schemaState;
    private final List<FieldIdentity> fields;
    private final String dataSourceId;
    private final DefinitionId definitionId;

    public BoundObjectReference(ObjectId id, QualifiedName name, ObjectKind kind) {
        this(id, name, kind, SchemaState.NOT_REQUESTED, Collections.<FieldIdentity>emptyList());
    }

    public BoundObjectReference(ObjectId id, QualifiedName name, ObjectKind kind, SchemaState schemaState, List<FieldIdentity> fields) {
        this(id, name, kind, schemaState, fields, null, null);
    }

    public BoundObjectReference(ObjectId id, QualifiedName name, ObjectKind kind, SchemaState schemaState, List<FieldIdentity> fields, String dataSourceId, DefinitionId definitionId) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.schemaState = Objects.requireNonNull(schemaState, "schemaState");
        this.fields = ModelCollections.immutableList(fields);
        if (schemaState == SchemaState.NOT_REQUESTED && !this.fields.isEmpty()) {
            throw new IllegalArgumentException("unrequested object schema must have no declared fields");
        }
        this.dataSourceId = dataSourceId;
        this.definitionId = definitionId;
    }

    public ObjectId getId() {
        return id;
    }

    public QualifiedName getName() {
        return name;
    }

    public ObjectKind getKind() {
        return kind;
    }

    public SchemaState getSchemaState() {
        return schemaState;
    }

    public List<FieldIdentity> getFields() {
        return fields;
    }

    public Optional<String> getDataSourceId() {
        return Optional.ofNullable(dataSourceId);
    }

    public Optional<DefinitionId> getDefinitionId() {
        return Optional.ofNullable(definitionId);
    }
}
