package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;

/** A source occurrence owns its schema discovery state; uncertainty is terminal. */
final class SourceBinding {
    private enum SchemaLoading { UNREQUESTED, LOADING, RESOLVED, UNCERTAIN }

    final NodeId relationId;
    final RelationInstanceId instanceId;
    final ObjectId objectId;
    final QualifiedName name;
    final List<String> aliases;
    final boolean physical;
    final SourceRef source;
    private final boolean generated;
    private final String explicitAlias;
    private OutputSchema schema;
    private Map<String, List<OutputSlot>> columnIndex;
    private SchemaLoading loading;

    private SourceBinding(NodeId relationId, RelationInstanceId instanceId, ObjectId objectId,
                          QualifiedName name, List<String> aliases, boolean physical, SourceRef source,
                          OutputSchema schema, SchemaLoading loading, boolean generated, String explicitAlias) {
        this.relationId = Objects.requireNonNull(relationId, "relationId");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.objectId = objectId;
        this.name = Objects.requireNonNull(name, "name");
        this.aliases = Collections.unmodifiableList(new ArrayList<>(aliases));
        this.physical = physical;
        this.source = source;
        installSchema(schema);
        this.loading = loading;
        this.generated = generated;
        this.explicitAlias = explicitAlias;
    }

    static SourceBinding physical(NodeId relation, RelationInstanceId instance, ObjectId object,
                                  QualifiedName name, List<String> aliases, SourceRef source,
                                  OutputSchema schema, boolean uncertain, String explicitAlias) {
        return new SourceBinding(relation, instance, Objects.requireNonNull(object, "object"), name, aliases,
                true, source, uncertain ? uncertainSchema(relation) : schema, uncertain ? SchemaLoading.UNCERTAIN : SchemaLoading.UNREQUESTED, false, explicitAlias);
    }

    static SourceBinding derived(NodeId relation, RelationInstanceId instance, ObjectId object,
                                 QualifiedName name, SourceRef source, OutputSchema schema) {
        return new SourceBinding(relation, instance, object, name, Collections.singletonList(name.getDisplayName()),
                false, source, schema, SchemaLoading.RESOLVED, false, null);
    }

    static SourceBinding generated(NodeId relation, RelationInstanceId instance, QualifiedName name,
                                   SourceRef source, OutputSchema schema) {
        return new SourceBinding(relation, instance, null, name, name.getParts(), false,
                source, schema, SchemaLoading.RESOLVED, true, null);
    }

    OutputSchema schema() {
        return schema;
    }

    boolean isUncertain() {
        return loading == SchemaLoading.UNCERTAIN;
    }

    boolean isGenerated() {
        return generated;
    }

    boolean explicitlyMatches(String qualifier) {
        return qualifier.equals(explicitAlias) || !physical;
    }

    boolean beginSchemaLoad() {
        if (!physical || loading != SchemaLoading.UNREQUESTED) {
            return false;
        }
        loading = SchemaLoading.LOADING;
        return true;
    }

    void completeSchemaLoad(OutputSchema discovered) {
        if (loading != SchemaLoading.LOADING || !physical) {
            throw new IllegalStateException("Only an attempted physical schema load may complete");
        }
        installSchema(discovered);
        loading = SchemaLoading.RESOLVED;
    }

    private static OutputSchema uncertainSchema(NodeId relation) {
        return BindingWork.unknown(new NodeId(relation.getValue() + "/uncertain-width"), "SESSION_OBJECT_UNCERTAIN");
    }

    void markUncertain() {
        if (!physical) {
            throw new IllegalStateException("Only physical sources have session uncertainty");
        }
        loading = SchemaLoading.UNCERTAIN;
        installSchema(uncertainSchema(relationId));
    }

    private void installSchema(OutputSchema next) {
        Objects.requireNonNull(next, "schema");
        Map<String, List<OutputSlot>> fields = new LinkedHashMap<>();
        for (OutputSlot slot : BindingWork.slots(next)) {
            String name = SemanticNames.normalize(slot.getDisplayName());
            List<OutputSlot> matches = fields.get(name);
            if (matches == null) {
                matches = new ArrayList<>();
                fields.put(name, matches);
            }
            matches.add(slot);
        }
        Map<String, List<OutputSlot>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, List<OutputSlot>> entry : fields.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        schema = next;
        columnIndex = Collections.unmodifiableMap(immutable);
    }

    List<OutputSlot> columns(String column) {
        List<OutputSlot> matches = columnIndex.get(column);
        return matches == null ? Collections.<OutputSlot>emptyList() : matches;
    }
}
