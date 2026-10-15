package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;
import java.util.Objects;

/**
 * Structural type declaration; no inferred or validated Hive type.
 */
public final class DataTypeSyntax {
    private final SyntaxName typeName;
    private final List<DataTypeSyntax> typeArguments;
    private final List<Integer> numericArguments;
    private final List<TypeFieldSyntax> fields;

    public DataTypeSyntax(
            SyntaxName typeName,
            List<DataTypeSyntax> typeArguments,
            List<Integer> numericArguments,
            List<TypeFieldSyntax> fields) {
        this.typeName = Objects.requireNonNull(typeName, "typeName");
        this.typeArguments = ModelCollections.immutableList(typeArguments);
        this.numericArguments = ModelCollections.immutableList(numericArguments);
        this.fields = ModelCollections.immutableList(fields);
    }

    public SyntaxName getTypeName() {
        return typeName;
    }

    public List<DataTypeSyntax> getTypeArguments() {
        return typeArguments;
    }

    public List<Integer> getNumericArguments() {
        return numericArguments;
    }

    public List<TypeFieldSyntax> getFields() {
        return fields;
    }
}
