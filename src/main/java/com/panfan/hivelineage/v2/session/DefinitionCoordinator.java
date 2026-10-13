package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.metadata.LookupResult;

/** Orchestration protocol only; binding and lineage never invoke a Parser through IR. */
public interface DefinitionCoordinator<T extends DefinitionTemplate> {
    LookupResult<T> prepare(DefinitionRequest request);
}
