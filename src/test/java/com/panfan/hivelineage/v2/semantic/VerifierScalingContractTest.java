package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.analysis.AnalysisFixtures;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.IrTestBuilder;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Counts graph visits, so machine load cannot conceal a width-dependent regression.
 */
public class VerifierScalingContractTest {
    @Test
    public void transparentWideChainDoesNotRescanAncestorsForEachSlot() {
        int narrow = visits(8, 60);
        int wide = visits(2000, 60);
        assertTrue("ancestor visits narrow=" + narrow + ", wide=" + wide, wide <= narrow + 10);
    }

    private int visits(int width, int depth) {
        List<OutputSlot> slots = new ArrayList<>();
        for (int i = 0; i < width; i++) slots.add(IrTestBuilder.slot("slot." + i, "c" + i));
        OutputSchema schema = OutputSchema.known(slots);
        AtomicInteger visits = new AtomicInteger();
        BoundRelation leaf = new CountingLeaf(schema, visits);
        List<BoundRelation> relations = new ArrayList<>();
        relations.add(leaf);
        BoundRelation input = leaf;
        for (int i = 0; i < depth; i++) {
            input = new Relations.Filter(new NodeId("filter." + i), schema, new QueryBlockId("main"), i, null,
                    input.getId(), AnalysisFixtures.literal("predicate." + i, "true"), AnalysisFixtures.passthrough(schema));
            relations.add(input);
        }
        // An unknown third-party leaf must be rejected, while the verifier still bounds its work.
        List<com.panfan.hivelineage.v2.common.Diagnostic> diagnostics = new DefaultStructuralVerifier()
                .verifyStructure(IrTestBuilder.unit(relations)).getDiagnostics();
        assertEquals(2, diagnostics.size());
        assertEquals("IR_RELATION_KIND_UNSUPPORTED", diagnostics.get(0).getCode());
        assertEquals("IR_OUTPUT_MAPPING_INCOMPLETE", diagnostics.get(1).getCode());
        return visits.get();
    }

    private static final class CountingLeaf implements BoundRelation {
        private final OutputSchema schema;
        private final AtomicInteger visits;

        CountingLeaf(OutputSchema schema, AtomicInteger visits) {
            this.schema = schema;
            this.visits = visits;
        }

        @Override
        public NodeId getId() {
            return new NodeId("leaf");
        }

        @Override
        public OutputSchema getOutputSchema() {
            return schema;
        }

        @Override
        public List<NodeId> getInputIds() {
            visits.incrementAndGet();
            return Collections.emptyList();
        }

        @Override
        public QueryBlockId getQueryBlockId() {
            return new QueryBlockId("main");
        }

        @Override
        public int getSourceOrder() {
            return 0;
        }

        @Override
        public Optional<SourceRef> getSourceRef() {
            return Optional.empty();
        }
    }
}
