package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import java.util.List;
import java.util.Optional;

public final class JoinFact {
    private final int joinIndex;
    private final QueryBlockId queryBlockId;
    private final String queryBlockPath;
    private final JoinKind kind;
    private final Optional<NodeId> predicateId;
    private final List<LineageEndpoint> directReferences;
    private final List<LineageEndpoint> physicalReferences;
    private final List<SourceRef> lexicalReferences;
    private final DiagnosticBag diagnostics;
    private final JoinConditionSource conditionSource;

    public JoinFact(int joinIndex, QueryBlockId queryBlockId, String queryBlockPath, JoinKind kind, Optional<NodeId> predicateId, List<LineageEndpoint> directReferences, List<LineageEndpoint> physicalReferences, List<SourceRef> lexicalReferences, DiagnosticBag diagnostics) {
        this(joinIndex, queryBlockId, queryBlockPath, kind, predicateId, directReferences, physicalReferences, lexicalReferences, diagnostics,
            new JoinConditionSource(Optional.empty(), Optional.empty()));
    }

    public JoinFact(int joinIndex, QueryBlockId queryBlockId, String queryBlockPath, JoinKind kind, Optional<NodeId> predicateId, List<LineageEndpoint> directReferences, List<LineageEndpoint> physicalReferences, List<SourceRef> lexicalReferences, DiagnosticBag diagnostics, JoinConditionSource conditionSource) {
        this.joinIndex = ModelChecks.nonNegative(joinIndex, "joinIndex");
        this.queryBlockId = ModelChecks.value(queryBlockId, "queryBlockId");
        this.queryBlockPath = ModelChecks.text(queryBlockPath, "queryBlockPath");
        this.kind = ModelChecks.value(kind, "kind");
        this.predicateId = ModelChecks.value(predicateId, "predicateId");
        this.directReferences = ModelCollections.immutableList(directReferences);
        this.physicalReferences = ModelCollections.immutableList(physicalReferences);
        this.lexicalReferences = ModelCollections.immutableList(lexicalReferences);
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
        this.conditionSource = ModelChecks.value(conditionSource, "conditionSource");
        if (conditionSource.getPredicate().isPresent()
            && (!predicateId.isPresent() || !predicateId.get().equals(conditionSource.getPredicate().get().getId()))) {
            throw new IllegalArgumentException("condition expression must match the declared predicate identity");
        }
        if (joinIndex == 0) {
            throw new IllegalArgumentException("joinIndex starts at one");
        }
    }

    public int getJoinIndex() {
        return joinIndex;
    }

    public QueryBlockId getQueryBlockId() {
        return queryBlockId;
    }

    public String getQueryBlockPath() {
        return queryBlockPath;
    }

    public JoinKind getKind() {
        return kind;
    }

    public Optional<NodeId> getPredicateId() {
        return predicateId;
    }

    public List<LineageEndpoint> getDirectReferences() {
        return directReferences;
    }

    public List<LineageEndpoint> getPhysicalReferences() {
        return physicalReferences;
    }

    public List<SourceRef> getLexicalReferences() {
        return lexicalReferences;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }

    public JoinConditionSource getConditionSource() {
        return conditionSource;
    }
}
