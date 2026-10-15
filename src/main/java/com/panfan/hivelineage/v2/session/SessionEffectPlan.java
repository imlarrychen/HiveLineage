package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collections;

import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;

/**
 * Uncommitted effects and their expected base revision, without mutation logic.
 */
public final class SessionEffectPlan {
    private final long expectedRevision;
    private final List<BoundEffect> effects;
    private final DiagnosticBag diagnostics;
    private final Optional<BoundAnalysisUnit> boundUnit;
    private final Map<DefinitionId, DefinitionTemplate> definitions;

    public SessionEffectPlan(long expectedRevision, List<BoundEffect> effects, DiagnosticBag diagnostics) {
        this(expectedRevision, effects, diagnostics, Optional.<BoundAnalysisUnit>empty(), Collections.<DefinitionId, DefinitionTemplate>emptyMap());
    }

    public SessionEffectPlan(long expectedRevision, List<BoundEffect> effects, DiagnosticBag diagnostics,
                             Optional<BoundAnalysisUnit> boundUnit, Map<DefinitionId, DefinitionTemplate> definitions) {
        this.boundUnit = ModelChecks.value(boundUnit, "boundUnit");
        this.definitions = ModelCollections.immutableMap(definitions);
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        this.expectedRevision = expectedRevision;
        this.effects = ModelCollections.immutableList(effects);
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
    }

    public SessionEffectPlan withDefinitions(Map<DefinitionId, DefinitionTemplate> templates) {
        Map<DefinitionId, DefinitionTemplate> combined = new java.util.LinkedHashMap<>(definitions);
        templates.forEach(combined::putIfAbsent);
        return new SessionEffectPlan(expectedRevision, effects, diagnostics, boundUnit, combined);
    }

    public long getExpectedRevision() {
        return expectedRevision;
    }

    public List<BoundEffect> getEffects() {
        return effects;
    }

    public Optional<BoundAnalysisUnit> getBoundUnit() {
        return boundUnit;
    }

    public Map<DefinitionId, DefinitionTemplate> getDefinitions() {
        return definitions;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }
}

