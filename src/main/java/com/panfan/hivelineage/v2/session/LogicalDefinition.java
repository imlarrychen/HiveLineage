package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.metadata.DefinitionRef;

import java.util.Optional;

/**
 * Session definition boundary. A legacy CTE is not a physical temporary object.
 */
public final class LogicalDefinition {
    public enum Kind {
        VIEW,
        TEMPORARY_RELATION,
        LEGACY_CTE,
        EXTERNAL_SCRIPT
    }

    private final Kind kind;
    private final DefinitionRef reference;
    private final Optional<DefinitionTemplate> template;

    public LogicalDefinition(Kind kind, DefinitionRef reference, Optional<DefinitionTemplate> template) {
        this.kind = ModelChecks.value(kind, "kind");
        this.reference = ModelChecks.value(reference, "reference");
        this.template = ModelChecks.value(template, "template");
        if (template.isPresent() && !reference.getDefinitionId().equals(template.get().getDefinitionId())) {
            throw new IllegalArgumentException("template must identify the referenced definition");
        }
    }

    public Kind getKind() {
        return kind;
    }

    public DefinitionRef getReference() {
        return reference;
    }

    public Optional<DefinitionTemplate> getTemplate() {
        return template;
    }
}

