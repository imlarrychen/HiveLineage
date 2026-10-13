package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import java.util.List;
import java.util.Optional;

public final class Scope {
    private final ScopeId id;
    private final Optional<ScopeId> parent;
    private final QueryBlockId queryBlock;
    private final List<ScopeEntry> entries;

    public Scope(ScopeId id, Optional<ScopeId> parent, QueryBlockId queryBlock, List<ScopeEntry> entries) {
        this.id = ModelChecks.value(id, "id");
        this.parent = ModelChecks.value(parent, "parent");
        this.queryBlock = ModelChecks.value(queryBlock, "queryBlock");
        this.entries = ModelCollections.immutableList(entries);
    }

    public ScopeId getId() {
        return id;
    }

    public Optional<ScopeId> getParent() {
        return parent;
    }

    public QueryBlockId getQueryBlock() {
        return queryBlock;
    }

    public List<ScopeEntry> getEntries() {
        return entries;
    }
}
