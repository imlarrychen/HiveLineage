package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;

/**
 * Structured immutable definition marker. Implementations carry syntax data only,
 * never Parser, Binder, Provider or executable lazy-resolution callbacks.
 */
public interface DefinitionTemplate {
    DefinitionId getDefinitionId();

    SourceRef getSource();
}

