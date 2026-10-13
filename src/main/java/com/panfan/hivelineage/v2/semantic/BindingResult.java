package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.session.DefinitionRequest;
import java.util.List;
import java.util.Optional;

public final class BindingResult {
    private final BindingOutcome outcome;
    private final Optional<BoundAnalysisUnit> unit;
    private final List<DefinitionRequest> definitionRequests;
    private final DiagnosticBag diagnostics;

    public BindingResult(BindingOutcome outcome, Optional<BoundAnalysisUnit> unit, List<DefinitionRequest> definitionRequests, DiagnosticBag diagnostics) {
        this.outcome = ModelChecks.value(outcome, "outcome");
        this.unit = ModelChecks.value(unit, "unit");
        this.definitionRequests = ModelCollections.immutableList(definitionRequests);
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
        if ((outcome == BindingOutcome.COMPLETE || outcome == BindingOutcome.PARTIAL) && !unit.isPresent()) {
            throw new IllegalArgumentException("bound outcomes require an analysis unit");
        }
        if (outcome == BindingOutcome.NEEDS_DEFINITION && definitionRequests.isEmpty()) {
            throw new IllegalArgumentException("definition requests must be explicit");
        }
    }

    public BindingOutcome getOutcome() {
        return outcome;
    }

    public Optional<BoundAnalysisUnit> getUnit() {
        return unit;
    }

    public List<DefinitionRequest> getDefinitionRequests() {
        return definitionRequests;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }
}
