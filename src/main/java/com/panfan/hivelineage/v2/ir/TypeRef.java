package com.panfan.hivelineage.v2.ir;

import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable type description; UNKNOWN is distinct from a verified Hive type. */
public final class TypeRef {
    public enum Kind { UNKNOWN, NULL, BOOLEAN, INTEGER, DECIMAL, STRING, DATE, TIMESTAMP, BINARY, ARRAY, MAP, STRUCT, UNION }
    private final Kind kind;
    private final String displayName;
    private final List<TypeRef> arguments;
    private final List<StructField> fields;

    public TypeRef(Kind kind, String displayName, List<TypeRef> arguments, List<StructField> fields) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.arguments = ModelCollections.immutableList(arguments);
        this.fields = ModelCollections.immutableList(fields);
    }

    public static TypeRef unknown() {
        return new TypeRef(Kind.UNKNOWN, "unknown", Collections.<TypeRef>emptyList(), Collections.<StructField>emptyList());
    }

    public Kind getKind() {
        return kind;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<TypeRef> getArguments() {
        return arguments;
    }

    public List<StructField> getFields() {
        return fields;
    }

    public static final class StructField {
        private final String name;
        private final TypeRef type;

        public StructField(String name, TypeRef type) {
            this.name = Objects.requireNonNull(name, "name");
            this.type = Objects.requireNonNull(type, "type");
        }

        public String getName() {
            return name;
        }

        public TypeRef getType() {
            return type;
        }
    }
}
