package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.expression.ResolvedFunctionSymbol;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Immutable declared Hive built-ins; unknown functions remain opaque and are never executed. */
public final class DefaultFunctionCatalog implements FunctionCatalog {
    private final Map<String, FunctionDescriptor> descriptors;

    public DefaultFunctionCatalog() {
        this(Collections.<String, FunctionDescriptor>emptyMap());
    }

    public DefaultFunctionCatalog(Map<String, FunctionDescriptor> extensions) {
        Map<String, FunctionDescriptor> entries = new LinkedHashMap<>();
        register(entries, FunctionSymbol.FunctionKind.SCALAR,
            "abs", "ceil", "floor", "round", "upper", "lower", "trim", "ltrim", "rtrim",
            "substr", "substring", "concat", "concat_ws", "coalesce", "nvl", "nullif",
            "if", "regexp_replace", "regexp_extract", "split", "size", "length", "instr",
            "cast", "to_date", "date_format", "date_add", "date_sub", "datediff",
            "unix_timestamp", "from_unixtime", "current_date", "current_timestamp",
            "get_json_object", "parse_url", "array", "map", "struct", "named_struct",
            "element_at", "greatest", "least", "isnull", "isnotnull", "hash", "pmod",
            "md5", "sha1", "sha2", "reverse", "replace", "format_number", "add_months",
            "year", "month", "day", "dayofmonth", "hour", "minute", "second", "to_unix_timestamp");
        register(entries, FunctionSymbol.FunctionKind.AGGREGATE,
            "sum", "count", "avg", "min", "max", "collect_set", "collect_list",
            "stddev", "variance", "first_value", "last_value");
        register(entries, FunctionSymbol.FunctionKind.WINDOW,
            "row_number", "rank", "dense_rank", "ntile", "lead", "lag", "cume_dist", "percent_rank");
        register(entries, FunctionSymbol.FunctionKind.GENERATOR,
            "explode", "posexplode", "inline", "json_tuple", "parse_url_tuple", "stack");
        for (Map.Entry<String, FunctionDescriptor> entry : extensions.entrySet()) {
            entries.put(SemanticNames.normalize(entry.getKey()), entry.getValue());
        }
        this.descriptors = ModelCollections.immutableMap(entries);
    }

    private static void register(Map<String, FunctionDescriptor> entries, FunctionSymbol.FunctionKind kind, String... names) {
        for (String name : names) {
            QualifiedName qualified = new QualifiedName(Collections.singletonList(name));
            ResolvedFunctionSymbol symbol = new ResolvedFunctionSymbol(qualified, kind, "hive." + name);
            FunctionSignature signature = new FunctionSignature(Collections.<TypeRef>emptyList(), true,
                Collections.singletonList(resultType(name)));
            FunctionOutputDependency dependency = new FunctionOutputDependency(0, Collections.<Integer>emptySet(),
                kind == FunctionSymbol.FunctionKind.AGGREGATE || kind == FunctionSymbol.FunctionKind.WINDOW,
                kind == FunctionSymbol.FunctionKind.WINDOW);
            entries.put(name, new FunctionDescriptor(symbol, Arrays.asList(signature), Arrays.asList(dependency),
                Collections.<String>emptyList(), false));
        }
    }

    private static final Map<String, TypeRef> RESULT_TYPES = resultTypes();

    private static Map<String, TypeRef> resultTypes() {
        Map<String, TypeRef> types = new LinkedHashMap<>();
        returns(types, TypeRef.Kind.INTEGER, "count", "row_number", "rank", "dense_rank", "ntile", "size", "length",
            "instr", "datediff", "unix_timestamp", "to_unix_timestamp", "year", "month", "day", "dayofmonth",
            "hour", "minute", "second", "hash");
        returns(types, TypeRef.Kind.DECIMAL, "avg", "percent_rank", "cume_dist", "stddev", "variance");
        returns(types, TypeRef.Kind.STRING, "upper", "lower", "concat", "concat_ws", "substring", "substr",
            "regexp_replace", "regexp_extract", "date_format", "trim", "ltrim", "rtrim", "get_json_object",
            "parse_url", "md5", "sha1", "sha2", "reverse", "replace", "format_number", "from_unixtime");
        returns(types, TypeRef.Kind.BOOLEAN, "isnull", "isnotnull");
        returns(types, TypeRef.Kind.DATE, "to_date", "current_date", "date_add", "date_sub", "add_months");
        returns(types, TypeRef.Kind.TIMESTAMP, "current_timestamp");
        return Collections.unmodifiableMap(types);
    }

    private static void returns(Map<String, TypeRef> types, TypeRef.Kind kind, String... names) {
        for (String name : names) {
            types.put(name, SemanticTypes.type(kind));
        }
    }

    private static TypeRef resultType(String name) {
        return RESULT_TYPES.getOrDefault(name, TypeRef.unknown());
    }

    @Override
    public Optional<FunctionDescriptor> find(QualifiedName name, String semanticVersion) {
        String key = SemanticNames.normalize(name.getDisplayName());
        return Optional.ofNullable(descriptors.get(key));
    }
}

