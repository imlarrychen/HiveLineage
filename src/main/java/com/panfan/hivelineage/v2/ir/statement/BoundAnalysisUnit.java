package com.panfan.hivelineage.v2.ir.statement;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.quality.BindingCoverage;
import com.panfan.hivelineage.v2.ir.quality.NotRequestedRegion;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Data-only binding product; construction does not verify the graph or calculate lineage.
 */
public final class BoundAnalysisUnit {
    public enum ExecutionAssumption {PRECEDING_STATEMENTS_SUCCEED, NO_EXECUTION_ASSUMPTION}

    private final NodeId statementId;
    private final Map<ObjectId, BoundObjectReference> objects;
    private final Map<ScopeId, BoundScopeReference> scopes;
    private final Map<RelationInstanceId, BoundRelationReference> relationReferences;
    private final Map<QueryBlockId, BoundQueryBlockReference> queryBlocks;
    private final List<BoundRelation> relations;
    private final List<QueryResult> queryResults;
    private final List<BoundWrite> writes;
    private final List<BoundEffect> effects;
    private final BindingCoverage bindingCoverage;
    private final DiagnosticBag diagnostics;
    private final List<NotRequestedRegion> notRequestedRegions;
    private final ExecutionAssumption executionAssumption;

    public BoundAnalysisUnit(NodeId statementId, List<BoundRelation> relations, List<QueryResult> queryResults, List<BoundWrite> writes, List<BoundEffect> effects, BindingCoverage bindingCoverage, DiagnosticBag diagnostics) {
        this(statementId, relations, queryResults, writes, effects, bindingCoverage, diagnostics, Collections.<NotRequestedRegion>emptyList(), ExecutionAssumption.PRECEDING_STATEMENTS_SUCCEED);
    }

    public BoundAnalysisUnit(NodeId statementId, List<BoundRelation> relations, List<QueryResult> queryResults, List<BoundWrite> writes, List<BoundEffect> effects, BindingCoverage bindingCoverage, DiagnosticBag diagnostics, Map<ObjectId, BoundObjectReference> objects) {
        this(statementId, relations, queryResults, writes, effects, bindingCoverage, diagnostics, Collections.<NotRequestedRegion>emptyList(), ExecutionAssumption.PRECEDING_STATEMENTS_SUCCEED, objects);
    }

    public BoundAnalysisUnit(NodeId statementId, List<BoundRelation> relations, List<QueryResult> queryResults, List<BoundWrite> writes, List<BoundEffect> effects, BindingCoverage bindingCoverage, DiagnosticBag diagnostics, List<NotRequestedRegion> notRequestedRegions, ExecutionAssumption executionAssumption) {
        this(statementId, relations, queryResults, writes, effects, bindingCoverage, diagnostics, notRequestedRegions, executionAssumption, Collections.<ObjectId, BoundObjectReference>emptyMap());
    }

    public BoundAnalysisUnit(NodeId statementId, List<BoundRelation> relations, List<QueryResult> queryResults, List<BoundWrite> writes, List<BoundEffect> effects, BindingCoverage bindingCoverage, DiagnosticBag diagnostics, List<NotRequestedRegion> notRequestedRegions, ExecutionAssumption executionAssumption, Map<ObjectId, BoundObjectReference> objects) {
        this(statementId, relations, queryResults, writes, effects, bindingCoverage, diagnostics, notRequestedRegions, executionAssumption, objects, Collections.<ScopeId, BoundScopeReference>emptyMap());
    }

    public BoundAnalysisUnit(NodeId statementId, List<BoundRelation> relations, List<QueryResult> queryResults, List<BoundWrite> writes, List<BoundEffect> effects, BindingCoverage bindingCoverage, DiagnosticBag diagnostics, List<NotRequestedRegion> notRequestedRegions, ExecutionAssumption executionAssumption, Map<ObjectId, BoundObjectReference> objects, Map<ScopeId, BoundScopeReference> scopes) {
        this(statementId, relations, queryResults, writes, effects, bindingCoverage, diagnostics, notRequestedRegions, executionAssumption, objects, scopes, Collections.<RelationInstanceId, BoundRelationReference>emptyMap());
    }

