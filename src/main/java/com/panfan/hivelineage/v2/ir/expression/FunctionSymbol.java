package com.panfan.hivelineage.v2.ir.expression;

import com.panfan.hivelineage.v2.common.QualifiedName;

public interface FunctionSymbol {
    QualifiedName getName();
    FunctionKind getKind();

    enum FunctionKind { SCALAR, AGGREGATE, WINDOW, GENERATOR, OPERATOR, OPAQUE }
}
