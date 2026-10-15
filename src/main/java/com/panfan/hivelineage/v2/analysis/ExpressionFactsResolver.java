package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.CorrelatedQualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.ResolvedFunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/** Recursive bound-expression algebra, independent of SQL text, frontends and catalogs. */
final class ExpressionFactsResolver {
    interface Upstream {
        RelationFacts relation(NodeId id);
        ExpressionFacts slot(SlotId id);
    }

    private final PureIrIndex index;
    private final Upstream upstream;
    private final FactSupport support;
    private final LineageRuleRegistry rules;

    ExpressionFactsResolver(PureIrIndex index, Upstream upstream, FactSupport support, LineageRuleRegistry rules) {
        this.index = index;
        this.upstream = upstream;
        this.support = support;
        this.rules = rules;
    }

    ExpressionFacts resolve(BoundExpression expression, Map<SlotId, ExpressionFacts> inputSlots, List<Dependency> inputRows) {
        return new Evaluation(inputSlots, inputRows).resolve(expression);
    }

    /** Inputs stay read-only for this synchronous evaluation; a new invocation always starts a new memo. */
    private final class Evaluation {
        private final Map<SlotId, ExpressionFacts> inputSlots;
        private final List<Dependency> inputRows;
        private final Map<BoundExpression, ExpressionFacts> memo = new IdentityHashMap<BoundExpression, ExpressionFacts>();

        private Evaluation(Map<SlotId, ExpressionFacts> slots, List<Dependency> rows) {
            this.inputSlots = Collections.unmodifiableMap(slots);
            this.inputRows = Collections.unmodifiableList(rows);
        }

        private ExpressionFacts resolve(BoundExpression expression) {
            ExpressionFacts known = memo.get(expression);
            if (known == null) {
                known = resolveUncached(expression);
                memo.put(expression, known);
            }
            return known;
        }

        private ExpressionFacts resolveUncached(BoundExpression expression) {
            Optional<ExpressionFacts> extension = extension(expression);
            if (extension.isPresent()) {
                return extension.get();
            }
            SlotEndpoint target = new SlotEndpoint(new SlotId("expression:" + expression.getId().getValue()));
            List<Dependency> dependencies = new ArrayList<Dependency>();
            if (reference(expression, target, dependencies)) {
                return facts(expression, dependencies);
            }
            if (expression instanceof Expressions.Case) {
                choice(expression, target, dependencies);
            } else if (expression instanceof Expressions.Call) {
                call(expression, target, dependencies);
            } else if (expression instanceof Expressions.FieldAccess) {
                Expressions.FieldAccess access = (Expressions.FieldAccess) expression;
                List<Dependency> source = resolve(access.getExpression()).getDependencies();
                dependencies.addAll(path(source, Collections.singletonList(access.getField().getDisplayName()), expression, target));
            } else if (expression instanceof Expressions.Subscript) {
                Expressions.Subscript subscript = (Expressions.Subscript) expression;
                merge(subscript.getExpression(), expression, target, "subscript.value", null, dependencies);
                merge(subscript.getIndex(), expression, target, "subscript.index", DependencyRole.CONDITION, dependencies);
            } else if (!subquery(expression, target, dependencies)) {
                for (BoundExpression child : IrTraversal.children(expression)) {
                    merge(child, expression, target, "unknown.known-child", null, dependencies);
                }
                unknown(expression instanceof Expressions.UnknownExpression ? ((Expressions.UnknownExpression) expression).getDiagnosticCode()
                        : "UNSUPPORTED_IR_EXPRESSION", "Expression has unmodeled behavior", expression, target, dependencies);
            }
            return facts(expression, dependencies);
        }

        private ExpressionFacts facts(BoundExpression expression, List<Dependency> dependencies) {
            List<Dependency> distinct = FactSupport.distinct(dependencies);
            return new ExpressionFacts(expression.getId(), distinct, FactSupport.quality(distinct), FactSupport.diagnostics(distinct));
        }

        private Optional<ExpressionFacts> extension(BoundExpression expression) {
            RuleKey key = LineageRuleIdentity.expression(expression);
            if (expression instanceof Expressions.Call && ((Expressions.Call) expression).getFunction() instanceof ResolvedFunctionSymbol) {
                key = new RuleKey(LineageRuleIdentity.FUNCTION_NAMESPACE, ((ResolvedFunctionSymbol) ((Expressions.Call) expression).getFunction()).getRuleKey(), LineageRuleIdentity.VERSION);
            }
            Optional<ExpressionLineageRule> extension = rules.expressionRule(key);
            if (extension.isPresent()) {
                List<ExpressionFacts> arguments = new ArrayList<ExpressionFacts>();
                for (BoundExpression child : IrTraversal.children(expression)) {
                    arguments.add(resolve(child));
                }
                ExpressionFacts result = extension.get().analyze(expression, arguments, new LineageRuleContext(LineageRuleIdentity.VERSION, inputSlots, support));
                support.capture(result.getDependencies());
                return Optional.of(result);
            }
            return Optional.empty();
        }

