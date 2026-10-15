package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;

import java.util.List;

final class SourceBinding {
    final NodeId relationId;
    final RelationInstanceId instanceId;
    final ObjectId objectId;
    final QualifiedName name;
    final List<String> aliases;
    final boolean physical;
    final SourceRef source;
    OutputSchema schema;
    boolean schemaAttempted;
    boolean uncertain;
    boolean generated;
    String explicitAlias;

    SourceBinding(NodeId relationId, RelationInstanceId instanceId, ObjectId objectId,
                  QualifiedName name, List<String> aliases, boolean physical,
                  SourceRef source, OutputSchema schema, boolean schemaAttempted) {
        this.relationId = relationId;
        this.instanceId = instanceId;
        this.objectId = objectId;
        this.name = name;
        this.aliases = aliases;
        this.physical = physical;
        this.source = source;
        this.schema = schema;
        this.schemaAttempted = schemaAttempted;
    }
}
