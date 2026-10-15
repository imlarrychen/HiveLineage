package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.v2.analysis.DefaultJoinConditionAnalysis;
import com.panfan.hivelineage.v2.analysis.DefaultLineageEngine;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.compat.DefaultRequestAdapter;
import com.panfan.hivelineage.v2.dialect.hive.HiveDialectFactory;
import com.panfan.hivelineage.v2.dialect.hive.HiveParseArtifact;
import com.panfan.hivelineage.v2.export.LineageJsonRenderer;
import com.panfan.hivelineage.v2.frontend.DefaultScriptPreprocessor;
import com.panfan.hivelineage.v2.frontend.DefaultVariableResolver;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataAccessFactory;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.DefinitionTextSourceProvider;
import com.panfan.hivelineage.v2.metadata.ExternalReadBudget;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.metadata.MetadataReadManifest;
import com.panfan.hivelineage.v2.metadata.ObjectDescriptor;
import com.panfan.hivelineage.v2.metadata.RelationMetadata;
import com.panfan.hivelineage.v2.metadata.RelationSchema;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;
import com.panfan.hivelineage.v2.metadata.WorkflowMetadataAccess;
import com.panfan.hivelineage.v2.metadata.WorkflowMetadataAccessFactory;
import com.panfan.hivelineage.v2.semantic.DefaultAnalysisDemandPlanner;
import com.panfan.hivelineage.v2.semantic.DefaultIrVerifier;
import com.panfan.hivelineage.v2.semantic.DefaultSemanticBinder;
import com.panfan.hivelineage.v2.semantic.DefaultViewTemplateCache;
import com.panfan.hivelineage.v2.session.DefaultSessionEffectCommitter;
import com.panfan.hivelineage.v2.session.DefaultSessionEffectPlanner;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** The same behavior contract applies when the orchestration capabilities are replaced. */
public class AnalyzerStageSubstitutionTest {
    @Test
    public void delegatingStagesPreserveUnknownDiagnosticsEffectsAndOutputOrder() {
        ScriptAnalysisRequest request = request("use nextdb; insert into output select a.id from missing a "
            + "where a.id > 0; use +; insert into output select 1;");
        ScriptAnalysisResult expected = new DefaultScriptLineageAnalyzer().analyzeScript(request, workflow(request));
        AtomicInteger verified = new AtomicInteger();
        AtomicInteger committed = new AtomicInteger();
        AnalyzerServices services = AnalyzerServices.builder()
            .variables((unit, context, options) -> new DefaultVariableResolver().expand(unit, context, options))
            .demands((syntax, contract) -> new DefaultAnalysisDemandPlanner().plan(syntax, contract))
            .verifier((unit, demand) -> { verified.incrementAndGet(); return new DefaultIrVerifier().verify(unit, demand); })
            .lineage(limit -> (unit, rules) -> new DefaultLineageEngine(limit).analyze(unit, rules))
            .joins(limit -> (unit, rules) -> new DefaultJoinConditionAnalysis(limit).analyze(unit, rules))
            .effects((snapshot, unit) -> new DefaultSessionEffectPlanner().plan(snapshot, unit))
            .committer((snapshot, plan) -> { committed.incrementAndGet(); return new DefaultSessionEffectCommitter().commit(snapshot, plan); })
            .build();
        DefaultMetadataAccessFactory defaults = new DefaultMetadataAccessFactory();
        WorkflowMetadataAccessFactory alternate = context -> new ForwardingAccess(defaults.create(context));
        ScriptAnalysisResult actual = analyzer(alternate, services).analyzeScript(request, workflow(request));
        assertEquals(LineageJsonRenderer.render(expected), LineageJsonRenderer.render(actual));
        assertEquals(3, verified.get());
        assertEquals(3, committed.get());
        assertEquals("INVALID_USE_DATABASE", actual.getStatements().get(2).getFailureCode().get());
        assertEquals("nextdb", actual.getStatements().get(3).getBoundUnit().get().getObjects().values().iterator().next().getName().getParts().get(0));
    }

