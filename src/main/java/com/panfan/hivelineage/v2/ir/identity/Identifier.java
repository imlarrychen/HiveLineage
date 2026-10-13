package com.panfan.hivelineage.v2.ir.identity;

import com.panfan.hivelineage.v2.common.ModelChecks;

/** Type-sensitive immutable identity. Identity allocation is a future binder responsibility. */
public abstract class Identifier {
    private final String value;

    protected Identifier(String value) {
        this.value = ModelChecks.text(value, "identifier");
    }

    public final String getValue() {
        return value;
    }

    @Override
    public final boolean equals(Object other) {
        return other != null && getClass().equals(other.getClass())
            && value.equals(((Identifier) other).value);
    }

    @Override
    public final int hashCode() {
        return 31 * getClass().hashCode() + value.hashCode();
    }

    @Override
    public final String toString() {
        return value;
    }
}
