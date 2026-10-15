package com.panfan.hivelineage.v2.testing;

import com.panfan.hivelineage.v2.api.DefaultScriptLineageAnalyzer;
import com.panfan.hivelineage.v2.api.DialectSyntaxFrontend;
import com.panfan.hivelineage.v2.api.SyntaxFrontend;
import com.panfan.hivelineage.v2.compat.DefaultLegacyResultProjector;
import com.panfan.hivelineage.v2.compat.DefaultRequestAdapter;
import com.panfan.hivelineage.v2.compat.HiveScriptLineageAdapter;
import com.panfan.hivelineage.v2.dialect.hive.HiveDialectFactory;
import com.panfan.hivelineage.v2.dialect.hive.HiveParseArtifact;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataAccessFactory;
import com.panfan.hivelineage.v2.semantic.DefaultSemanticBinder;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only semantic counterpart of the original view parser fault fixture.
 */
public final class ViewFrontendFaultAnalyzerFactory {
    private ViewFrontendFaultAnalyzerFactory() {
    }

    public static HiveScriptLineageAdapter createWithViewFrontendFault() {
        return createWithViewFrontendFault(new AtomicInteger());
    }

    public static HiveScriptLineageAdapter createWithViewFrontendFault(AtomicInteger observedFaults) {
        java.util.Objects.requireNonNull(observedFaults, "observedFaults");
        SyntaxFrontend delegate = new DialectSyntaxFrontend<HiveParseArtifact>(new HiveDialectFactory());
        SyntaxFrontend frontend = (unit, dialect) -> {
            if (unit.getExpandedText().contains("bad_view_marker")) {
                observedFaults.incrementAndGet();
                throw new IllegalArgumentException("broken view definition");
            }
            return delegate.parse(unit, dialect);
        };
        DefaultScriptLineageAnalyzer analyzer = new DefaultScriptLineageAnalyzer(frontend,
                new DefaultSemanticBinder(), new DefaultMetadataAccessFactory());
        return new HiveScriptLineageAdapter(analyzer, new DefaultRequestAdapter(), new DefaultLegacyResultProjector());
    }
}
