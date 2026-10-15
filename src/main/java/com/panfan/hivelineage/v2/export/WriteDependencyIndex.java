package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.ColumnEndpoint;
import com.panfan.hivelineage.v2.analysis.Dependency;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.identity.FieldId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, projection-scoped grouping; every dependency is visited once when indexing. */
public final class WriteDependencyIndex {
    private final Map<NodeId, List<Dependency>> writes = new LinkedHashMap<NodeId, List<Dependency>>();
    private final Map<NodeId, Map<ObjectId, Map<FieldId, List<Dependency>>>> fields = new LinkedHashMap<NodeId, Map<ObjectId, Map<FieldId, List<Dependency>>>>();

    public WriteDependencyIndex(List<Dependency> dependencies) {
        for (Dependency dependency : dependencies) {
            NodeId operation = dependency.getEvidence().getOperationId();
            writes.computeIfAbsent(operation, key -> new ArrayList<Dependency>()).add(dependency);
            if (dependency.getTarget() instanceof ColumnEndpoint) {
                ColumnEndpoint target = (ColumnEndpoint) dependency.getTarget();
                fields.computeIfAbsent(operation, key -> new LinkedHashMap<ObjectId, Map<FieldId, List<Dependency>>>())
                        .computeIfAbsent(target.getObjectId(), key -> new LinkedHashMap<FieldId, List<Dependency>>())
                        .computeIfAbsent(target.getFieldId(), key -> new ArrayList<Dependency>()).add(dependency);
            }
        }
        writes.replaceAll((id, values) -> Collections.unmodifiableList(values));
        fields.values().forEach(objects -> objects.values().forEach(columns ->
                columns.replaceAll((id, values) -> Collections.unmodifiableList(values))));
    }

    public List<Dependency> forWrite(BoundWrite write) {
        return writes.getOrDefault(write.getId(), Collections.emptyList());
    }

    public List<Dependency> forField(BoundWrite write, FieldIdentity field) {
        return fields.getOrDefault(write.getId(), Collections.emptyMap())
                .getOrDefault(write.getTargetObject(), Collections.emptyMap())
                .getOrDefault(field.getId(), Collections.emptyList());
    }
}
