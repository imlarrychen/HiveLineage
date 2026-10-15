package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.AnalysisFixtures;
import com.panfan.hivelineage.v2.analysis.ColumnEndpoint;
import com.panfan.hivelineage.v2.analysis.Dependency;
import com.panfan.hivelineage.v2.analysis.DependencyPrecision;
import com.panfan.hivelineage.v2.analysis.DependencyRole;
import com.panfan.hivelineage.v2.analysis.EvidenceFactory;
import com.panfan.hivelineage.v2.analysis.LineageRuleContext;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

public class WriteDependencyIndexTest {
    @Test
    public void wideProjectionVisitsDependenciesOnceAndKeepsWriteAndObjectIdentitySeparate() {
        EvidenceFactory evidence = new LineageRuleContext("1", Collections.emptyMap()).getEvidenceFactory();
        BoundWrite first = write("first", "target");
        BoundWrite second = write("second", "target");
        BoundWrite otherObject = write("first", "other-target");
        List<Dependency> values = new ArrayList<Dependency>();
        for (BoundWrite write : Arrays.asList(first, second, otherObject)) {
            for (int column = 0; column < 400; column++) {
                FieldIdentity field = new FieldIdentity("field-" + column, "column" + column);
                values.add(evidence.dependency(new ColumnEndpoint(new ObjectId("source"), field.getId(), Collections.emptyList()),
                        new ColumnEndpoint(write.getTargetObject(), field.getId(), Collections.emptyList()), DependencyRole.VALUE,
                        DependencyPrecision.STRUCTURAL, write.getId(), "write.value", Collections.emptyList(), Optional.empty()));
            }
        }
        AtomicInteger visits = new AtomicInteger();
        List<Dependency> counted = new AbstractList<Dependency>() {
            @Override
            public Dependency get(int index) {
                visits.incrementAndGet();
                return values.get(index);
            }
            @Override
            public int size() {
                return values.size();
            }
        };
        WriteDependencyIndex index = new WriteDependencyIndex(counted);
        Assert.assertEquals(1200, visits.get());
        for (BoundWrite write : Arrays.asList(first, second, otherObject)) {
            for (int column = 0; column < 400; column++) {
                List<Dependency> dependencies = index.forField(write, new FieldIdentity("field-" + column, "column" + column));
                Assert.assertEquals(1, dependencies.size());
                Assert.assertEquals(write.getTargetObject(), ((ColumnEndpoint) dependencies.get(0).getTarget()).getObjectId());
                Assert.assertEquals(write.getId(), dependencies.get(0).getEvidence().getOperationId());
            }
        }
        Assert.assertEquals("Field lookups must not rescan the input dependencies", 1200, visits.get());
    }

    private static BoundWrite write(String id, String object) {
        return new BoundWrite(new NodeId(id), new ObjectId(object), VersionRef.notRequested(), VersionRef.notRequested(), BoundWrite.WriteMode.APPEND,
                Collections.singletonList(new WriteAssignment(new FieldIdentity("field-0", "column0"), AnalysisFixtures.literal(id + object, "1"))),
                new PartitionScope(PartitionScope.Kind.UNPARTITIONED, Collections.emptyList()), null, MappingCoverage.COMPLETE, Collections.emptyList());
    }
}
