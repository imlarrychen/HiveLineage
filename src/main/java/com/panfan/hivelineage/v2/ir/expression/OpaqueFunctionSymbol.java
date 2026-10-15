package com.panfan.hivelineage.v2.ir.expression;

import com.panfan.hivelineage.v2.common.QualifiedName;

import java.util.Objects;

/**
 * Parameters are known but function behavior and possible external sources are opaque.
 */
public final class OpaqueFunctionSymbol implements FunctionSymbol {
    private final QualifiedName name;
    private final String diagnosticCode;

    public OpaqueFunctionSymbol(QualifiedName name, String diagnosticCode) {
        this.name = Objects.requireNonNull(name, "name");
        this.diagnosticCode = Objects.requireNonNull(diagnosticCode, "diagnosticCode");
    }

    public QualifiedName getName() {
        return name;
    }

    public FunctionKind getKind() {
        return FunctionKind.OPAQUE;
    }

    public String getDiagnosticCode() {
        return diagnosticCode;
    }
}
