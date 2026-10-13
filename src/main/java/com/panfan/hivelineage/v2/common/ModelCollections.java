package com.panfan.hivelineage.v2.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ModelCollections {
    private ModelCollections() {
    }

    public static <T> List<T> immutableList(Collection<? extends T> values) {
        ModelChecks.value(values, "values");
        List<T> copy = new ArrayList<>();
        for (T value : values) {
            copy.add(ModelChecks.value(value, "element"));
        }
        return Collections.unmodifiableList(copy);
    }

    public static <T> Set<T> immutableSet(Collection<? extends T> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(immutableList(values)));
    }

    public static <K, V> Map<K, V> immutableMap(Map<? extends K, ? extends V> values) {
        ModelChecks.value(values, "values");
        Map<K, V> copy = new LinkedHashMap<>();
        for (Map.Entry<? extends K, ? extends V> entry : values.entrySet()) {
            copy.put(ModelChecks.value(entry.getKey(), "key"), ModelChecks.value(entry.getValue(), "value"));
        }
        return Collections.unmodifiableMap(copy);
    }
}
