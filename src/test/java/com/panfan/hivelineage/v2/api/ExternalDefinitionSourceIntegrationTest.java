package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.v2.compat.DefaultRequestAdapter;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.dialect.hive.HiveDialectFactory;
import com.panfan.hivelineage.v2.dialect.hive.HiveParseArtifact;
import com.panfan.hivelineage.v2.frontend.DefaultScriptPreprocessor;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.metadata.ColumnMetadata;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataAccessFactory;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.DefinitionContext;
import com.panfan.hivelineage.v2.metadata.DefinitionRef;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.MetadataFacet;
import com.panfan.hivelineage.v2.metadata.MetadataProvider;
import com.panfan.hivelineage.v2.metadata.MetadataProvenance;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.metadata.MetadataVersion;
import com.panfan.hivelineage.v2.metadata.ObjectDescriptor;
import com.panfan.hivelineage.v2.metadata.ProviderCapabilities;
import com.panfan.hivelineage.v2.metadata.RelationMetadata;
import com.panfan.hivelineage.v2.metadata.RelationSchema;
import com.panfan.hivelineage.v2.metadata.ScriptSourceProvider;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;
import com.panfan.hivelineage.v2.semantic.DefaultSemanticBinder;
import com.panfan.hivelineage.v2.semantic.DefaultViewTemplateCache;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExternalDefinitionSourceIntegrationTest {
    @Test
    public void opaqueReferenceIsReadByInjectedProviderAndPreparedBySharedFrontend() {
        Fixture fixture = new Fixture();
        fixture.warmMetadata();
        V2AnalyzeResult result = fixture.analyzer.analyzeScript(fixture.request(1), fixture.workflow);
        assertEquals(0, result.getCounts().getFailed());
        assertEquals(1, fixture.sourceReads.get());
        assertEquals(3, fixture.metadataReads.get());
        assertEquals("select id from db.src", result.getSources().get("opaque-view-source").getText());
        assertTrue(result.getFrontendEvidence().values().stream()
            .anyMatch(unit -> unit.getExpandedText().equals("select id from db.src")));
        assertTrue(result.getStatements().get(0).getBoundUnit().get().getObjects().values().stream()
            .anyMatch(object -> object.getName().getDisplayName().equals("db.src")));
    }

    @Test
    public void sourceAndMetadataReadsConsumeOneRequestBudgetAndFailureRecovers() {
        Fixture fixture = new Fixture();
        V2AnalyzeResult cold = fixture.analyzer.analyzeScript(fixture.request(1), fixture.workflow);
        assertEquals(1, fixture.metadataReads.get());
        assertEquals(0, fixture.sourceReads.get());
        assertEquals(1, cold.getCounts().getFailed());
        assertEquals("RESOURCE_LIMIT", cold.getStatements().get(0).getFailureCode().get());

        fixture.warmMetadata();
        V2AnalyzeRequest request = fixture.request(0);
        request = new V2AnalyzeRequest(request.getTask(), new SourceDocument("recovery",
            "insert into db.dest(id) select id from db.v; insert into db.dest(id) select id from db.src"),
            request.getDialect(), request.getInitialSession(), request.getMetadataContext(), request.getContract(), request.getResourceLimits());
        V2AnalyzeResult result = fixture.analyzer.analyzeScript(request, fixture.workflow);
        assertEquals(0, fixture.sourceReads.get());
        assertEquals(1, result.getCounts().getFailed());
        assertEquals("RESOURCE_LIMIT", result.getStatements().get(0).getFailureCode().get());
        assertTrue(result.getStatements().get(1).isFormalLineageProduced());
        assertFalse(result.getStatements().get(1).getStatus() == StatementStatus.FAILED);
    }

    @Test
    public void defaultInlineProviderReportsMissingTextWithoutExternalRead() {
        Fixture fixture = new Fixture();
        fixture.warmMetadata();
        DefaultV2LineageAnalyzer analyzer = new DefaultV2LineageAnalyzer(fixture.frontend, new DefaultSemanticBinder(), fixture.metadata);
        V2AnalyzeResult result = analyzer.analyzeScript(fixture.request(0), fixture.workflow);
        assertEquals(0, result.getCounts().getFailed());
        assertTrue(result.getDiagnostics().getDiagnostics().stream()
            .anyMatch(diagnostic -> diagnostic.getCode().equals("VIEW_DEFINITION_NOT_FOUND")));
        assertTrue(result.getStatements().get(0).getBoundUnit().get().getObjects().values().stream()
            .anyMatch(object -> object.getName().getDisplayName().equals("db.v")));
        assertEquals(0, fixture.sourceReads.get());
    }

    @Test
    public void oversizedExternalDefinitionFailsBeforeItCanPublishASessionEffect() {
        Fixture fixture = new Fixture();
        fixture.warmMetadata();
        fixture.definitionSql = "select id from db.src --" + String.join("", Collections.nCopies(200, "x"));
        V2AnalyzeRequest request = fixture.request(1);
        ResourceLimits old = request.getResourceLimits();
        request = new V2AnalyzeRequest(request.getTask(), request.getSource(), request.getDialect(), request.getInitialSession(),
            request.getMetadataContext(), request.getContract(), new ResourceLimits(100, old.getMaxExpansionDepth(), old.getMaxIrNodes(),
                old.getMaxEvidenceEdges(), 1, old.getMaxIrDepth()));
        V2AnalyzeResult result = fixture.analyzer.analyzeScript(request, fixture.workflow);
        assertEquals(1, result.getCounts().getFailed());
        assertEquals("RESOURCE_LIMIT", result.getStatements().get(0).getFailureCode().get());
        assertFalse(result.getStatements().get(0).isFormalLineageProduced());
        assertTrue(result.getDatasetVersions().isEmpty());
        assertFalse(result.getSources().containsKey("opaque-view-source"));
    }

    private static final class Fixture {
        private final V2AnalyzeRequest base = new DefaultRequestAdapter().adapt(LineageAnalyzeRequest.builder()
            .workflowId("wf").taskId("external-definition").taskVersion("1")
            .rawScript("insert into db.dest(id) select id from db.v")
            .options(LineageAnalyzeOptions.builder().expandViewLineage(true).build()).build());
        private final AtomicInteger metadataReads = new AtomicInteger();
        private final AtomicInteger sourceReads = new AtomicInteger();
        private String definitionSql = "select id from db.src";
        private final DefaultMetadataAccessFactory metadata = new DefaultMetadataAccessFactory();
        private final SyntaxFrontend frontend = new DialectSyntaxFrontend<HiveParseArtifact>(new HiveDialectFactory());
        private final WorkflowContext workflow;
        private final DefaultV2LineageAnalyzer analyzer;

        private Fixture() {
            MetadataProvider provider = new MetadataProvider() {
                @Override
                public ProviderCapabilities getCapabilities() {
                    return new ProviderCapabilities(EnumSet.allOf(MetadataFacet.class), ProviderCapabilities.LookupShape.BUNDLED_LOOKUP, false);
                }

                @Override
                public LookupResult<RelationMetadata> lookupRelation(QualifiedName name, MetadataReadContext context) {
                    metadataReads.incrementAndGet();
                    MetadataProvenance provenance = new MetadataProvenance(context.getSourceId(), MetadataProvenance.Origin.TEST_FIXTURE,
                        Optional.empty(), Optional.empty());
                    boolean view = name.getDisplayName().equals("db.v");
                    DefinitionRef definition = new DefinitionRef(new DefinitionId("opaque-view"), Optional.empty(),
                        new DefinitionContext(Optional.of(new QualifiedName(Collections.singletonList("db"))), Collections.emptyMap(),
                            base.getDialect().getDialectId(), base.getDialect().getParserVersion(), base.getDialect().getSemanticRuleVersion(), context),
                        provenance, MetadataVersion.unknown(), SourceRef.synthetic("opaque-view-source"));
                    ObjectDescriptor object = new ObjectDescriptor(new ObjectId(name.getDisplayName()), name,
                        view ? ObjectDescriptor.Kind.VIEW : ObjectDescriptor.Kind.TABLE, view ? Optional.of(definition) : Optional.empty(),
                        provenance, MetadataVersion.unknown());
                    RelationSchema schema = new RelationSchema(SchemaState.KNOWN, Collections.singletonList(new ColumnMetadata("id", 0,
                        TypeRef.unknown(), ColumnMetadata.Role.DATA, ColumnMetadata.Nullability.UNKNOWN)), provenance, MetadataVersion.unknown());
                    return LookupResult.found(new RelationMetadata(object, schema));
                }
            };
            workflow = new WorkflowContext("wf", base.getMetadataContext(), provider, new DefaultMetadataCache());
            ScriptSourceProvider sources = reference -> {
                assertEquals("opaque-view", reference.getDefinitionId().getValue());
                assertFalse(reference.getDefinitionText().isPresent());
                sourceReads.incrementAndGet();
                return LookupResult.found(new SourceDocument(reference.getSource().getSourceId(), definitionSql));
            };
            analyzer = new DefaultV2LineageAnalyzer(frontend, new DefaultSemanticBinder(), metadata, new DefaultScriptPreprocessor(),
                new DefaultViewTemplateCache(), sources);
        }

        private void warmMetadata() {
            for (String object : Arrays.asList("v", "src", "dest")) {
                metadata.create(workflow).lookupRelation(new QualifiedName(Arrays.asList("db", object)), base.getMetadataContext());
            }
        }

        private V2AnalyzeRequest request(int reads) {
            ResourceLimits limits = base.getResourceLimits();
            return new V2AnalyzeRequest(base.getTask(), base.getSource(), base.getDialect(), base.getInitialSession(), base.getMetadataContext(),
                base.getContract(), new ResourceLimits(limits.getMaxScriptCharacters(), limits.getMaxExpansionDepth(), limits.getMaxIrNodes(),
                    limits.getMaxEvidenceEdges(), reads, limits.getMaxIrDepth()));
        }
    }
}
