package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

public final class DefaultSessionEffectPlanner implements SessionEffectPlanner {
    @Override
    public SessionEffectPlan plan(SessionSnapshot session, BoundAnalysisUnit unit) {
        return plan(session, unit, Collections.<DefinitionId, DefinitionTemplate>emptyMap());
    }

    public SessionEffectPlan plan(SessionSnapshot session, BoundAnalysisUnit unit,
                                  Map<DefinitionId, DefinitionTemplate> definitions) {
        return new SessionEffectPlan(session.getRevision(), unit.getEffects(), DiagnosticBag.empty(),
            Optional.of(unit), definitions);
    }
}
