package com.panfan.hivelineage.v2.ir;

import com.panfan.hivelineage.v2.ir.identity.FieldId;

import java.util.Objects;

/**
 * Binder-assigned identity within a bound owner; display names need not be unique.
 */
public final class FieldIdentity {
    private final FieldId id;
    private final String displayName;

    public FieldIdentity(String value, String displayName) {
        this(new FieldId(value), displayName);
    }

    public FieldIdentity(FieldId id, String displayName) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
    }

    public FieldId getId() {
        return id;
    }

    public String getValue() {
        return id.getValue();
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof FieldIdentity && id.equals(((FieldIdentity) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
