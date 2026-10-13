package com.panfan.hivelineage.v2.ir.statement;

import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import java.util.Objects;
import java.util.Optional;

/** Immutable declared scope ancestry, independent of mutable Binder scope state. */
public final class BoundScopeReference {
    private final ScopeId id;
    private final QueryBlockId queryBlockId;
    private final Optional<ScopeId> parent;

    public BoundScopeReference(ScopeId id, QueryBlockId queryBlockId, Optional<ScopeId> parent) {
        this.id = Objects.requireNonNull(id, "id");
        this.queryBlockId = Objects.requireNonNull(queryBlockId, "queryBlockId");
        this.parent = Objects.requireNonNull(parent, "parent");
    }

    public ScopeId getId() {
        return id;
    }

    public QueryBlockId getQueryBlockId() {
        return queryBlockId;
    }

    public Optional<ScopeId> getParent() {
        return parent;
    }
}
