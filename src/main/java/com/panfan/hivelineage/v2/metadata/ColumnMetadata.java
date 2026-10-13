package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.ir.TypeRef;

public final class ColumnMetadata {
    public enum Role {
        DATA,
        PARTITION,
        UNKNOWN
    }

    public enum Nullability {
        NULLABLE,
        NOT_NULL,
        UNKNOWN
    }

    private final String name;
    private final int position;
    private final TypeRef type;
    private final Role role;
    private final Nullability nullability;

    public ColumnMetadata(String name, int position, TypeRef type, Role role, Nullability nullability) {
        this.name = ModelChecks.text(name, "name");
        this.position = ModelChecks.nonNegative(position, "position");
        this.type = ModelChecks.value(type, "type");
        this.role = ModelChecks.value(role, "role");
        this.nullability = ModelChecks.value(nullability, "nullability");
    }

    public String getName() {
        return name;
    }

    public int getPosition() {
        return position;
    }

    public TypeRef getType() {
        return type;
    }

    public Role getRole() {
        return role;
    }

    public Nullability getNullability() {
        return nullability;
    }
}

