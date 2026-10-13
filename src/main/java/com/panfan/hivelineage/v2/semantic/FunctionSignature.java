package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.TypeRef;
import java.util.List;

public final class FunctionSignature {
    private final List<TypeRef> arguments;
    private final boolean variadic;
    private final List<TypeRef> resultTypes;

    public FunctionSignature(List<TypeRef> arguments, boolean variadic, List<TypeRef> resultTypes) {
        this.arguments = ModelCollections.immutableList(arguments);
        this.variadic = variadic;
        this.resultTypes = ModelCollections.immutableList(resultTypes);
    }

    public List<TypeRef> getArguments() {
        return arguments;
    }

    public boolean isVariadic() {
        return variadic;
    }

    public List<TypeRef> getResultTypes() {
        return resultTypes;
    }
}
