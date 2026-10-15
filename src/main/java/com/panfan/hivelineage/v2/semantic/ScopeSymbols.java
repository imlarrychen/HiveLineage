package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local source registration, alias precedence and schema-sensitive owner indexes. */
final class ScopeSymbols {
    private final List<SourceBinding> sources = new ArrayList<>();
    private final Map<String, List<SourceBinding>> aliases = new LinkedHashMap<>();
    private final Map<RelationInstanceId, OutputSchema> indexedSchemas = new LinkedHashMap<>();
    private Map<SlotId, RelationInstanceId> slotOwners = Collections.emptyMap();
    private List<OutputSlot> outputs = Collections.emptyList();

    void register(SourceBinding source) {
        sources.add(source);
        for (String alias : source.aliases) {
            List<SourceBinding> matches = aliases.get(alias);
            if (matches == null) {
                matches = new ArrayList<>();
                aliases.put(alias, matches);
            }
            // A physical source can expose the same simple and qualified spelling.
            if (!matches.contains(source)) {
                matches.add(source);
            }
        }
    }

    void registerAll(List<SourceBinding> additions) {
        for (SourceBinding source : additions) {
            register(source);
        }
    }

    void replaceSources(List<SourceBinding> replacements) {
        List<SourceBinding> copy = new ArrayList<>(replacements);
        sources.clear();
        aliases.clear();
        indexedSchemas.clear();
        slotOwners = Collections.emptyMap();
        registerAll(copy);
    }

    List<SourceBinding> sources() {
        return Collections.unmodifiableList(sources);
    }

    List<SourceBinding> qualified(String qualifier) {
        List<SourceBinding> matches = aliases.get(qualifier);
        if (matches == null) {
            return Collections.emptyList();
        }
        List<SourceBinding> explicit = new ArrayList<>();
        for (SourceBinding source : matches) {
            if (source.explicitlyMatches(qualifier)) {
                explicit.add(source);
            }
        }
        return Collections.unmodifiableList(explicit.isEmpty() ? new ArrayList<>(matches) : explicit);
    }

    boolean hasStruct(String column) {
        for (SourceBinding source : sources) {
            for (OutputSlot slot : source.columns(column)) {
                if (slot.getType().getKind() == com.panfan.hivelineage.v2.ir.TypeRef.Kind.STRUCT) {
                    return true;
                }
            }
        }
        return false;
    }

    void setOutputAliases(OutputSchema schema) {
        outputs = Collections.unmodifiableList(BindingWork.slots(schema));
    }

    List<OutputSlot> outputAliases(String name) {
        List<OutputSlot> matches = new ArrayList<>();
        for (OutputSlot slot : outputs) {
            if (SemanticNames.normalize(slot.getDisplayName()).equals(name)) {
                matches.add(slot);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    Map<SlotId, RelationInstanceId> slotOwners() {
        boolean changed = indexedSchemas.size() != sources.size();
        for (SourceBinding source : sources) {
            changed |= indexedSchemas.get(source.instanceId) != source.schema();
        }
        if (changed) {
            rebuildOwners();
        }
        return slotOwners;
    }

    private void rebuildOwners() {
        Map<SlotId, RelationInstanceId> owners = new LinkedHashMap<>();
        indexedSchemas.clear();
        for (SourceBinding source : sources) {
            indexedSchemas.put(source.instanceId, source.schema());
            for (OutputSlot slot : BindingWork.slots(source.schema())) {
                owners.put(slot.getId(), source.instanceId);
            }
        }
        slotOwners = Collections.unmodifiableMap(owners);
    }
}
