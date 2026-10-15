package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.AnalysisFixtures;
import com.panfan.hivelineage.v2.analysis.SlotProvenanceIndex;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

/** Structural classification and function discovery have no renderer dependency. */
public class ExpressionStructureContractTest {
    @Test
    public void literalSetClassificationWorksWithoutFormatterOrSourceText() {
        Relations.Values input = values("input", "seed");
        Relations.Project left = literalProject(input, "left", "1");
        Relations.Project right = literalProject(input, "right", "2");
        Relations.SetOp set = new Relations.SetOp(new NodeId("set"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("set.out"))),
                new QueryBlockId("main"), 1, null, Arrays.asList(left.getId(), right.getId()), Relations.SetOperator.UNION, Relations.DuplicatePolicy.ALL,
                Collections.singletonList(new Relations.SetOutputMapping(new SlotId("set.out"), Arrays.asList(
                Relations.BranchSlot.known(new SlotId("left.out")), Relations.BranchSlot.known(new SlotId("right.out"))))));
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Arrays.asList(input, left, right, set), Collections.emptyList());
        SlotProvenanceIndex provenance = new SlotProvenanceIndex(unit);
        ExpressionShapeAnalyzer shapes = new ExpressionShapeAnalyzer(provenance, new BoundNameIndex(unit), object -> false);
        BoundExpression result = AnalysisFixtures.ref("result", "set.out");
        Assert.assertTrue(shapes.hasSetProvenance(result));
        Assert.assertTrue(shapes.literalOnly(result));
        Assert.assertEquals(ColumnDerivationKind.CONSTANT, ColumnDerivationClassifier.classifyShape(shapes, result, Collections.emptyList()));
        Assert.assertTrue(new FunctionUsageCollector(provenance, shapes).functions(result).isEmpty());
    }

    @Test
    public void functionCollectorPreservesDistinctAndOccurrencePoliciesThroughAssignments() {
        BoundExpression first = AnalysisFixtures.call("first", "lower", FunctionSymbol.FunctionKind.SCALAR, AnalysisFixtures.literal("a", "A"));
        BoundExpression second = AnalysisFixtures.call("second", "lower", FunctionSymbol.FunctionKind.SCALAR, AnalysisFixtures.literal("b", "B"));
        BoundExpression expression = AnalysisFixtures.call("concat", "concat", FunctionSymbol.FunctionKind.SCALAR, first, second);
        Relations.Values input = values("input", "seed");
        Relations.Project project = new Relations.Project(new NodeId("project"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("result"))),
                new QueryBlockId("main"), 1, null, input.getId(), Collections.singletonList(new OutputAssignment(new SlotId("result"), expression)));
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Arrays.asList(input, project), Collections.emptyList());
        SlotProvenanceIndex provenance = new SlotProvenanceIndex(unit);
        ExpressionShapeAnalyzer shapes = new ExpressionShapeAnalyzer(provenance, new BoundNameIndex(unit), object -> false);
        FunctionUsageCollector functions = new FunctionUsageCollector(provenance, shapes);
        BoundExpression result = AnalysisFixtures.ref("result-ref", "result");
        Assert.assertTrue(shapes.complex(result));
        Assert.assertFalse(shapes.literalOnly(result));
        Assert.assertEquals(Arrays.asList("concat", "lower"), functions.functions(result));
        Assert.assertEquals(Arrays.asList("concat", "lower", "lower"), functions.functionsWithOccurrences(result));
    }

    @Test
    public void legacyClassifierEntryPointMatchesShapeCoreWithoutRenderingText() {
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Collections.emptyList(), Collections.emptyList());
        BoundNameIndex names = new BoundNameIndex(unit);
        BoundExpressionFormatter formatter = new BoundExpressionFormatter(unit, names, source -> {
            throw new AssertionError("Classification must not render SQL");
        });
        BoundExpression literal = AnalysisFixtures.literal("literal", "1");
        BoundExpression call = AnalysisFixtures.call("call", "abs", FunctionSymbol.FunctionKind.SCALAR, literal);
        for (BoundExpression expression : Arrays.asList(literal, call)) {
            Assert.assertEquals(ColumnDerivationClassifier.classifyShape(formatter.shapes(), expression, Collections.emptyList()),
                    ColumnDerivationClassifier.classify(formatter, expression, Collections.emptyList()));
        }
    }

    private static Relations.Project literalProject(Relations.Values input, String id, String value) {
        return new Relations.Project(new NodeId(id), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output(id + ".out"))),
                new QueryBlockId("main"), 1, null, input.getId(), Collections.singletonList(new OutputAssignment(new SlotId(id + ".out"),
                AnalysisFixtures.literal(id + ".literal", value))));
    }

    private static Relations.Values values(String id, String value) {
        return new Relations.Values(new NodeId(id), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output(id + ".out"))),
                new QueryBlockId("main"), 0, null, Collections.singletonList(Collections.singletonList(AnalysisFixtures.literal(id + ".literal", value))));
    }
}