        private boolean reference(BoundExpression expression, SlotEndpoint target, List<Dependency> dependencies) {
            if (expression instanceof Expressions.Literal) {
                Expressions.LiteralValue literal = ((Expressions.Literal) expression).getValue();
                dependencies.add(support.dependency(new ConstantEndpoint(literal.getKind() + ":" + literal.getValue().orElse("null")),
                        target, DependencyRole.VALUE, DependencyPrecision.STRUCTURAL, expression.getId(), "literal", Collections.emptyList(), expression.getSourceRef()));
            } else if (expression instanceof Expressions.SlotRef) {
                Expressions.SlotRef ref = (Expressions.SlotRef) expression;
                dependencies.addAll(slot(ref.getSlotId(), ref.getFieldPath(), expression, target, inputSlots));
            } else if (expression instanceof Expressions.CorrelatedSlotRef) {
                Expressions.CorrelatedSlotRef ref = (Expressions.CorrelatedSlotRef) expression;
                dependencies.addAll(slot(ref.getSlotId(), ref.getFieldPath(), expression, target, inputSlots));
            } else if (expression instanceof QualifiedFieldHandle) {
                QualifiedFieldHandle field = (QualifiedFieldHandle) expression;
                physical(field.getOwner(), field.getFieldIdentity().getId(), field.getFieldPath(),
                        field.getExistence() == QualifiedFieldHandle.ExistenceState.VERIFIED,
                        field.getExistence() == QualifiedFieldHandle.ExistenceState.UNKNOWN, expression, target, dependencies);
            } else if (expression instanceof CorrelatedQualifiedFieldHandle) {
                CorrelatedQualifiedFieldHandle field = (CorrelatedQualifiedFieldHandle) expression;
                physical(field.getOwner(), field.getFieldIdentity().getId(), field.getFieldPath(),
                        field.getExistence() == CorrelatedQualifiedFieldHandle.ExistenceState.VERIFIED,
                        field.getExistence() == CorrelatedQualifiedFieldHandle.ExistenceState.UNKNOWN, expression, target, dependencies);
            } else if (expression instanceof Expressions.UnknownReference) {
                Expressions.UnknownReference unknown = (Expressions.UnknownReference) expression;
                unknown(unknown.getDiagnosticCode(), "Reference is explicitly unresolved", expression, target, dependencies);
            } else {
                return false;
            }
            return true;
        }

        private boolean subquery(BoundExpression expression, SlotEndpoint target, List<Dependency> dependencies) {
            if (expression instanceof Expressions.NotRequestedExpression) {
                for (NodeId root : ((Expressions.NotRequestedExpression) expression).getKnownQueryRoots()) {
                    dependencies.addAll(support.retarget(upstream.relation(root).getRowSetFacts(), target,
                            expression.getId(), "unrequested.subquery-objects", null, expression.getSourceRef()));
                }
            } else if (expression instanceof Expressions.ScalarSubquery) {
                Expressions.ScalarSubquery subquery = (Expressions.ScalarSubquery) expression;
                RelationFacts facts = upstream.relation(subquery.getQueryRoot());
                dependencies.addAll(support.retarget(facts.getOutputFacts().get(subquery.getResultSlot()).getDependencies(), target,
                        expression.getId(), "subquery.scalar", null, expression.getSourceRef()));
                dependencies.addAll(support.retarget(facts.getRowSetFacts(), target, expression.getId(), "subquery.rows", DependencyRole.ROWSET, expression.getSourceRef()));
                if (subquery.getCardinality() == Expressions.ScalarCardinality.UNKNOWN) {
                    unknown("SCALAR_CARDINALITY_UNKNOWN", "Scalar subquery cardinality was not established", expression, target, dependencies);
                }
            } else if (expression instanceof Expressions.ExistsSubquery) {
                RelationFacts facts = upstream.relation(((Expressions.ExistsSubquery) expression).getQueryRoot());
                dependencies.addAll(support.retarget(facts.getRowSetFacts(), target, expression.getId(), "subquery.exists", DependencyRole.ROWSET, expression.getSourceRef()));
            } else if (expression instanceof Expressions.InSubquery) {
                Expressions.InSubquery subquery = (Expressions.InSubquery) expression;
                merge(subquery.getValue(), expression, target, "subquery.in-left", null, dependencies);
                RelationFacts facts = upstream.relation(subquery.getQueryRoot());
                dependencies.addAll(support.retarget(facts.getOutputFacts().get(subquery.getResultSlot()).getDependencies(), target,
                        expression.getId(), "subquery.in-right", null, expression.getSourceRef()));
                dependencies.addAll(support.retarget(facts.getRowSetFacts(), target, expression.getId(), "subquery.in-rows", DependencyRole.ROWSET, expression.getSourceRef()));
            } else {
                return false;
            }
            return true;
        }

