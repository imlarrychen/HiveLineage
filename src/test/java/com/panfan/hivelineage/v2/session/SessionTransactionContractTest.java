package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.Effects;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Transaction outcomes describe this commit while uncertainty remains durable session evidence. */
public class SessionTransactionContractTest {
    @Test
    public void inheritedUncertaintyDoesNotMakeAnUnrelatedEmptyCommitUncertain() {
        SessionSnapshot initial = initial();
        Effects.UnknownEffect unknown = new Effects.UnknownEffect(new NodeId("unknown"), Effects.Certainty.UNKNOWN, always(),
                Collections.singletonList(new ObjectId("external")), Collections.<Effects.SessionKey>emptyList(), true, "UNKNOWN");
        SessionSnapshot uncertain = commit(initial, unknown).getSnapshot();
        SessionCommitResult empty = commit(uncertain);
        assertEquals(CommitOutcome.COMMITTED, empty.getOutcome());
        assertTrue(empty.getSnapshot().getUncertainty().isCatalogUncertain());
        assertTrue(empty.getSnapshot().getUncertainty().getObjects().contains(new ObjectId("external")));
        assertEquals(uncertain.getUncertainty().getDiagnostics().getDiagnostics(), empty.getDiagnostics().getDiagnostics());
        assertEquals(2L, empty.getSnapshot().getRevision());
    }

    @Test
    public void aLaterCertainAssignmentRestoresStateWithoutErasingThisCommitsUncertainOutcome() {
        Effects.SessionKey key = new Effects.SessionKey(Effects.Namespace.HIVEVAR, "value");
        SessionCommitResult result = commit(initial(),
                new Effects.SetVariable(new NodeId("maybe"), Effects.Certainty.UNKNOWN, always(), key, "possible"),
                new Effects.SetVariable(new NodeId("certain"), Effects.Certainty.CERTAIN, always(), key, "known"));
        assertEquals(CommitOutcome.UNCERTAIN, result.getOutcome());
        VariableKey variable = new VariableKey(VariableKey.Namespace.HIVEVAR, "value");
        assertEquals("known", result.getSnapshot().getVariables().get(variable).getValue().get());
        assertFalse(result.getSnapshot().getUncertainty().getVariables().contains(variable));
        assertEquals(1, result.getDiagnostics().getDiagnostics().size());
    }

    static SessionSnapshot initial() {
        return SessionSnapshot.initial(new InitialSessionContext("session", "workflow", "task", "version", "cluster",
                Optional.of(new QualifiedName(Collections.singletonList("db"))), Collections.<VariableKey, VariableState>emptyMap(),
                Collections.<String, String>emptyMap()));
    }

    private static SessionCommitResult commit(SessionSnapshot before, BoundEffect... effects) {
        return new DefaultSessionEffectCommitter().commit(before,
                new SessionEffectPlan(before.getRevision(), Arrays.asList(effects), DiagnosticBag.empty()));
    }

    private static Effects.Precondition always() {
        return new Effects.Precondition(Effects.Precondition.Kind.ALWAYS, null);
    }
}
