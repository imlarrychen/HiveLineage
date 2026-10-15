package com.panfan.hivelineage.v2.ir.statement;

import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;

import java.util.Objects;

public final class QueryResult {
    public enum Role {MAIN, CTE, SUBQUERY, DEFINITION}

    private final NodeId relationRoot;
    private final QueryBlockId queryBlockId;
    private final OutputSchema outputSchema;
    private final Role role;

    public QueryResult(NodeId relationRoot, QueryBlockId queryBlockId, OutputSchema outputSchema, Role role) {
        this.relationRoot = Objects.requireNonNull(relationRoot, "relationRoot");
        this.queryBlockId = Objects.requireNonNull(queryBlockId, "queryBlockId");
        this.outputSchema = Objects.requireNonNull(outputSchema, "outputSchema");
        this.role = Objects.requireNonNull(role, "role");
    }

    public NodeId getRelationRoot() {
        return relationRoot;
    }

    public QueryBlockId getQueryBlockId() {
        return queryBlockId;
    }

    public OutputSchema getOutputSchema() {
        return outputSchema;
    }

    public Role getRole() {
        return role;
    }
}