        private void choice(BoundExpression expression, SlotEndpoint target, List<Dependency> dependencies) {
            Expressions.Case value = (Expressions.Case) expression;
            for (Expressions.WhenThen branch : value.getBranches()) {
                merge(branch.getCondition(), expression, target, "case.condition", DependencyRole.CONDITION, dependencies);
                merge(branch.getValue(), expression, target, "case.value", null, dependencies);
            }
            if (value.getElseValue().isPresent()) {
                merge(value.getElseValue().get(), expression, target, "case.else", null, dependencies);
            } else {
                dependencies.add(support.dependency(new ConstantEndpoint("NULL:null"), target, DependencyRole.VALUE,
                        DependencyPrecision.STRUCTURAL, expression.getId(), "case.implicit-null", Collections.emptyList(), expression.getSourceRef()));
            }
        }

        private void call(BoundExpression expression, SlotEndpoint target, List<Dependency> dependencies) {
            Expressions.Call call = (Expressions.Call) expression;
            for (BoundExpression argument : call.getArguments()) {
                merge(argument, expression, target, "call.argument", null, dependencies);
            }
            if (call.getFunction().getKind() == FunctionSymbol.FunctionKind.AGGREGATE
                    || call.getFunction().getKind() == FunctionSymbol.FunctionKind.WINDOW) {
                dependencies.addAll(support.retarget(inputRows, target, expression.getId(), "call.input-rows", DependencyRole.ROWSET, expression.getSourceRef()));
            }
            if (call.getFunction().getKind() == FunctionSymbol.FunctionKind.OPAQUE) {
                unknown("OPAQUE_FUNCTION", "Function behavior may use additional sources", expression, target, dependencies);
            }
        }

        private void merge(BoundExpression child, BoundExpression operation, SlotEndpoint target, String rule, DependencyRole role, List<Dependency> dependencies) {
            dependencies.addAll(support.retarget(resolve(child).getDependencies(), target, operation.getId(), rule, role, operation.getSourceRef()));
        }

    }

    private void physical(com.panfan.hivelineage.v2.ir.identity.RelationInstanceId owner,
            com.panfan.hivelineage.v2.ir.identity.FieldId field, List<String> path, boolean verified, boolean existenceUnknown,
            BoundExpression expression, SlotEndpoint target, List<Dependency> dependencies) {
        if (existenceUnknown) {
            unknown("FIELD_EXISTENCE_UNKNOWN", "Physical field state is uncertain", expression, target, dependencies);
            return;
        }
        if (index.object(owner).isPresent()) {
            dependencies.add(support.dependency(new ColumnEndpoint(index.object(owner).get(), field, path), target, DependencyRole.VALUE,
                    verified ? DependencyPrecision.STRUCTURAL : DependencyPrecision.CONSERVATIVE,
                    expression.getId(), verified ? "field.verified" : "field.unverified", Collections.emptyList(), expression.getSourceRef()));
        } else {
            unknown("PHYSICAL_OWNER_UNKNOWN", "Field owner has no bound object", expression, target, dependencies);
        }
    }

    private List<Dependency> slot(SlotId slot, List<String> path, BoundExpression expression, SlotEndpoint target,
            Map<SlotId, ExpressionFacts> inputSlots) {
        ExpressionFacts facts = inputSlots.containsKey(slot) ? inputSlots.get(slot) : upstream.slot(slot);
        return path(facts.getDependencies(), path, expression, target);
    }

    private List<Dependency> path(List<Dependency> source, List<String> suffix, BoundExpression expression, SlotEndpoint target) {
        List<Dependency> dependencies = new ArrayList<Dependency>();
        for (Dependency dependency : source) {
            LineageEndpoint origin = dependency.getSource();
            if (origin instanceof ColumnEndpoint && !suffix.isEmpty()) {
                ColumnEndpoint column = (ColumnEndpoint) origin;
                List<String> path = new ArrayList<String>(column.getFieldPath());
                path.addAll(suffix);
                origin = new ColumnEndpoint(column.getObjectId(), column.getFieldId(), path);
            }
            String rule = dependency.getEvidence().getRuleId().startsWith("window.") ? dependency.getEvidence().getRuleId() : "slot.reference";
            dependencies.add(support.dependency(origin, target, dependency.getRole(), dependency.getPrecision(), expression.getId(), rule,
                    Collections.singletonList(dependency.getEvidence().getId()), expression.getSourceRef()));
        }
        return dependencies;
    }

    private void unknown(String code, String message, BoundExpression expression, SlotEndpoint target, List<Dependency> dependencies) {
        DiagnosticBag bag = FactSupport.diagnostic(code, message, expression.getSourceRef().orElse(null));
        dependencies.add(support.dependency(new UnknownEndpoint(bag), target, DependencyRole.VALUE, DependencyPrecision.UNKNOWN,
                expression.getId(), "unknown", Collections.emptyList(), expression.getSourceRef()));
    }
}
