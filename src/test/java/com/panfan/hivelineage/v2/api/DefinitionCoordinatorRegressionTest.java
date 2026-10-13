package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.dialect.hive.HiveDialectFactory;
import com.panfan.hivelineage.v2.dialect.hive.HiveParseArtifact;
import com.panfan.hivelineage.v2.frontend.ExpandedUnit;
import com.panfan.hivelineage.v2.frontend.SourceMappingKind;
import com.panfan.hivelineage.v2.frontend.VariableResolutionKind;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.metadata.DefinitionContext;
import com.panfan.hivelineage.v2.metadata.DefinitionRef;
import com.panfan.hivelineage.v2.metadata.DefinitionTextSourceProvider;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.MetadataProvenance;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.metadata.MetadataVersion;
import com.panfan.hivelineage.v2.semantic.DefaultViewTemplateCache;
import com.panfan.hivelineage.v2.semantic.PreparedDefinition;
import com.panfan.hivelineage.v2.session.DefinitionRequest;
import com.panfan.hivelineage.v2.syntax.LiteralSyntax;
import com.panfan.hivelineage.v2.syntax.QueryStatementSyntax;
import com.panfan.hivelineage.v2.syntax.SelectQuerySyntax;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;

public class DefinitionCoordinatorRegressionTest {
    private final MetadataReadContext context = new MetadataReadContext("source", "cluster", Optional.empty(), Collections.emptyMap());

    @Test
    public void variableMapsWithIdenticalDisplayStringsDoNotShareExpandedTemplates() {
        DefaultViewTemplateCache cache = new DefaultViewTemplateCache();
        Map<String, String> one = Collections.singletonMap("a", "one, b=two");
        Map<String, String> two = new LinkedHashMap<String, String>();
        two.put("a", "one");
        two.put("b", "two");
        Assert.assertEquals(one.toString(), two.toString());
        Assert.assertEquals("one, b=two", literal(coordinator(cache).prepare(request("select '${a}' as id", one, "definition"))));
        Assert.assertEquals("one", literal(coordinator(cache).prepare(request("select '${a}' as id", two, "definition"))));
    }

    @Test
    public void syntaxDiagnosticsRemainStableAcrossCoordinatorCacheHits() {
        DefaultViewTemplateCache cache = new DefaultViewTemplateCache();
        DefinitionRequest request = request("select ??? from db.t", Collections.emptyMap(), "definition");
        LookupResult<PreparedDefinition> first = coordinator(cache).prepare(request);
        LookupResult<PreparedDefinition> cached = coordinator(cache).prepare(request);
        Assert.assertTrue(codes(first).contains("RECOVERED_PROJECTION"));
        Assert.assertEquals(codes(first), codes(cached));
        Assert.assertEquals(first.getDiagnostics().getDiagnostics().size(), cached.getDiagnostics().getDiagnostics().size());
        Assert.assertTrue(cached.getValue().get().getSyntax().get().getFrontendEvidence().isPresent());
    }

    @Test
    public void unresolvedDefinitionVariablesRetainDiagnosticsAndOriginalMapping() {
        DefaultDefinitionCoordinator coordinator = coordinator(new DefaultViewTemplateCache());
        LookupResult<PreparedDefinition> result = coordinator.prepare(request("select '${unknown}' as id", Collections.emptyMap(), "definition"));
        Assert.assertTrue(codes(result).contains("UNRESOLVED_VARIABLE"));
        ExpandedUnit evidence = coordinator.getFrontendEvidence().values().iterator().next();
        Assert.assertEquals(VariableResolutionKind.UNRESOLVED, evidence.getVariableEvidence().get(0).getResolution());
        Assert.assertEquals("definition", evidence.getSourceMap().getOriginalSourceId());
        Assert.assertTrue(evidence.getSourceMap().getEntries().stream()
                .anyMatch(entry -> entry.getKind() == SourceMappingKind.VARIABLE_REPLACEMENT));
    }

