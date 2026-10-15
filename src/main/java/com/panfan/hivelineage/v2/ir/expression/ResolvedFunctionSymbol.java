package com.panfan.hivelineage.v2.ir.expression;

import com.panfan.hivelineage.v2.common.QualifiedName;

import java.util.Objects;

/**
 * Identifies a declared rule; does not load or execute a function class.
 */
public final class ResolvedFunctionSymbol implements FunctionSymbol {
    private final QualifiedName name;
    private final FunctionKind kind;
    private final String ruleKey;

    public ResolvedFunctionSymbol(QualifiedName name, FunctionKind kind, String ruleKey) {
        this.name = Objects.requireNonNull(name, "name");
        this.kind = Objects.requireNonNull(kind, "kind");
        if (kind == FunctionKind.OPAQUE) {
            throw new IllegalArgumentException("opaque functions use OpaqueFunctionSymbol");
        }
        this.ruleKey = Objects.requireNonNull(ruleKey, "ruleKey");
    }

    public QualifiedName getName() {
        return name;
    }

    public FunctionKind getKind() {
        return kind;
    }

    public String getRuleKey() {
        return ruleKey;
    }
}
