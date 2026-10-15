package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.ExternalDatasetRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.semantic.DefaultStructuralVerifier;

import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExternalDatasetLineageTest {
    @Test
    public void registeredLeafRulePreservesStorageIdentityWithoutReadingOrInventingFields() {
        ExternalDatasetRelation external = storage(new OutputSchema(SchemaState.UNKNOWN, Collections.emptyList()));
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Collections.<BoundRelation>singletonList(external), Collections.emptyList());
        LineageFacts facts = new DefaultLineageEngine().analyze(AnalysisFixtures.verify(unit,
                BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA));
        RelationFacts relation = facts.getRelations().get(0);
        assertTrue(relation.getOutputFacts().isEmpty());
        assertEquals(2, relation.getRowSetFacts().size());
        Dependency storage = relation.getRowSetFacts().get(0);
        assertEquals(DependencyRole.STORAGE, storage.getRole());
        assertEquals("hdfs://warehouse/input/2026-10-12", ((ExternalDatasetEndpoint) storage.getSource()).getDatasetIdentity());
        assertEquals(DependencyRole.SCHEMA, relation.getRowSetFacts().get(1).getRole());
        assertTrue(relation.getRowSetFacts().get(1).getSource() instanceof UnknownEndpoint);
        assertEquals(Coverage.PARTIAL, facts.getQuality().getCoverage());
        assertTrue(new DefaultLineageRuleRegistry().relationRule(new RuleKey("ir", "ExternalDatasetRelation", "1")).isPresent());
    }

    @Test
    public void storageDeclarationCannotClaimKnownFileSchema() {
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Collections.<BoundRelation>singletonList(storage(OutputSchema.known(Collections.emptyList()))), Collections.emptyList());
        assertFalse(new DefaultStructuralVerifier().verifyStructure(unit).getDiagnostics().isEmpty());
        assertTrue(new DefaultStructuralVerifier().verifyStructure(unit).getDiagnostics().stream()
                .anyMatch(value -> value.getCode().equals("IR_EXTERNAL_DATASET_SHAPE")));
    }

    private ExternalDatasetRelation storage(OutputSchema schema) {
        return new ExternalDatasetRelation(new NodeId("external-input"), schema, new QueryBlockId("main"), 0, null,
                "hdfs://warehouse/input/2026-10-12", ExternalDatasetRelation.SourceKind.DISTRIBUTED_PATH);
    }
}
