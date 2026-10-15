package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;

/**
 * Declares planning only; state changes happen outside binding and analysis.
 */
public interface SessionEffectPlanner {
    SessionEffectPlan plan(SessionSnapshot snapshot, BoundAnalysisUnit unit);
}

