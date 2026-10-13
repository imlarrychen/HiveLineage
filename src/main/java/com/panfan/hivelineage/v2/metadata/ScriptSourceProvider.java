package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.api.SourceDocument;

/** Read-only source retrieval. Returned text is prepared by the shared frontend. */
public interface ScriptSourceProvider {
    LookupResult<SourceDocument> read(DefinitionRef reference);

    /** Whether resolving this reference invokes an external source service. */
    default boolean requiresExternalRead(DefinitionRef reference) {
        return true;
    }
}
