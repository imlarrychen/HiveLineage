package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.core.HiveLineageAnalyzer;

/**
 * Contract for a future V2 implementation of the existing API.
 * Projection limitations must be carried into statement warnings, not discarded.
 */
public interface CompatibleLineageAnalyzer extends HiveLineageAnalyzer {
}
