package com.panfan.hivelineage.v2.ir.relation;

import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import java.util.Objects;
import java.util.Optional;

/** Ordered output identity; displayName is not an identity key. */
public final class OutputSlot {
    private final SlotId id;
    private final String displayName;
    private final TypeRef type;
    private final FieldIdentity fieldIdentity;

    public OutputSlot(SlotId id, String displayName, TypeRef type, FieldIdentity fieldIdentity) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.type = Objects.requireNonNull(type, "type");
        this.fieldIdentity = fieldIdentity;
    }

    public SlotId getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public TypeRef getType() {
        return type;
    }

    public Optional<FieldIdentity> getFieldIdentity() {
        return Optional.ofNullable(fieldIdentity);
    }
}
