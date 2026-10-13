package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import java.util.Objects;

/** Explicit definition, grammar, configuration and source identity; never a task version key. */
public final class ViewTemplateKey {
    private final DefinitionId definition;
    private final String definitionDigest;
    private final String dialectFingerprint;
    private final String definitionContextFingerprint;
    private final MetadataReadContext metadataContext;

    public ViewTemplateKey(DefinitionId definition, String definitionDigest, String dialectFingerprint,
                           String definitionContextFingerprint, MetadataReadContext metadataContext) {
        this.definition = ModelChecks.value(definition, "definition");
        this.definitionDigest = ModelChecks.text(definitionDigest, "definitionDigest");
        this.dialectFingerprint = ModelChecks.text(dialectFingerprint, "dialectFingerprint");
        this.definitionContextFingerprint = ModelChecks.text(definitionContextFingerprint, "definitionContextFingerprint");
        this.metadataContext = ModelChecks.value(metadataContext, "metadataContext");
    }

    public DefinitionId getDefinition() {
        return definition;
    }

    public String getDefinitionDigest() {
        return definitionDigest;
    }

    public String getDialectFingerprint() {
        return dialectFingerprint;
    }

    public String getDefinitionContextFingerprint() {
        return definitionContextFingerprint;
    }

    public MetadataReadContext getMetadataContext() {
        return metadataContext;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ViewTemplateKey)) {
            return false;
        }
        ViewTemplateKey that = (ViewTemplateKey) other;
        return definition.equals(that.definition) && definitionDigest.equals(that.definitionDigest)
            && dialectFingerprint.equals(that.dialectFingerprint)
            && definitionContextFingerprint.equals(that.definitionContextFingerprint)
            && metadataContext.equals(that.metadataContext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(definition, definitionDigest, dialectFingerprint, definitionContextFingerprint, metadataContext);
    }
}
