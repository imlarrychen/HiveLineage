package com.panfan.hivelineage.v2.contracts;

import com.panfan.hivelineage.v2.analysis.AnalysisQuality;
import com.panfan.hivelineage.v2.analysis.Coverage;
import com.panfan.hivelineage.v2.analysis.DependencyPrecision;
import com.panfan.hivelineage.v2.analysis.EffectState;
import com.panfan.hivelineage.v2.analysis.ExpressionFacts;
import com.panfan.hivelineage.v2.analysis.LineageFacts;
import com.panfan.hivelineage.v2.analysis.Validity;
import com.panfan.hivelineage.v2.api.AnalysisCapabilities;
import com.panfan.hivelineage.v2.api.AnalysisCapability;
import com.panfan.hivelineage.v2.api.SourceDocument;
import com.panfan.hivelineage.v2.api.StatementCounts;
import com.panfan.hivelineage.v2.api.StatementStatus;
import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.SourceSpan;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.semantic.BindingDemand;
import com.panfan.hivelineage.v2.semantic.BindingOutcome;
import com.panfan.hivelineage.v2.semantic.BindingResult;
import com.panfan.hivelineage.v2.semantic.VerificationOutcome;
import com.panfan.hivelineage.v2.semantic.VerificationResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

public class ModelContractsTest {
    @Test
    public void identitiesAreTypeSensitiveAndPreserveEmptySourceDocuments() {
        Assert.assertEquals(new NodeId("same"), new NodeId("same"));
        Assert.assertNotEquals(new NodeId("same"), new SlotId("same"));
        Assert.assertEquals("", new SourceDocument("empty-script", "").getText());
    }

    @Test
    public void listSnapshotPreservesDuplicatesAndRejectsMutation() {
        List<String> input = new ArrayList<>(Arrays.asList("id", "id"));
        List<String> copy = ModelCollections.immutableList(input);
        input.clear();
        Assert.assertEquals(Arrays.asList("id", "id"), copy);
        Assert.assertThrows(UnsupportedOperationException.class, () -> copy.add("another"));
        Assert.assertThrows(NullPointerException.class, () -> ModelCollections.immutableList(Arrays.asList("id", null)));
    }

    @Test
    public void mapSnapshotPreservesDeclaredOrderWithoutSharingInput() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("b", "second");
        input.put("a", "first");
        Map<String, String> copy = ModelCollections.immutableMap(input);
        input.clear();
        Assert.assertEquals(Arrays.asList("b", "a"), new ArrayList<>(copy.keySet()));
        Assert.assertThrows(UnsupportedOperationException.class, () -> copy.put("c", "third"));
    }

    @Test
    public void sourceReferencesRejectCrossDocumentSpans() {
        Assert.assertThrows(IllegalArgumentException.class, () -> new SourceSpan("s", 5, 3));
        Assert.assertThrows(IllegalArgumentException.class,
            () -> new SourceRef("one", new SourceSpan("two", 0, 1)));
        Assert.assertEquals(0, SourceRef.synthetic("fixture").getSpan().getEnd());
    }

    @Test
    public void qualifiedNamesPreserveStructuredParts() {
        List<String> parts = new ArrayList<>(Arrays.asList("Db", "table.with.dot"));
        QualifiedName name = new QualifiedName(parts);
        parts.clear();
        Assert.assertEquals(Arrays.asList("Db", "table.with.dot"), name.getParts());
        Assert.assertThrows(IllegalArgumentException.class, () -> new QualifiedName(Collections.<String>emptyList()));
    }

    @Test
    public void diagnosticsRetainStageWithoutReclassifyingUnknownAsFailure() {
        List<Diagnostic> items = new ArrayList<>();
        items.add(new Diagnostic(AnalysisStage.BINDING, "UNVERIFIED_FIELD", "schema not requested", null));
        DiagnosticBag bag = new DiagnosticBag(items);
        items.clear();
        Assert.assertEquals(AnalysisStage.BINDING, bag.getDiagnostics().get(0).getStage());
        AnalysisQuality quality = new AnalysisQuality(Validity.UNDETERMINED, Coverage.PARTIAL,
            DependencyPrecision.UNKNOWN, EffectState.NOT_APPLIED);
        ExpressionFacts facts = new ExpressionFacts(new NodeId("expr"), Collections.emptyList(), quality, bag);
        Assert.assertTrue(facts.getDependencies().isEmpty());
        Assert.assertEquals(DependencyPrecision.UNKNOWN, facts.getQuality().getPrecision());
        LineageFacts aggregate = new LineageFacts(Collections.emptyList(), Collections.emptyList(), quality, bag);
        Assert.assertEquals(EffectState.NOT_APPLIED, aggregate.getQuality().getEffectState());
    }

    @Test
    public void joinOnlyCapabilitiesAreRepresentableWithoutTableOrColumn() {
        AnalysisCapabilities capabilities = new AnalysisCapabilities(Collections.singleton(AnalysisCapability.JOIN_CONDITION));
        Assert.assertEquals(Collections.singleton(AnalysisCapability.JOIN_CONDITION), capabilities.getCapabilities());
        Assert.assertThrows(UnsupportedOperationException.class,
            () -> capabilities.getCapabilities().add(AnalysisCapability.TABLE_LINEAGE));
    }

    @Test
    public void noBindingOrVerificationResultCanImplyUnperformedWork() {
        Assert.assertThrows(IllegalArgumentException.class, () -> new BindingResult(BindingOutcome.COMPLETE,
            Optional.empty(), Collections.emptyList(), DiagnosticBag.empty()));
        Assert.assertThrows(IllegalArgumentException.class, () -> new BindingResult(BindingOutcome.NEEDS_DEFINITION,
            Optional.empty(), Collections.emptyList(), DiagnosticBag.empty()));
        BindingDemand demand = new BindingDemand(Collections.emptySet(), Collections.emptySet());
        Assert.assertThrows(IllegalArgumentException.class, () -> new VerificationResult(VerificationOutcome.ACCEPTED,
            Optional.empty(), demand, DiagnosticBag.empty()));
    }

    @Test
    public void statusesAndCountsKeepExistingNumericContract() {
        Assert.assertEquals(0, StatementStatus.SUCCESS.getCode());
        Assert.assertEquals(1, StatementStatus.PARTIAL_SUCCESS.getCode());
        Assert.assertEquals(2, StatementStatus.FAILED.getCode());
        Assert.assertEquals(3, StatementStatus.IGNORED.getCode());
        Assert.assertThrows(IllegalArgumentException.class, () -> new StatementCounts(2, 2, 1));
        Assert.assertEquals(4, new StatementCounts(4, 2, 1).getTotal());
    }
}
