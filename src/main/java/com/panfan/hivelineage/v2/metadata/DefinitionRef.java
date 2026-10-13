package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import java.util.Optional;

/** Provider/source definition data; text is prepared by orchestration, never by lineage. */
public final class DefinitionRef {
    private final DefinitionId definitionId;
    private final Optional<String> definitionText;
    private final DefinitionContext definitionContext;
    private final MetadataProvenance provenance;
    private final MetadataVersion version;
    private final SourceRef source;

    public DefinitionRef(DefinitionId definitionId, Optional<String> definitionText,
                         DefinitionContext definitionContext, MetadataProvenance provenance,
                         MetadataVersion version, SourceRef source) {
        this.definitionId = ModelChecks.value(definitionId, "definitionId");
        this.definitionText = ModelChecks.value(definitionText, "definitionText");
        this.definitionContext = ModelChecks.value(definitionContext, "definitionContext");
        this.provenance = ModelChecks.value(provenance, "provenance");
        this.version = ModelChecks.value(version, "version");
        this.source = ModelChecks.value(source, "source");
    }

    public DefinitionId getDefinitionId() {
        return definitionId;
    }

    public Optional<String> getDefinitionText() {
        return definitionText;
    }

    public DefinitionContext getDefinitionContext() {
        return definitionContext;
    }

    public MetadataProvenance getProvenance() {
        return provenance;
    }

    public MetadataVersion getVersion() {
        return version;
    }

    public SourceRef getSource() {
        return source;
    }
}

