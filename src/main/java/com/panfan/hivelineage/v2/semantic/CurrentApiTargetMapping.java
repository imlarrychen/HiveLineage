package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;

import java.util.ArrayList;
import java.util.List;

/** Current API compatibility owns inference, prefixes and explicit star naming together. */
final class CurrentApiTargetMapping implements SinkMappingStrategy {
    @Override
    public MappingPlan map(BoundRelation query, SinkBindingRequest request, TargetLayout layout) {
        List<OutputSlot> sources = BindingWork.slots(query.getOutputSchema());
        if (request.getExplicitTargetFields().isEmpty() && request.getTargetSchema().getState() != SchemaState.KNOWN) {
            return SinkMappingSupport.positional(query, request, inferred(request, layout, sources), true);
        }
        if (request.getExplicitTargetFields().isEmpty() && !request.isSourceContainsStar()
                && request.getTargetSchema().getState() == SchemaState.KNOWN && sources.size() < layout.getFields().size()) {
            return SinkMappingSupport.positional(query, request,
                    layout.withFields(layout.getFields().subList(0, sources.size())), true);
        }
        if (request.isSourceContainsStar() && !request.getExplicitTargetFields().isEmpty()) {
            return SinkMappingSupport.named(query, request, layout);
        }
        return SinkMappingSupport.positional(query, request, layout, false);
    }

    private TargetLayout inferred(SinkBindingRequest request, TargetLayout layout, List<OutputSlot> sources) {
        List<FieldIdentity> fields = new ArrayList<>();
        int dataFields = Math.max(0, sources.size() - layout.getDynamicPartitions().size());
        for (int index = 0; index < dataFields; index++) {
            String name = SemanticNames.normalize(sources.get(index).getDisplayName());
            fields.add(new FieldIdentity(request.getTargetObject().getValue() + "/field/" + name, name));
        }
        fields.addAll(layout.getDynamicPartitions());
        return layout.withFields(fields);
    }
}
