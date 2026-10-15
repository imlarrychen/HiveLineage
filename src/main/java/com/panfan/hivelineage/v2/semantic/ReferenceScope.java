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
import com.panfan.hivelineage.v2.syntax.NamedWindowSyntax;
import com.panfan.hivelineage.v2.syntax.CteSyntax;

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
    final ScopeSymbols symbols = new ScopeSymbols();
    final Map<String, NamedWindowSyntax> windows = new LinkedHashMap<>();
    final WindowLayout windowsLayout = new WindowLayout();
    final Map<NodeId, BoundExpression> expressions = new LinkedHashMap<>();
    Optional<QualifiedName> database;
    String displayPath = "root";
    ClauseLocation clauseLocation = ClauseLocation.SELECT;
    int childSequence;
    boolean analyzeJoins = true;

    Map<SlotId, RelationInstanceId> slotOwners() {
        return symbols.slotOwners();
    }

    void setOutputAliases(OutputSchema schema) {
        symbols.setOutputAliases(schema);
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
