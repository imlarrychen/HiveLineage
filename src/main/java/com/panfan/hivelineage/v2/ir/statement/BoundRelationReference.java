package com.panfan.hivelineage.v2.ir.statement;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;

import java.util.Objects;
import java.util.Optional;

/**
 * Display declaration for a bound occurrence; alias is never used to rebind fields.
 */
public final class BoundRelationReference {
    private final RelationInstanceId id;
    private final QualifiedName alias;
    private final Optional<ObjectId> objectId;
    private final Optional<DefinitionId> definitionId;
    private final QueryBlockId queryBlockId;

    public BoundRelationReference(RelationInstanceId id, QualifiedName alias, Optional<ObjectId> objectId, Optional<DefinitionId> definitionId, QueryBlockId queryBlockId) {
        this.id = Objects.requireNonNull(id, "id");
        this.alias = Objects.requireNonNull(alias, "alias");
        this.objectId = Objects.requireNonNull(objectId, "objectId");
        this.definitionId = Objects.requireNonNull(definitionId, "definitionId");
        this.queryBlockId = Objects.requireNonNull(queryBlockId, "queryBlockId");
    }

    public RelationInstanceId getId() {
        return id;
    }

    public QualifiedName getAlias() {
        return alias;
    }

    public Optional<ObjectId> getObjectId() {
        return objectId;
    }

    public Optional<DefinitionId> getDefinitionId() {
        return definitionId;
    }

    public QueryBlockId getQueryBlockId() {
        return queryBlockId;
    }
}
