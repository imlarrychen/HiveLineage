package com.panfan.hivelineage.v2.ir.statement;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import java.util.List;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

/** Typed proposals only; no mutation, parser callbacks or arbitrary semantic payload. */
public final class Effects {
    private Effects() {
    }

    public enum EffectKind { SET_DATABASE, SET_VARIABLE, SET_OPTION, CREATE_OBJECT, DROP_OBJECT, ALTER_OBJECT, RENAME_OBJECT, REGISTER_DEFINITION, UNKNOWN }
    public enum Certainty { CERTAIN, CONDITIONAL, UNKNOWN }
    public enum ObjectKind { TABLE, TEMPORARY_TABLE, VIEW, LOGICAL_DEFINITION }
    public enum Namespace { HIVEVAR, HIVECONF, CONFIGURATION, SYSTEM_PROPERTY, ENVIRONMENT }

    public static final class Precondition {
        public enum Kind { ALWAYS, OBJECT_PRESENT, OBJECT_ABSENT, UNKNOWN }
        private final Kind kind;
        private final ObjectId object;

        public Precondition(Kind kind, ObjectId object) {
            this.kind = Objects.requireNonNull(kind, "kind");
            boolean objectCondition = kind == Kind.OBJECT_PRESENT || kind == Kind.OBJECT_ABSENT;
            if (objectCondition != (object != null)) {
                throw new IllegalArgumentException("object preconditions require an object identity");
            }
            this.object = object;
        }

        public Kind getKind() {
            return kind;
        }

        public Optional<ObjectId> getObject() {
            return Optional.ofNullable(object);
        }
    }

    public static final class SessionKey {
        private final Namespace namespace;
        private final String key;

        public SessionKey(Namespace namespace, String key) {
            this.namespace = Objects.requireNonNull(namespace, "namespace");
            this.key = Objects.requireNonNull(key, "key");
        }

        public Namespace getNamespace() {
            return namespace;
        }

        public String getKey() {
            return key;
        }
    }

    public static final class ObjectShape {
        private final ObjectId objectId;
        private final QualifiedName name;
        private final ObjectKind kind;
        private final OutputSchema schema;
        private final VersionRef version;
        private final List<QualifiedName> lookupNames;
        private final List<String> partitionColumns;

        public ObjectShape(ObjectId objectId, QualifiedName name, ObjectKind kind, OutputSchema schema, VersionRef version) {
            this(objectId, name, kind, schema, version, Collections.singletonList(name));
        }

        public ObjectShape(ObjectId objectId, QualifiedName name, ObjectKind kind, OutputSchema schema, VersionRef version, List<QualifiedName> lookupNames) {
            this(objectId, name, kind, schema, version, lookupNames, Collections.<String>emptyList());
        }

        public ObjectShape(ObjectId objectId, QualifiedName name, ObjectKind kind, OutputSchema schema, VersionRef version,
                           List<QualifiedName> lookupNames, List<String> partitionColumns) {
            this.objectId = Objects.requireNonNull(objectId, "objectId");
            this.name = Objects.requireNonNull(name, "name");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.schema = Objects.requireNonNull(schema, "schema");
            this.version = Objects.requireNonNull(version, "version");
            this.lookupNames = ModelCollections.immutableList(lookupNames);
            this.partitionColumns = ModelCollections.immutableList(partitionColumns);
            if (!this.lookupNames.contains(name)) {
                throw new IllegalArgumentException("object lookup names must include its declared name");
            }
        }

        public ObjectId getObjectId() {
            return objectId;
        }

        public QualifiedName getName() {
            return name;
        }

        public ObjectKind getKind() {
            return kind;
        }

        public OutputSchema getSchema() {
            return schema;
        }

        public VersionRef getVersion() {
            return version;
        }

        public List<QualifiedName> getLookupNames() {
            return lookupNames;
        }

        public List<String> getPartitionColumns() {
            return partitionColumns;
        }
    }

    public abstract static class Base implements BoundEffect {
        private final NodeId id;
        private final EffectKind kind;
        private final Certainty certainty;
        private final Precondition precondition;

        protected Base(NodeId id, EffectKind kind, Certainty certainty, Precondition precondition) {
            this.id = Objects.requireNonNull(id, "id");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.certainty = Objects.requireNonNull(certainty, "certainty");
            this.precondition = Objects.requireNonNull(precondition, "precondition");
        }

        public NodeId getId() {
            return id;
        }

        public EffectKind getKind() {
            return kind;
        }

        public Certainty getCertainty() {
            return certainty;
        }

        public Precondition getPrecondition() {
            return precondition;
        }
    }

    public static final class SetDatabase extends Base {
        private final QualifiedName database;

        public SetDatabase(NodeId id, Certainty certainty, Precondition precondition, QualifiedName database) {
            super(id, EffectKind.SET_DATABASE, certainty, precondition);
            this.database = Objects.requireNonNull(database, "database");
        }

        public QualifiedName getDatabase() {
            return database;
        }
    }

    public static final class SetVariable extends Base {
        private final SessionKey key;
        private final String value;

        public SetVariable(NodeId id, Certainty certainty, Precondition precondition, SessionKey key, String value) {
            super(id, EffectKind.SET_VARIABLE, certainty, precondition);
            this.key = Objects.requireNonNull(key, "key");
            this.value = Objects.requireNonNull(value, "value");
        }

        public SessionKey getKey() {
            return key;
        }

        public String getValue() {
            return value;
        }
    }

    public static final class SetOption extends Base {
        private final SessionKey key;
        private final String value;

