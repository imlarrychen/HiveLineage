package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.quality.BindingCoverage;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.NotRequestedRegion;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Stateless structure binder. Each invocation owns its scopes and metadata demands. */
public final class DefaultSemanticBinder implements SemanticBinder {
    private final SinkPlanner sinks;
    private final EffectBinder effects;
    private final TypeAnalyzer types;

    public DefaultSemanticBinder() {
        this(new DefaultSinkPlanner(), new DefaultEffectBinder(), new DefaultTypeAnalyzer());
    }

    public DefaultSemanticBinder(SinkPlanner sinks, EffectBinder effects, TypeAnalyzer types) {
        this.sinks = ModelChecks.value(sinks, "sinks");
        this.effects = ModelChecks.value(effects, "effects");
        this.types = ModelChecks.value(types, "types");
    }

    @Override
    public BindingResult bind(SyntaxStatement syntax, BindingContext context, BindingDemand demand) {
        ModelChecks.value(syntax, "syntax");
        BindingWork work = new BindingWork(context, demand, types);
        try {
            new StatementBinder(work, sinks, effects).bind(syntax);
        } catch (BindingFailure failure) {
            work.error(failure.getCode(), failure.getMessage(), syntax.getSourceRef());
        }
        Map<BindingRequirement, BindingCoverage.State> requirements = new EnumMap<>(BindingRequirement.class);
        for (BindingRequirement requirement : BindingRequirement.values()) {
            requirements.put(requirement, !work.requested(requirement) ? BindingCoverage.State.NOT_REQUESTED
                : work.failed ? BindingCoverage.State.UNKNOWN : work.partial ? BindingCoverage.State.PARTIAL : BindingCoverage.State.COMPLETE);
        }
        Map<NodeId, Map<BindingRequirement, BindingCoverage.State>> regions = new LinkedHashMap<>();
        regions.put(syntax.getNodeId(), requirements);
        BoundAnalysisUnit unit = new BoundAnalysisUnit(syntax.getNodeId(), new ArrayList<BoundRelation>(work.relations.values()),
            work.queryResults, work.writes, work.effects, new BindingCoverage(regions), work.bag(),
            Collections.<NotRequestedRegion>emptyList(), BoundAnalysisUnit.ExecutionAssumption.PRECEDING_STATEMENTS_SUCCEED,
            work.objects, work.scopes, work.relationReferences, work.queryBlocks);
        BindingOutcome outcome = work.failed ? BindingOutcome.FAILED : work.unsupported ? BindingOutcome.UNSUPPORTED : !work.definitionRequests.isEmpty()
            ? BindingOutcome.NEEDS_DEFINITION : work.partial ? BindingOutcome.PARTIAL : BindingOutcome.COMPLETE;
        return new BindingResult(outcome, Optional.of(unit), work.definitionRequests, work.bag());
    }
}