    @Test
    public void unexpectedStageFailureRetainsOriginalCauseAndSkipsCommit() {
        IllegalStateException original = new IllegalStateException("verifier implementation failed", new IllegalArgumentException("cause"));
        AtomicInteger commits = new AtomicInteger();
        AnalyzerServices services = AnalyzerServices.builder().verifier((unit, demand) -> { throw original; })
            .committer((snapshot, plan) -> { commits.incrementAndGet(); return new DefaultSessionEffectCommitter().commit(snapshot, plan); }).build();
        ScriptAnalysisRequest request = request("insert into db.output select 1");
        StatementAnalysisResult result = analyzer(new DefaultMetadataAccessFactory(), services).analyzeScript(request, workflow(request)).getStatements().get(0);
        assertEquals(StatementStatus.FAILED, result.getStatus());
        assertEquals("VERIFICATION_ERROR", result.getFailureCode().get());
        assertSame(original, result.getFailureCause().get());
        assertSame(original.getCause(), result.getFailureCause().get().getCause());
        assertEquals(0, commits.get());
    }

    @Test
    public void replacementStagesMustHonorResourceBoundary() {
        ScriptAnalysisRequest base = request("insert into db.output select 1");
        ScriptAnalysisRequest limited = new ScriptAnalysisRequest(base.getTask(), base.getSource(), base.getDialect(),
            base.getInitialSession(), base.getMetadataContext(), base.getContract(), new ResourceLimits(1000, 10, 0, 1000, 10));
        for (AnalyzerServices services : Arrays.asList(AnalyzerServices.defaults(), AnalyzerServices.builder()
            .verifier((unit, demand) -> new DefaultIrVerifier().verify(unit, demand)).build())) {
            StatementAnalysisResult result = analyzer(new DefaultMetadataAccessFactory(), services).analyzeScript(limited, workflow(limited)).getStatements().get(0);
            assertEquals("RESOURCE_LIMIT", result.getFailureCode().get());
        }
    }

    private DefaultScriptLineageAnalyzer analyzer(WorkflowMetadataAccessFactory metadata, AnalyzerServices services) {
        return new DefaultScriptLineageAnalyzer(new DialectSyntaxFrontend<HiveParseArtifact>(new HiveDialectFactory()),
            new DefaultSemanticBinder(), metadata, new DefaultScriptPreprocessor(), new DefaultViewTemplateCache(),
            new DefinitionTextSourceProvider(), services);
    }

    private ScriptAnalysisRequest request(String sql) {
        return new DefaultRequestAdapter().adapt(LineageAnalyzeRequest.builder().workflowId("wf").taskId("task").taskVersion("1")
            .defaultDatabase("db").rawScript(sql).options(LineageAnalyzeOptions.builder().parseMode(LineageParseMode.TABLE_ONLY)
                .includeIgnoredStatements(true).parseJoinConditions(true).build()).build());
    }

    private WorkflowContext workflow(ScriptAnalysisRequest request) {
        return new WorkflowContext("wf", request.getMetadataContext(), Optional.empty(), new DefaultMetadataCache(), Optional.empty(), Optional.empty());
    }

    private static final class ForwardingAccess implements WorkflowMetadataAccess {
        private final WorkflowMetadataAccess delegate;
        ForwardingAccess(WorkflowMetadataAccess delegate) {
            this.delegate = delegate;
        }
        @Override public LookupResult<ObjectDescriptor> lookupObject(QualifiedName name, MetadataReadContext context) {
            return delegate.lookupObject(name, context);
        }
        @Override public LookupResult<RelationSchema> lookupSchema(QualifiedName name, MetadataReadContext context) {
            return delegate.lookupSchema(name, context);
        }
        @Override public LookupResult<RelationMetadata> lookupRelation(QualifiedName name, MetadataReadContext context) {
            return delegate.lookupRelation(name, context);
        }
        @Override public LookupResult<ObjectDescriptor> lookupObject(QualifiedName name, MetadataReadContext context, ExternalReadBudget budget) {
            return delegate.lookupObject(name, context, budget);
        }
        @Override public LookupResult<RelationSchema> lookupSchema(QualifiedName name, MetadataReadContext context, ExternalReadBudget budget) {
            return delegate.lookupSchema(name, context, budget);
        }
        @Override public LookupResult<RelationMetadata> lookupRelation(QualifiedName name, MetadataReadContext context, ExternalReadBudget budget) {
            return delegate.lookupRelation(name, context, budget);
        }
        @Override public MetadataReadManifest manifest() {
            return delegate.manifest();
        }
    }
}
