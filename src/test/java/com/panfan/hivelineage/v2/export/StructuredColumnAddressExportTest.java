package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.AnalysisFixtures;
import com.panfan.hivelineage.v2.analysis.DefaultLineageEngine;
import com.panfan.hivelineage.v2.analysis.LineageFacts;
import com.panfan.hivelineage.v2.api.SourceDocument;
import com.panfan.hivelineage.v2.api.StatementAnalysisResult;
import com.panfan.hivelineage.v2.api.StatementIdentity;
import com.panfan.hivelineage.v2.api.StatementStatus;
import com.panfan.hivelineage.v2.api.TaskIdentity;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

public class StructuredColumnAddressExportTest {
    @Test
    public void dottedFieldAndNestedPathAreBothExportedInStableOrder() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "source", "main", "a.b", "a");
        Expressions.SlotRef nested = new Expressions.SlotRef(new NodeId("nested"), TypeRef.unknown(), null,
                new SlotId("scan.a"), Collections.singletonList("b"));
        Expressions.Call expression = AnalysisFixtures.call("both", "plus", FunctionSymbol.FunctionKind.OPERATOR,
                AnalysisFixtures.ref("dotted", "scan.a.b"), nested, nested);
        BoundWrite write = new BoundWrite(new NodeId("write"), new ObjectId("target"), VersionRef.notRequested(), VersionRef.notRequested(),
                BoundWrite.WriteMode.APPEND, Collections.singletonList(new WriteAssignment(new FieldIdentity("target.result", "result"), expression)),
                new PartitionScope(PartitionScope.Kind.UNPARTITIONED, Collections.emptyList()), null, MappingCoverage.COMPLETE,
                Collections.emptyList(), scan.getId());
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Collections.singletonList(scan), Collections.singletonList(write));
        LineageFacts facts = new DefaultLineageEngine().analyze(AnalysisFixtures.verify(unit,
                BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA, BindingRequirement.SINK_MAPPING));
        SourceDocument source = new SourceDocument("sql-free", "");
        StatementAnalysisResult statement = new StatementAnalysisResult(new StatementIdentity(new TaskIdentity("wf", "task", "v"), 0, "s"),
                "INSERT_INTO", source, source, StatementStatus.SUCCESS, true, Optional.of(facts), Optional.empty(), Optional.of(unit),
                Optional.empty(), Optional.empty(), Optional.empty(), DiagnosticBag.empty());
        List<ColumnAddress> addresses = new DefaultFormalLineageRecordExporter().exportColumns(statement).get(0).getSources();
        Assert.assertEquals(2, addresses.size());
        Assert.assertEquals(Collections.singletonList("a.b"), addresses.get(0).getFieldPath());
        Assert.assertEquals(Arrays.asList("a", "b"), addresses.get(1).getFieldPath());
        Assert.assertEquals(2, new LinkedHashSet<ColumnAddress>(addresses).size());
    }

    @Test
    public void addressEqualityPreservesStructuredObjectNamesAndFieldPaths() {
        ColumnAddress dottedObject = new ColumnAddress(new QualifiedName(Collections.singletonList("db.table")), Collections.singletonList("field"));
        ColumnAddress qualifiedObject = new ColumnAddress(new QualifiedName(Arrays.asList("db", "table")), Collections.singletonList("field"));
        Assert.assertNotEquals(dottedObject, qualifiedObject);
        Assert.assertEquals(qualifiedObject, new ColumnAddress(new QualifiedName(Arrays.asList("db", "table")), Collections.singletonList("field")));
        Assert.assertEquals(qualifiedObject.hashCode(), new ColumnAddress(new QualifiedName(Arrays.asList("db", "table")), Collections.singletonList("field")).hashCode());
    }
}