    public BoundAnalysisUnit(NodeId statementId, List<BoundRelation> relations, List<QueryResult> queryResults, List<BoundWrite> writes, List<BoundEffect> effects, BindingCoverage bindingCoverage, DiagnosticBag diagnostics, List<NotRequestedRegion> notRequestedRegions, ExecutionAssumption executionAssumption, Map<ObjectId, BoundObjectReference> objects, Map<ScopeId, BoundScopeReference> scopes, Map<RelationInstanceId, BoundRelationReference> relationReferences) {
        this(statementId, relations, queryResults, writes, effects, bindingCoverage, diagnostics, notRequestedRegions, executionAssumption, objects, scopes, relationReferences, Collections.<QueryBlockId, BoundQueryBlockReference>emptyMap());
    }

    public BoundAnalysisUnit(NodeId statementId, List<BoundRelation> relations, List<QueryResult> queryResults, List<BoundWrite> writes, List<BoundEffect> effects, BindingCoverage bindingCoverage, DiagnosticBag diagnostics, List<NotRequestedRegion> notRequestedRegions, ExecutionAssumption executionAssumption, Map<ObjectId, BoundObjectReference> objects, Map<ScopeId, BoundScopeReference> scopes, Map<RelationInstanceId, BoundRelationReference> relationReferences, Map<QueryBlockId, BoundQueryBlockReference> queryBlocks) {
        this.statementId = Objects.requireNonNull(statementId, "statementId");
        this.relations = ModelCollections.immutableList(relations);
        this.queryResults = ModelCollections.immutableList(queryResults);
        this.writes = ModelCollections.immutableList(writes);
        this.effects = ModelCollections.immutableList(effects);
        this.bindingCoverage = Objects.requireNonNull(bindingCoverage, "bindingCoverage");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.notRequestedRegions = ModelCollections.immutableList(notRequestedRegions);
        this.executionAssumption = Objects.requireNonNull(executionAssumption, "executionAssumption");
        this.objects = ModelCollections.immutableMap(objects);
        this.scopes = ModelCollections.immutableMap(scopes);
        this.relationReferences = ModelCollections.immutableMap(relationReferences);
        this.queryBlocks = ModelCollections.immutableMap(queryBlocks);
        for (Map.Entry<RelationInstanceId, BoundRelationReference> entry : this.relationReferences.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().getId())) {
                throw new IllegalArgumentException("relation declaration key must match its identity");
            }
        }
        for (Map.Entry<QueryBlockId, BoundQueryBlockReference> entry : this.queryBlocks.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().getId())) {
                throw new IllegalArgumentException("query block declaration key must match its identity");
            }
        }
        for (Map.Entry<ScopeId, BoundScopeReference> entry : this.scopes.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().getId())) {
                throw new IllegalArgumentException("scope declaration key must match its identity");
            }
        }
        for (Map.Entry<ObjectId, BoundObjectReference> entry : this.objects.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().getId())) {
                throw new IllegalArgumentException("object declaration key must match its bound identity");
            }
        }
    }

    public Map<QueryBlockId, BoundQueryBlockReference> getQueryBlocks() {
        return queryBlocks;
    }

    public Map<RelationInstanceId, BoundRelationReference> getRelationReferences() {
        return relationReferences;
    }

    public Map<ScopeId, BoundScopeReference> getScopes() {
        return scopes;
    }

    public Map<ObjectId, BoundObjectReference> getObjects() {
        return objects;
    }

    public NodeId getStatementId() {
        return statementId;
    }

    public List<BoundRelation> getRelations() {
        return relations;
    }

    public List<QueryResult> getQueryResults() {
        return queryResults;
    }

    public List<BoundWrite> getWrites() {
        return writes;
    }

    public List<BoundEffect> getEffects() {
        return effects;
    }

    public BindingCoverage getBindingCoverage() {
        return bindingCoverage;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }

    public List<NotRequestedRegion> getNotRequestedRegions() {
        return notRequestedRegions;
    }

    public ExecutionAssumption getExecutionAssumption() {
        return executionAssumption;
    }
}
