package com.panfan.hivelineage.v2.contracts;

import com.panfan.hivelineage.v2.analysis.JoinConditionSource;
import com.panfan.hivelineage.v2.analysis.JoinFact;
import com.panfan.hivelineage.v2.analysis.JoinKind;
import com.panfan.hivelineage.v2.api.SourceDocument;
import com.panfan.hivelineage.v2.api.StatementIdentity;
import com.panfan.hivelineage.v2.api.TaskIdentity;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.SourceSpan;
import com.panfan.hivelineage.v2.export.ColumnAddress;
import com.panfan.hivelineage.v2.export.JoinConditionRecord;
import com.panfan.hivelineage.v2.export.JoinPresentation;
import com.panfan.hivelineage.v2.export.RenderedExpression;
import com.panfan.hivelineage.v2.ir.IrTestBuilder;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.semantic.FunctionOutputDependency;
import com.panfan.hivelineage.v2.semantic.PreparedDefinition;
import com.panfan.hivelineage.v2.semantic.ViewTemplateKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.Assert;
import org.junit.Test;

public class OutputContractTest {
    @Test
    public void definitionsCarryStructureInsteadOfAnUnusableMarkerOrSqlCallback() {
        Assert.assertThrows(IllegalArgumentException.class, () -> new PreparedDefinition(new DefinitionId("d"),
                Optional.empty(), Optional.empty(), SourceRef.synthetic("definition")));
        PreparedDefinition definition = new PreparedDefinition(new DefinitionId("d"), Optional.empty(),
                Optional.of(IrTestBuilder.scan("scan", "instance", "opaque-object", OutputSchema.notRequested())),
                SourceRef.synthetic("definition"));
        Assert.assertEquals(new NodeId("scan"), definition.getBoundRelation().get().getId());
        Assert.assertFalse(definition.getSyntax().isPresent());
    }

    @Test
    public void fullJoinConditionRetainsItsRenderingRangeAndExpressionIdentity() {
        SourceRef range = new SourceRef("query", new SourceSpan("query", 20, 50));
        Expressions.Literal predicate = new Expressions.Literal(new NodeId("predicate"), TypeRef.unknown(), range,
                new Expressions.LiteralValue(Expressions.LiteralValue.Kind.BOOLEAN, "true"));
        JoinConditionSource source = new JoinConditionSource(Optional.of(predicate), Optional.of(range));
        JoinFact fact = join(Optional.of(new NodeId("predicate")), source);
        Assert.assertSame(predicate, fact.getConditionSource().getPredicate().get());
        Assert.assertEquals(range, fact.getConditionSource().getCompleteConditionSource().get());
        Assert.assertThrows(IllegalArgumentException.class, () -> join(Optional.of(new NodeId("different")), source));
    }

    @Test
    public void crossJoinCanBeRepresentedWithoutAnInventedOnExpression() {
        JoinFact cross = new JoinFact(1, new QueryBlockId("q"), "root", JoinKind.CROSS, Optional.empty(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), DiagnosticBag.empty());
        Assert.assertFalse(cross.getPredicateId().isPresent());
        Assert.assertFalse(cross.getConditionSource().getPredicate().isPresent());
    }

    @Test
    public void standaloneJoinRecordPreservesIdentityAndDirectVersusPhysicalNames() {
        TaskIdentity task = new TaskIdentity("wf", "task", "business-version");
        StatementIdentity statement = new StatementIdentity(task, 0, "wf_task_business-version_0");
        ColumnAddress direct = new ColumnAddress(new QualifiedName(Collections.singletonList("a")), Collections.singletonList("id"));
        ColumnAddress physical = new ColumnAddress(new QualifiedName(Arrays.asList("ods", "orders")), Collections.singletonList("id"));
        List<ColumnAddress> directColumns = new ArrayList<>(Collections.singletonList(direct));
        JoinPresentation display = new JoinPresentation(1, "INNER_JOIN", Collections.singletonList(direct.getObject()),
                directColumns, Collections.singletonList(physical.getObject()), Collections.singletonList(physical),
                new RenderedExpression(Optional.of("a.id=b.id"), true));
        directColumns.clear();
        JoinConditionRecord record = new JoinConditionRecord(statement, new SourceDocument("q", "select ..."),
                join(Optional.empty(), new JoinConditionSource(Optional.empty(), Optional.empty())), Optional.of(display));
        Assert.assertEquals("business-version", record.getStatement().getTask().getTaskVersion());
        Assert.assertEquals("a", record.getPresentation().get().getDirectColumns().get(0).getObject().getDisplayName());
        Assert.assertEquals("ods.orders", record.getPresentation().get().getPhysicalColumns().get(0).getObject().getDisplayName());
        Assert.assertThrows(UnsupportedOperationException.class,
                () -> record.getPresentation().get().getDirectColumns().clear());
    }

    @Test
    public void syntaxTemplateCacheKeysSeparateSourcesAndDefinitionContexts() {
        MetadataReadContext one = new MetadataReadContext("one", "cluster", Optional.empty(), Collections.emptyMap());
        MetadataReadContext two = new MetadataReadContext("two", "cluster", Optional.empty(), Collections.emptyMap());
        ViewTemplateKey a = new ViewTemplateKey(new DefinitionId("view"), "digest", "dialect", "ctx", one);
        Assert.assertEquals(a, new ViewTemplateKey(new DefinitionId("view"), "digest", "dialect", "ctx", one));
        Assert.assertNotEquals(a, new ViewTemplateKey(new DefinitionId("view"), "digest", "dialect", "ctx", two));
        Assert.assertNotEquals(a, new ViewTemplateKey(new DefinitionId("view"), "digest", "dialect", "other", one));
    }

    @Test
    public void badFunctionIndexesAndMissingExtractedExpressionCannotBeClaims() {
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new FunctionOutputDependency(0, Collections.singleton(-1), false, false));
        Assert.assertThrows(IllegalArgumentException.class, () -> new RenderedExpression(Optional.empty(), true));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new ColumnAddress(new QualifiedName(Collections.singletonList("table")), Collections.emptyList()));
    }

    private JoinFact join(Optional<NodeId> predicate, JoinConditionSource source) {
        return new JoinFact(1, new QueryBlockId("q"), "root", JoinKind.INNER, predicate,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), DiagnosticBag.empty(), source);
    }
}