    @Test
    public void definitionContextsRetainExplicitVariableNamespaces() {
        LookupResult<PreparedDefinition> result = coordinator(new DefaultViewTemplateCache()).prepare(request(
                "select '${hiveconf:x}' as id", Collections.singletonMap("hiveconf:x", "value"), "definition"));
        Assert.assertEquals("value", literal(result));
        Assert.assertTrue(result.getDiagnostics().getDiagnostics().isEmpty());
    }

    @Test
    public void changedSourceIdentityDoesNotReuseReferencesToAnotherDocument() {
        DefaultViewTemplateCache cache = new DefaultViewTemplateCache();
        coordinator(cache).prepare(request("select 1", Collections.emptyMap(), "document-a"));
        LookupResult<PreparedDefinition> second = coordinator(cache).prepare(request("select 1", Collections.emptyMap(), "document-b"));
        Assert.assertEquals("document-b:expanded:0", second.getValue().get().getSyntax().get().getStatement().getSourceRef().getSourceId());
    }

    @Test
    public void failedNativeSyntaxRetainsDetailedParserDiagnosticsAndFrontendEvidence() {
        DefaultDefinitionCoordinator coordinator = coordinator(new DefaultViewTemplateCache());
        LookupResult<PreparedDefinition> result = coordinator.prepare(request("select from", Collections.emptyMap(), "definition"));
        Assert.assertFalse(result.getValue().isPresent());
        Assert.assertTrue(codes(result).contains("HIVE_SYNTAX_ERROR"));
        Assert.assertTrue(codes(result).contains("VIEW_PARSE_FAILED"));
        Assert.assertFalse(coordinator.getFrontendEvidence().isEmpty());
    }

    @Test
    public void failedScriptBoundaryRetainsItsFrontendDiagnostic() {
        LookupResult<PreparedDefinition> result = coordinator(new DefaultViewTemplateCache()).prepare(
                request("select 'unterminated", Collections.emptyMap(), "definition"));
        Assert.assertTrue(codes(result).contains("UNTERMINATED_SCRIPT_UNIT"));
        Assert.assertTrue(codes(result).contains("VIEW_PARSE_FAILED"));
    }

    private DefaultDefinitionCoordinator coordinator(DefaultViewTemplateCache cache) {
        return new DefaultDefinitionCoordinator(new DialectSyntaxFrontend<HiveParseArtifact>(new HiveDialectFactory()),
                new DefinitionTextSourceProvider(), cache, new LinkedHashMap<String, SourceDocument>(),
                new ResourceLimits(100000, 64, 10000, 10000, 100));
    }

    private DefinitionRequest request(String sql, Map<String, String> variables, String source) {
        DefinitionRef reference = new DefinitionRef(new DefinitionId("definition"), Optional.of(sql),
                new DefinitionContext(Optional.of(new QualifiedName(Collections.singletonList("db"))), variables,
                        "hive", "4.0.1", "v2", context),
                new MetadataProvenance("source", MetadataProvenance.Origin.PROVIDER_DEFINED, Optional.empty(), Optional.empty()),
                MetadataVersion.unknown(), SourceRef.synthetic(source));
        return new DefinitionRequest(reference, EnumSet.of(BindingRequirement.OBJECTS), Collections.emptyList(), 10);
    }

    private static String literal(LookupResult<PreparedDefinition> result) {
        QueryStatementSyntax statement = (QueryStatementSyntax) result.getValue().get().getSyntax().get().getStatement();
        SelectQuerySyntax query = (SelectQuerySyntax) statement.getQuery();
        return ((LiteralSyntax) query.getBody().getSelectItems().get(0).getExpression()).getDecodedValue();
    }

    private static List<String> codes(LookupResult<PreparedDefinition> result) {
        return result.getDiagnostics().getDiagnostics().stream().map(diagnostic -> diagnostic.getCode()).collect(Collectors.toList());
    }
}
