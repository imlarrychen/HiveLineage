package com.panfan.hivelineage.v2.ir.expression;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.Optional;

/** Bound structure; source attachments are optional and never semantic input. */
public interface BoundExpression {
    NodeId getId();
    TypeRef getType();
    Optional<SourceRef> getSourceRef();
}
