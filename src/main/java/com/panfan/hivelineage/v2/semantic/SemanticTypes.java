package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.syntax.DataTypeSyntax;
import com.panfan.hivelineage.v2.syntax.TypeFieldSyntax;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SemanticTypes {
    private SemanticTypes() {
    }

    static TypeRef type(TypeRef.Kind kind) {
        return new TypeRef(kind, kind.name().toLowerCase(java.util.Locale.ROOT),
            Collections.<TypeRef>emptyList(), Collections.<TypeRef.StructField>emptyList());
    }

    static TypeRef declared(DataTypeSyntax syntax) {
        if (syntax == null) {
            return TypeRef.unknown();
        }
        String name = SemanticNames.last(syntax.getTypeName());
        TypeRef.Kind kind;
        if ("tinyint".equals(name) || "smallint".equals(name) || "int".equals(name) || "bigint".equals(name)) {
            kind = TypeRef.Kind.INTEGER;
        } else if ("float".equals(name) || "double".equals(name) || "decimal".equals(name)) {
            kind = TypeRef.Kind.DECIMAL;
        } else if ("varchar".equals(name) || "char".equals(name) || "string".equals(name)) {
            kind = TypeRef.Kind.STRING;
        } else if ("uniontype".equals(name)) {
            kind = TypeRef.Kind.UNION;
        } else {
            try {
                kind = TypeRef.Kind.valueOf(name.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                kind = TypeRef.Kind.UNKNOWN;
            }
        }
        List<TypeRef> arguments = new ArrayList<>();
        for (DataTypeSyntax argument : syntax.getTypeArguments()) {
            arguments.add(declared(argument));
        }
        List<TypeRef.StructField> fields = new ArrayList<>();
        for (TypeFieldSyntax field : syntax.getFields()) {
            fields.add(new TypeRef.StructField(field.getName().getValue(), declared(field.getType())));
        }
        return new TypeRef(kind, name, arguments, fields);
    }

    static TypeRef common(List<TypeRef> types) {
        TypeRef result = type(TypeRef.Kind.NULL);
        for (TypeRef type : types) {
            if (type.getKind() == TypeRef.Kind.NULL) {
                continue;
            }
            if (result.getKind() == TypeRef.Kind.NULL) {
                result = type;
            } else if (type.getKind() == TypeRef.Kind.UNKNOWN || result.getKind() == TypeRef.Kind.UNKNOWN) {
                return TypeRef.unknown();
            } else if (type.getKind() != result.getKind()) {
                if ((type.getKind() == TypeRef.Kind.INTEGER && result.getKind() == TypeRef.Kind.DECIMAL)
                    || (type.getKind() == TypeRef.Kind.DECIMAL && result.getKind() == TypeRef.Kind.INTEGER)) {
                    result = type(TypeRef.Kind.DECIMAL);
                } else {
                    return TypeRef.unknown();
                }
            }
        }
        return result;
    }
}

