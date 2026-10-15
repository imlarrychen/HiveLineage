package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.statement.BoundScopeReference;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.syntax.NamedWindowSyntax;
import com.panfan.hivelineage.v2.syntax.CteSyntax;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ReferenceScope {
    final ReferenceScope parent;
    final ScopeId id;
    final QueryBlockId block;
    final String prefix;
    final Map<String, CteSyntax> ctes = new LinkedHashMap<>();
    final List<SourceBinding> sources = new ArrayList<>();
    final Map<String, NamedWindowSyntax> windows = new LinkedHashMap<>();
    final List<OutputSlot> windowSlots = new ArrayList<>();
    final List<Relations.WindowComputation> windowComputations = new ArrayList<>();
    private List<OutputSlot> queryOutputs = java.util.Collections.emptyList();
    private final Map<RelationInstanceId, OutputSchema> indexedSchemas = new LinkedHashMap<>();
    private Map<SlotId, RelationInstanceId> slotOwners = java.util.Collections.emptyMap();
    final Map<NodeId, BoundExpression> expressions = new LinkedHashMap<>();
    Optional<QualifiedName> database;
    String displayPath = "root";
    ClauseLocation clauseLocation = ClauseLocation.SELECT;
    int childSequence;
    boolean analyzeJoins = true;

    Map<SlotId, RelationInstanceId> slotOwners() {
        boolean changed = indexedSchemas.size() != sources.size();
        for (SourceBinding source : sources) {
            changed |= indexedSchemas.get(source.instanceId) != source.schema;
        }
        if (changed) {
            Map<SlotId, RelationInstanceId> owners = new LinkedHashMap<>();
            indexedSchemas.clear();
            for (SourceBinding source : sources) {
                indexedSchemas.put(source.instanceId, source.schema);
                for (OutputSlot slot : BindingWork.slots(source.schema)) {
                    owners.put(slot.getId(), source.instanceId);
                }
            }
            slotOwners = java.util.Collections.unmodifiableMap(owners);
        }
        return slotOwners;
    }

    void setOutputAliases(com.panfan.hivelineage.v2.ir.relation.OutputSchema schema) {
        queryOutputs = java.util.Collections.unmodifiableList(BindingWork.slots(schema));
    }

    List<OutputSlot> visibleOutputAliases() {
        return clauseLocation.outputAliasesVisible() ? queryOutputs : java.util.Collections.<OutputSlot>emptyList();
    }

    ReferenceScope(BindingWork work, ReferenceScope parent, ScopeId id, QueryBlockId block,
                   String prefix, List<CteSyntax> localCtes) {
        this.parent = parent;
        this.id = id;
        this.block = block;
        this.prefix = prefix;
        this.database = parent == null ? work.context.getSession().getCurrentDatabase() : parent.database;
        this.displayPath = parent == null ? "root" : parent.displayPath;
        this.analyzeJoins = parent == null || parent.analyzeJoins;
        if (parent != null) {
            ctes.putAll(parent.ctes);
        }
        for (CteSyntax cte : localCtes) {
            ctes.put(SemanticNames.normalize(cte.getName().getValue()), cte);
        }
        work.scopes.put(id, new BoundScopeReference(id, block,
            parent == null ? Optional.<ScopeId>empty() : Optional.of(parent.id)));
    }
}
