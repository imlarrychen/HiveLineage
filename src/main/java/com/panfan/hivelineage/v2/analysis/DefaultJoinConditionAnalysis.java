package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.ResourceLimitException;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.CorrelatedQualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.ResolvedFunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.VerifiedAnalysisUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reads bound predicate structure and owner annotations; never extracts keywords from text. */
public final class DefaultJoinConditionAnalysis implements JoinConditionAnalysis {
    private final int maxEvidenceEdges;

    public DefaultJoinConditionAnalysis() {
        this(Integer.MAX_VALUE);
    }

    public DefaultJoinConditionAnalysis(int maxEvidenceEdges) {
        if (maxEvidenceEdges < 0) {
            throw new IllegalArgumentException("maxEvidenceEdges must be nonnegative");
        }
        this.maxEvidenceEdges = maxEvidenceEdges;
    }

    public JoinFacts analyze(VerifiedAnalysisUnit unit) {
        return analyze(unit, new DefaultJoinRuleRegistry());
    }

    @Override
    public JoinFacts analyze(VerifiedAnalysisUnit verified, LineageRuleRegistry rules) {
        if (!verified.getCertificate().coversWholeUnit()) {
            throw new IllegalArgumentException("NEEDS_BINDING: whole-unit Join analysis requires a whole-unit verification claim");
        }
        if (!verified.getCertificate().getRequirements().contains(BindingRequirement.CONDITION_FIELDS)) {
            throw new IllegalArgumentException("NEEDS_BINDING: condition fields were not verified");
        }
        PureIrIndex index = new PureIrIndex(verified.getUnit());
        RelationFactsEngine engine = new RelationFactsEngine(verified.getUnit(), rules, maxEvidenceEdges);
        List<Relations.Join> joins = new ArrayList<Relations.Join>();
        for (BoundRelation relation : verified.getUnit().getRelations()) {
            if (relation instanceof Relations.Join && (!verified.getUnit().getQueryBlocks().containsKey(relation.getQueryBlockId())
                    || verified.getUnit().getQueryBlocks().get(relation.getQueryBlockId()).isAnalyzeJoins())) {
                joins.add((Relations.Join) relation);
            }
        }
        joins = ordered(joins, index, verified);
        List<JoinFact> result = new ArrayList<JoinFact>();
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        long references = 0L;
        for (Relations.Join join : joins) {
            Optional<BoundExpression> predicate = predicate(join);
            if (!predicate.isPresent()) {
                continue;
            }
            Map<SlotId, ExpressionFacts> slots = new LinkedHashMap<SlotId, ExpressionFacts>();
            List<Dependency> rows = new ArrayList<Dependency>();
            for (NodeId input : join.getInputIds()) {
                RelationFacts facts = engine.relation(input);
                slots.putAll(facts.getOutputFacts());
                rows.addAll(facts.getRowSetFacts());
            }
            List<LineageEndpoint> direct = new ArrayList<LineageEndpoint>();
            List<LineageEndpoint> physical = new ArrayList<LineageEndpoint>();
            List<SourceRef> lexical = new ArrayList<SourceRef>();
            List<Diagnostic> warnings = new ArrayList<Diagnostic>();
            if (predicate.isPresent()) {
                direct(predicate.get(), index, direct, lexical);
                ExpressionFacts proof = engine.expression(predicate.get(), slots, rows);
                for (Dependency dependency : proof.getDependencies()) {
                    if (dependency.getRole() != DependencyRole.DEFINITION && dependency.getSource() instanceof ColumnEndpoint) {
                        physical.add(dependency.getSource());
                    } else if (dependency.getRole() != DependencyRole.DEFINITION && dependency.getSource() instanceof RowSetEndpoint
                            && index.object(((RowSetEndpoint) dependency.getSource()).getRelationId()).isPresent()) {
                        physical.add(dependency.getSource());
                    }
                }
                warnings.addAll(proof.getDiagnostics().getDiagnostics());
            }
            diagnostics.addAll(warnings);
            references += distinct(direct).size() + distinct(physical).size();
            if (references + engine.support().proofEdges() > maxEvidenceEdges) {
                throw new ResourceLimitException("Incremental Join proof/reference limit exceeded");
            }
            String path = verified.getUnit().getQueryBlocks().containsKey(join.getQueryBlockId())
                    ? verified.getUnit().getQueryBlocks().get(join.getQueryBlockId()).getPath() : "root";
            result.add(new JoinFact(result.size() + 1, join.getQueryBlockId(), path,
                    join.getJoinForm() == Relations.JoinForm.COMMA ? JoinKind.IMPLICIT : kind(join.getJoinType()),
                    predicate.map(BoundExpression::getId), distinct(direct), distinct(physical), lexical, new DiagnosticBag(warnings),
                    new JoinConditionSource(predicate, predicate.flatMap(BoundExpression::getSourceRef))));
        }
        return new JoinFacts(result, new DiagnosticBag(diagnostics), engine.support().graph());
    }

