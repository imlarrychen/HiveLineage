package com.panfan.hivelineage.v2.ir.statement;

import com.panfan.hivelineage.v2.ir.IrTestBuilder;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class VerificationCertificateContractTest {
    @Test
    public void certificateCarriesOnlyDeclaredDemandAndFreezesIssuerClaim() {
        BoundAnalysisUnit unit = IrTestBuilder.unit(Collections.<BoundRelation>singletonList(
                IrTestBuilder.scan("scan", "instance", "table", OutputSchema.notRequested())));
        Set<BindingRequirement> requirements = EnumSet.of(BindingRequirement.OBJECTS);
        Set<VerificationCertificate.VerificationCheck> checks = EnumSet.allOf(VerificationCertificate.VerificationCheck.class);
        VerificationCertificate claim = new VerificationCertificate(unit, "fixture-explicit-claim", requirements, checks);
        requirements.clear();
        checks.clear();
        VerifiedAnalysisUnit verified = new VerifiedAnalysisUnit(unit, claim);
        assertSame(unit, verified.getUnit());
        assertEquals(Collections.singleton(BindingRequirement.OBJECTS), claim.getRequirements());
        assertFalse(claim.getRequirements().contains(BindingRequirement.OUTPUT_SCHEMA));
        assertEquals("fixture-explicit-claim", claim.getVerifierId());
        try {
            claim.getRequirements().clear();
            fail("certificate demand scope must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, claim.getRequirements().size());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void matchingStatementIdDoesNotPermitCertificateReuseOnDifferentGraph() {
        BoundAnalysisUnit original = IrTestBuilder.unit(Collections.<BoundRelation>singletonList(
                IrTestBuilder.scan("one", "one.instance", "table", OutputSchema.notRequested())));
        BoundAnalysisUnit changed = IrTestBuilder.unit(Collections.<BoundRelation>singletonList(
                IrTestBuilder.scan("two", "two.instance", "other", OutputSchema.notRequested())));
        assertEquals(original.getStatementId(), changed.getStatementId());
        VerificationCertificate claim = new VerificationCertificate(original, "fixture-explicit-claim",
                EnumSet.of(BindingRequirement.OBJECTS), EnumSet.allOf(VerificationCertificate.VerificationCheck.class));
        new VerifiedAnalysisUnit(changed, claim);
    }

    @Test(expected = IllegalArgumentException.class)
    public void incompleteChecksCannotCreateVerifiedAnalysisInput() {
        BoundAnalysisUnit unit = IrTestBuilder.unit(Collections.<BoundRelation>emptyList());
        VerificationCertificate claim = new VerificationCertificate(unit, "fixture-explicit-claim", EnumSet.of(BindingRequirement.OBJECTS),
                EnumSet.of(VerificationCertificate.VerificationCheck.STRUCTURE));
        new VerifiedAnalysisUnit(unit, claim);
    }
}
