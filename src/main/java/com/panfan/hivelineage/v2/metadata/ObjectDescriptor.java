package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import java.util.Optional;

/** The light object facet does not require an ordered column list. */
public final class ObjectDescriptor {
    public enum Kind {
        TABLE,
        VIEW,
        TEMPORARY_TABLE,
        EXTERNAL,
        UNKNOWN
    }

    private final ObjectId objectId;
    private final QualifiedName name;
    private final Kind kind;
    private final Optional<DefinitionRef> definition;
    private final MetadataProvenance provenance;
    private final MetadataVersion version;

    public ObjectDescriptor(ObjectId objectId, QualifiedName name, Kind kind,
                            Optional<DefinitionRef> definition, MetadataProvenance provenance,
                            MetadataVersion version) {
        this.objectId = ModelChecks.value(objectId, "objectId");
        this.name = ModelChecks.value(name, "name");
        this.kind = ModelChecks.value(kind, "kind");
        this.definition = ModelChecks.value(definition, "definition");
        this.provenance = ModelChecks.value(provenance, "provenance");
        this.version = ModelChecks.value(version, "version");
    }

    public ObjectId getObjectId() {
        return objectId;
    }

    public QualifiedName getName() {
        return name;
    }

    public Kind getKind() {
        return kind;
    }

    public Optional<DefinitionRef> getDefinition() {
        return definition;
    }

    public MetadataProvenance getProvenance() {
        return provenance;
    }

    public MetadataVersion getVersion() {
        return version;
    }
}