        public SetOption(NodeId id, Certainty certainty, Precondition precondition, SessionKey key, String value) {
            super(id, EffectKind.SET_OPTION, certainty, precondition);
            this.key = Objects.requireNonNull(key, "key");
            this.value = Objects.requireNonNull(value, "value");
        }

        public SessionKey getKey() {
            return key;
        }

        public String getValue() {
            return value;
        }
    }

    public static final class CreateObject extends Base {
        private final ObjectShape shape;

        public CreateObject(NodeId id, Certainty certainty, Precondition precondition, ObjectShape shape) {
            super(id, EffectKind.CREATE_OBJECT, certainty, precondition);
            this.shape = Objects.requireNonNull(shape, "shape");
        }

        public ObjectShape getShape() {
            return shape;
        }
    }

    public static final class DropObject extends Base {
        private final ObjectId objectId;
        private final ObjectKind objectKind;
        private final QualifiedName name;

        public DropObject(NodeId id, Certainty certainty, Precondition precondition, ObjectId objectId, ObjectKind objectKind) {
            this(id, certainty, precondition, objectId, objectKind, null);
        }

        public DropObject(NodeId id, Certainty certainty, Precondition precondition, ObjectId objectId, ObjectKind objectKind, QualifiedName name) {
            super(id, EffectKind.DROP_OBJECT, certainty, precondition);
            this.objectId = Objects.requireNonNull(objectId, "objectId");
            this.objectKind = Objects.requireNonNull(objectKind, "objectKind");
            this.name = name;
        }

        public Optional<QualifiedName> getName() {
            return Optional.ofNullable(name);
        }

        public ObjectId getObjectId() {
            return objectId;
        }

        public ObjectKind getObjectKind() {
            return objectKind;
        }
    }

    public static final class AlterObject extends Base {
        private final ObjectShape replacement;

        public AlterObject(NodeId id, Certainty certainty, Precondition precondition, ObjectShape replacement) {
            super(id, EffectKind.ALTER_OBJECT, certainty, precondition);
            this.replacement = Objects.requireNonNull(replacement, "replacement");
        }

        public ObjectShape getReplacement() {
            return replacement;
        }
    }

    public static final class RenameObject extends Base {
        private final ObjectId objectId;
        private final QualifiedName newName;
        private final QualifiedName previousName;

        public RenameObject(NodeId id, Certainty certainty, Precondition precondition, ObjectId objectId, QualifiedName newName) {
            this(id, certainty, precondition, objectId, newName, null);
        }

        public RenameObject(NodeId id, Certainty certainty, Precondition precondition, ObjectId objectId, QualifiedName newName, QualifiedName previousName) {
            super(id, EffectKind.RENAME_OBJECT, certainty, precondition);
            this.objectId = Objects.requireNonNull(objectId, "objectId");
            this.newName = Objects.requireNonNull(newName, "newName");
            this.previousName = previousName;
        }

        public Optional<QualifiedName> getPreviousName() {
            return Optional.ofNullable(previousName);
        }

        public ObjectId getObjectId() {
            return objectId;
        }

        public QualifiedName getNewName() {
            return newName;
        }
    }

    public static final class RegisterDefinition extends Base {
        private final DefinitionId definitionId;
        private final QualifiedName name;
        private final OutputSchema schema;
        private final NodeId relationRoot;
        private final ObjectId physicalObject;

        public RegisterDefinition(NodeId id, Certainty certainty, Precondition precondition, DefinitionId definitionId, QualifiedName name, OutputSchema schema, NodeId relationRoot, ObjectId physicalObject) {
            super(id, EffectKind.REGISTER_DEFINITION, certainty, precondition);
            this.definitionId = Objects.requireNonNull(definitionId, "definitionId");
            this.name = Objects.requireNonNull(name, "name");
            this.schema = Objects.requireNonNull(schema, "schema");
            this.relationRoot = Objects.requireNonNull(relationRoot, "relationRoot");
            this.physicalObject = physicalObject;
        }

        public DefinitionId getDefinitionId() {
            return definitionId;
        }

        public QualifiedName getName() {
            return name;
        }

        public OutputSchema getSchema() {
            return schema;
        }

        public NodeId getRelationRoot() {
            return relationRoot;
        }

        public Optional<ObjectId> getPhysicalObject() {
            return Optional.ofNullable(physicalObject);
        }
    }

    public static final class UnknownEffect extends Base {
        private final List<ObjectId> affectedObjects;
        private final List<SessionKey> affectedKeys;
        private final boolean catalogMayChange;
        private final String diagnosticCode;

        public UnknownEffect(NodeId id, Certainty certainty, Precondition precondition, List<ObjectId> affectedObjects, List<SessionKey> affectedKeys, boolean catalogMayChange, String diagnosticCode) {
            super(id, EffectKind.UNKNOWN, certainty, precondition);
            this.affectedObjects = ModelCollections.immutableList(affectedObjects);
            this.affectedKeys = ModelCollections.immutableList(affectedKeys);
            this.catalogMayChange = catalogMayChange;
            this.diagnosticCode = Objects.requireNonNull(diagnosticCode, "diagnosticCode");
        }

        public List<ObjectId> getAffectedObjects() {
            return affectedObjects;
        }

        public List<SessionKey> getAffectedKeys() {
            return affectedKeys;
        }

        public boolean getCatalogMayChange() {
            return catalogMayChange;
        }

        public String getDiagnosticCode() {
            return diagnosticCode;
        }
    }
}