    private Optional<BoundExpression> predicate(Relations.Join join) {
        if (join.getJoinForm() == Relations.JoinForm.EXPLICIT) {
            return join.getCondition();
        }
        List<BoundExpression> conditions = new ArrayList<BoundExpression>();
        for (Relations.JoinPredicate predicate : join.getPredicates()) {
            if (predicate.getOrigin() == Relations.PredicateOrigin.WHERE && predicate.getOwners().size() > 1
                    && !hasCorrelation(predicate.getExpression())) {
                conditions.add(predicate.getExpression());
            }
        }
        if (conditions.isEmpty()) {
            return Optional.empty();
        }
        if (conditions.size() == 1) {
            return Optional.of(conditions.get(0));
        }
        return Optional.of(new Expressions.Call(new NodeId(join.getId().getValue() + ":implicit-condition"), TypeRef.unknown(), null,
                new ResolvedFunctionSymbol(new QualifiedName(Collections.singletonList("and")), FunctionSymbol.FunctionKind.OPERATOR, "operator.AND"),
                conditions, false));
    }

    private boolean hasCorrelation(BoundExpression expression) {
        if (expression instanceof Expressions.CorrelatedSlotRef || expression instanceof CorrelatedQualifiedFieldHandle) {
            return true;
        }
        for (BoundExpression child : IrTraversal.children(expression)) {
            if (hasCorrelation(child)) {
                return true;
            }
        }
        return false;
    }

    private void direct(BoundExpression expression, PureIrIndex index, List<LineageEndpoint> references, List<SourceRef> lexical) {
        expression.getSourceRef().ifPresent(lexical::add);
        if (expression instanceof QualifiedFieldHandle) {
            QualifiedFieldHandle field = (QualifiedFieldHandle) expression;
            references.add(new DirectFieldEndpoint(field.getOwner(), field.getFieldIdentity().getId(), field.getFieldPath(), index.object(field.getOwner())));
        } else if (expression instanceof CorrelatedQualifiedFieldHandle) {
            CorrelatedQualifiedFieldHandle field = (CorrelatedQualifiedFieldHandle) expression;
            references.add(new DirectFieldEndpoint(field.getOwner(), field.getFieldIdentity().getId(), field.getFieldPath(), index.object(field.getOwner())));
        } else if (expression instanceof Expressions.SlotRef) {
            references.add(new SlotEndpoint(((Expressions.SlotRef) expression).getSlotId()));
        } else if (expression instanceof Expressions.CorrelatedSlotRef) {
            references.add(new SlotEndpoint(((Expressions.CorrelatedSlotRef) expression).getSlotId()));
        } else if (expression instanceof Expressions.UnknownReference) {
            Expressions.UnknownReference unknown = (Expressions.UnknownReference) expression;
            unknown.getDisplayReference().ifPresent(name -> references.add(new UnknownDirectEndpoint(unknown.getDisplayOwner(), name,
                    FactSupport.diagnostic(unknown.getDiagnosticCode(), "Direct field lineage is unknown", expression.getSourceRef().orElse(null)))));
        }
        for (BoundExpression child : IrTraversal.children(expression)) {
            direct(child, index, references, lexical);
        }
    }

    private List<LineageEndpoint> distinct(List<LineageEndpoint> references) {
        LinkedHashMap<String, LineageEndpoint> result = new LinkedHashMap<String, LineageEndpoint>();
        for (LineageEndpoint reference : references) {
            String key = FactSupport.endpointKey(reference);
            if (!result.containsKey(key)) {
                result.put(key, reference);
            }
        }
        return new ArrayList<LineageEndpoint>(result.values());
    }

    private JoinKind kind(Relations.JoinType type) {
        return JoinKind.valueOf(type.name());
    }

    private List<Relations.Join> ordered(List<Relations.Join> joins, PureIrIndex index, VerifiedAnalysisUnit verified) {
        LinkedHashMap<QueryBlockId, List<Relations.Join>> blocks = new LinkedHashMap<>();
        for (Relations.Join join : joins) {
            blocks.computeIfAbsent(join.getQueryBlockId(), ignored -> new ArrayList<>()).add(join);
        }
        List<List<Relations.Join>> groups = new ArrayList<>(blocks.values());
        Collections.sort(groups, Comparator.comparingInt(group -> priority(group.get(0), verified)));
        List<Relations.Join> result = new ArrayList<>();
        Set<NodeId> seen = new HashSet<>();
        for (List<Relations.Join> group : groups) {
            Collections.sort(group, Comparator.comparingInt(Relations.Join::getSourceOrder));
            for (Relations.Join join : group) {
                postorder(join, join.getQueryBlockId(), index, seen, result);
            }
        }
        return result;
    }

    private int priority(Relations.Join join, VerifiedAnalysisUnit verified) {
        String path = verified.getUnit().getQueryBlocks().containsKey(join.getQueryBlockId())
                ? verified.getUnit().getQueryBlocks().get(join.getQueryBlockId()).getPath() : "root";
        return path.startsWith("cte.") ? 0 : "root".equals(path) ? 1 : 2;
    }

    private void postorder(BoundRelation relation, QueryBlockId block, PureIrIndex index,
            Set<NodeId> seen, List<Relations.Join> result) {
        if (!relation.getQueryBlockId().equals(block) || !seen.add(relation.getId())) {
            return;
        }
        for (NodeId input : relation.getInputIds()) {
            BoundRelation child = index.getRelations().get(input);
            if (child != null) {
                postorder(child, block, index, seen, result);
            }
        }
        if (relation instanceof Relations.Join) {
            result.add((Relations.Join) relation);
        }
    }
}
