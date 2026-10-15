package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.TypeRef;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable operator identifiers are shared by syntax binding and type inference.
 */
final class OperatorTypeRules {
    private static final Map<String, TypeRef.Kind> FIXED;

    static {
        Map<String, TypeRef.Kind> rules = new LinkedHashMap<>();
        for (String name : new String[]{"and", "or", "not", "equal", "null_safe_equal", "not_equal",
                "less_than", "less_or_equal", "greater_than", "greater_or_equal", "in", "not_in",
                "between", "not_between", "like", "rlike", "is_null", "is_not_null",
                "merge_matched", "merge_not_matched", "merge_not_matched_by_source"}) {
            rules.put(name, TypeRef.Kind.BOOLEAN);
        }
        FIXED = Collections.unmodifiableMap(rules);
    }

    private OperatorTypeRules() {
    }

    static TypeRef infer(String name, List<TypeRef> arguments) {
        TypeRef.Kind fixed = FIXED.get(name);
        if (fixed != null) {
            return SemanticTypes.type(fixed);
        }
        switch (name) {
            case "add":
            case "subtract":
            case "multiply":
            case "modulo":
            case "plus":
            case "minus":
            case "bitwise_and":
            case "bitwise_or":
            case "bitwise_xor":
            case "bitwise_not":
                return SemanticTypes.common(arguments);
            case "divide":
                TypeRef common = SemanticTypes.common(arguments);
                return common.getKind() == TypeRef.Kind.INTEGER || common.getKind() == TypeRef.Kind.DECIMAL
                        ? SemanticTypes.type(TypeRef.Kind.DECIMAL) : TypeRef.unknown();
            default:
                return TypeRef.unknown();
        }
    }
}
