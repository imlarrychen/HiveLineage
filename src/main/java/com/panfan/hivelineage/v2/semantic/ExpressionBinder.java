package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.CorrelatedQualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.expression.OpaqueFunctionSymbol;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.ResolvedFunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.QueryResult;
import com.panfan.hivelineage.v2.syntax.BetweenSyntax;
import com.panfan.hivelineage.v2.syntax.BinarySyntax;
import com.panfan.hivelineage.v2.syntax.CallSyntax;
import com.panfan.hivelineage.v2.syntax.CaseBranchSyntax;
import com.panfan.hivelineage.v2.syntax.CaseSyntax;
import com.panfan.hivelineage.v2.syntax.CastSyntax;
import com.panfan.hivelineage.v2.syntax.FieldAccessSyntax;
import com.panfan.hivelineage.v2.syntax.FrameBoundarySyntax;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.InListSyntax;
import com.panfan.hivelineage.v2.syntax.LiteralKind;
import com.panfan.hivelineage.v2.syntax.LiteralSyntax;
import com.panfan.hivelineage.v2.syntax.NameRefSyntax;
import com.panfan.hivelineage.v2.syntax.QuantifierSyntax;
import com.panfan.hivelineage.v2.syntax.SortItemSyntax;
import com.panfan.hivelineage.v2.syntax.StarSyntax;
import com.panfan.hivelineage.v2.syntax.SubqueryExpressionSyntax;
import com.panfan.hivelineage.v2.syntax.SubqueryKindSyntax;
import com.panfan.hivelineage.v2.syntax.SubscriptSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxExpression;
import com.panfan.hivelineage.v2.syntax.SyntaxName;
import com.panfan.hivelineage.v2.syntax.UnarySyntax;
import com.panfan.hivelineage.v2.syntax.WindowCallSyntax;
import com.panfan.hivelineage.v2.syntax.WindowFrameSyntax;
import com.panfan.hivelineage.v2.syntax.WindowSpecificationSyntax;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class ExpressionBinder {
    private final BindingWork work;
    private final ReferenceBinder references;

    ExpressionBinder(BindingWork work) {
        this.work = work;
        this.references = new ReferenceBinder(work);
    }

    BoundExpression bind(SyntaxExpression syntax, ReferenceScope scope) {
        BoundExpression existing = scope.expressions.get(syntax.getNodeId());
        if (existing != null) {
            return existing;
        }
        BoundExpression expression = bindUncached(syntax, scope);
        scope.expressions.put(syntax.getNodeId(), expression);
        return expression;
    }

    private BoundExpression bindUncached(SyntaxExpression syntax, ReferenceScope scope) {
        NodeId id = work.id(syntax, scope, "expression");
        SourceRef source = syntax.getSourceRef();
        if (syntax instanceof NameRefSyntax) {
            return references.bind((NameRefSyntax) syntax, scope);
        }
        if (syntax instanceof LiteralSyntax) {
            LiteralSyntax literal = (LiteralSyntax) syntax;
            Expressions.LiteralValue.Kind kind;
            if (literal.getLiteralKind() == LiteralKind.NUMBER) {
                kind = literal.getDecodedValue() != null && literal.getDecodedValue().contains(".")
                    ? Expressions.LiteralValue.Kind.DECIMAL : Expressions.LiteralValue.Kind.INTEGER;
            } else if (literal.getLiteralKind() == LiteralKind.INTERVAL) {
                kind = Expressions.LiteralValue.Kind.STRING;
            } else {
                kind = Expressions.LiteralValue.Kind.valueOf(literal.getLiteralKind().name());
            }
            String value = kind == Expressions.LiteralValue.Kind.NULL ? null : literal.getDecodedValue();
            if (value == null && kind != Expressions.LiteralValue.Kind.NULL) {
                value = literal.getLexeme();
            }
            return new Expressions.Literal(id, SemanticTypes.type(TypeRef.Kind.valueOf(kind.name())), source,
                new Expressions.LiteralValue(kind, value));
        }
        if (syntax instanceof BinarySyntax) {
            BinarySyntax binary = (BinarySyntax) syntax;
            return operator(id, source, binary.getOperator().name().toLowerCase(java.util.Locale.ROOT),
                Arrays.asList(bind(binary.getLeft(), scope), bind(binary.getRight(), scope)));
        }
        if (syntax instanceof UnarySyntax) {
            UnarySyntax unary = (UnarySyntax) syntax;
            return operator(id, source, unary.getOperator().name().toLowerCase(java.util.Locale.ROOT),
                Collections.singletonList(bind(unary.getOperand(), scope)));
        }
        if (syntax instanceof CallSyntax) {
            return call((CallSyntax) syntax, scope);
        }
        if (syntax instanceof CastSyntax) {
            CastSyntax cast = (CastSyntax) syntax;
            TypeRef type = SemanticTypes.declared(cast.getTargetType());
            return new Expressions.Cast(id, type, source, bind(cast.getExpression(), scope), type);
        }
        if (syntax instanceof CaseSyntax) {
            CaseSyntax cases = (CaseSyntax) syntax;
            BoundExpression operand = cases.getOperand() == null ? null : bind(cases.getOperand(), scope);
            List<Expressions.WhenThen> branches = new ArrayList<>();
            for (CaseBranchSyntax branch : cases.getBranches()) {
                BoundExpression condition = bind(branch.getWhenExpression(), scope);
                if (operand != null) {
                    condition = operator(new NodeId(condition.getId().getValue() + "/simple-case"), condition.getSourceRef().orElse(null),
                        "equal", Arrays.asList(operand, condition));
                }
                branches.add(new Expressions.WhenThen(condition, bind(branch.getThenExpression(), scope)));
            }
            return new Expressions.Case(id, TypeRef.unknown(), source, branches,
                cases.getElseExpression() == null ? null : bind(cases.getElseExpression(), scope));
        }
        if (syntax instanceof FieldAccessSyntax) {
            FieldAccessSyntax access = (FieldAccessSyntax) syntax;
            if (access.getBase() instanceof NameRefSyntax) {
                List<IdentifierPart> parts = new ArrayList<>(((NameRefSyntax) access.getBase()).getName().getParts());
                parts.add(access.getField());
                return references.bind(new NameRefSyntax(syntax.getNodeId(), source, new SyntaxName(parts)), scope);
            }
            BoundExpression base = bind(access.getBase(), scope);
            FieldIdentity field = new FieldIdentity(id.getValue() + "/field", access.getField().getValue());
            TypeRef type = TypeRef.unknown();
            for (TypeRef.StructField candidate : base.getType().getFields()) {
                if (SemanticNames.normalize(candidate.getName()).equals(SemanticNames.normalize(access.getField().getValue()))) {
                    type = candidate.getType();
                }
            }
            return new Expressions.FieldAccess(id, type, source, base, field);
        }
        if (syntax instanceof SubscriptSyntax) {
            SubscriptSyntax subscript = (SubscriptSyntax) syntax;
            BoundExpression base = bind(subscript.getBase(), scope);
            TypeRef type = base.getType().getArguments().isEmpty() ? TypeRef.unknown()
                : base.getType().getArguments().get(base.getType().getArguments().size() - 1);
            return new Expressions.Subscript(id, type, source, base, bind(subscript.getIndex(), scope));
        }
        if (syntax instanceof InListSyntax) {
            InListSyntax in = (InListSyntax) syntax;
            List<BoundExpression> values = new ArrayList<>();
            values.add(bind(in.getExpression(), scope));
            for (SyntaxExpression value : in.getValues()) {
                values.add(bind(value, scope));
            }
            return operator(id, source, in.isNegated() ? "not_in" : "in", values);
        }
        if (syntax instanceof BetweenSyntax) {
            BetweenSyntax between = (BetweenSyntax) syntax;
            return operator(id, source, between.isNegated() ? "not_between" : "between",
                Arrays.asList(bind(between.getExpression(), scope), bind(between.getLowerBound(), scope),
                    bind(between.getUpperBound(), scope)));
        }
        if (syntax instanceof SubqueryExpressionSyntax) {
            SubqueryExpressionSyntax subquery = (SubqueryExpressionSyntax) syntax;
            String suffix = subquery.getSubqueryKind().name().toLowerCase(java.util.Locale.ROOT);
            String path = scope.displayPath + "." + scope.clauseLocation + "." + suffix + "[" + (++scope.childSequence) + "]";
            BoundRelation relation = new QueryBinder(work).query(subquery.getQuery(), scope,
                subquery.getSubqueryKind() != SubqueryKindSyntax.EXISTS, QueryResult.Role.SUBQUERY, scope.prefix, null, path);
            if (subquery.getSubqueryKind() == SubqueryKindSyntax.EXISTS) {
                return new Expressions.ExistsSubquery(id, SemanticTypes.type(TypeRef.Kind.BOOLEAN), source, relation.getId(), false);
            }
            List<OutputSlot> slots = BindingWork.slots(relation.getOutputSchema());
            if (slots.size() != 1) {
                work.warn("SCALAR_SUBQUERY_WIDTH_UNKNOWN", "Subquery must expose one known output", source);
                return new Expressions.UnknownExpression(id, TypeRef.unknown(), source,
                    Collections.<BoundExpression>singletonList(new Expressions.ExistsSubquery(
                        new NodeId(id.getValue() + "/rows"), SemanticTypes.type(TypeRef.Kind.BOOLEAN), source, relation.getId(), false)),
                    "SCALAR_SUBQUERY_WIDTH_UNKNOWN");
            }
            if (subquery.getSubqueryKind() == SubqueryKindSyntax.IN) {
                return new Expressions.InSubquery(id, SemanticTypes.type(TypeRef.Kind.BOOLEAN), source,
                    bind(subquery.getProbe(), scope), relation.getId(), slots.get(0).getId(), false);
            }
            return new Expressions.ScalarSubquery(id, slots.get(0).getType(), source, relation.getId(),
                slots.get(0).getId(), Expressions.ScalarCardinality.UNKNOWN);
        }
        if (syntax instanceof WindowCallSyntax) {
            return window((WindowCallSyntax) syntax, scope);
        }
        work.warn("UNSUPPORTED_EXPRESSION", "Expression has no declared binding rule", source);
        return new Expressions.UnknownExpression(id, TypeRef.unknown(), source, Collections.<BoundExpression>emptyList(), "UNSUPPORTED_EXPRESSION");
    }

    Expressions.Call call(CallSyntax syntax, ReferenceScope scope) {
        QualifiedName name = SemanticNames.name(syntax.getFunctionName());
        Optional<FunctionDescriptor> descriptor = work.context.getFunctions().find(name, work.context.getDialect().getSemanticRuleVersion());
        FunctionSymbol symbol = descriptor.isPresent() ? descriptor.get().getSymbol() : new OpaqueFunctionSymbol(name, "OPAQUE_FUNCTION");
        List<BoundExpression> arguments = new ArrayList<>();
        for (SyntaxExpression argument : syntax.getArguments().getArguments()) {
            if (argument instanceof StarSyntax) {
                continue;
            }
            arguments.add(bind(argument, scope));
        }
        if (syntax.getArguments().getFilter() != null) {
            arguments.add(bind(syntax.getArguments().getFilter(), scope));
        }
        NodeId id = work.id(syntax, scope, "call");
        Expressions.Call result = new Expressions.Call(id, TypeRef.unknown(), syntax.getSourceRef(), symbol, arguments,
            syntax.getArguments().getQuantifier() == QuantifierSyntax.DISTINCT);
        TypeRef type = work.types.analyze(result, work.context.getFunctions()).getType();
        return new Expressions.Call(id, type, syntax.getSourceRef(), symbol, arguments, result.getDistinct());
    }

    BoundExpression operator(NodeId id, SourceRef source, String name, List<BoundExpression> arguments) {
        FunctionSymbol function = new ResolvedFunctionSymbol(new QualifiedName(Collections.singletonList(name)),
            FunctionSymbol.FunctionKind.OPERATOR, "operator." + name);
        Expressions.Call call = new Expressions.Call(id, TypeRef.unknown(), source, function, arguments, false);
        return new Expressions.Call(id, work.types.analyze(call, work.context.getFunctions()).getType(), source, function, arguments, false);
    }

    List<Relations.OrderKey> orders(List<SortItemSyntax> syntax, ReferenceScope scope) {
        List<Relations.OrderKey> result = new ArrayList<>();
        for (SortItemSyntax sort : syntax) {
            Relations.Direction direction = "DESCENDING".equals(sort.getDirection().name()) || "DESC".equals(sort.getDirection().name())
                ? Relations.Direction.DESCENDING : Relations.Direction.ASCENDING;
            Relations.NullOrder nullOrder;
            try {
                nullOrder = Relations.NullOrder.valueOf(sort.getNullOrdering().name());
            } catch (IllegalArgumentException exception) {
                nullOrder = Relations.NullOrder.DIALECT_DEFAULT;
            }
            result.add(new Relations.OrderKey(bind(sort.getExpression(), scope), direction, nullOrder));
        }
        return result;
    }

    private BoundExpression window(WindowCallSyntax syntax, ReferenceScope scope) {
        Expressions.Call call = call(syntax.getCall(), scope);
        WindowSpecificationSyntax specification = syntax.getWindow();
        if (specification.getBaseWindowName() != null) {
            com.panfan.hivelineage.v2.syntax.NamedWindowSyntax named = scope.windows.get(
                SemanticNames.normalize(specification.getBaseWindowName().getValue()));
            if (named != null) {
                specification = named.getSpecification();
            } else {
                work.warn("UNKNOWN_WINDOW", "Window definition is unavailable", syntax.getSourceRef());
            }
        }
        List<BoundExpression> partitions = new ArrayList<>();
        for (SyntaxExpression partition : specification.getPartitionBy()) {
            partitions.add(bind(partition, scope));
        }
        Relations.WindowFrame frame = null;
        WindowFrameSyntax syntaxFrame = specification.getFrame();
        if (syntaxFrame != null) {
            Relations.FrameBound end = syntaxFrame.getEnd() == null
                ? new Relations.FrameBound(Relations.FrameBoundKind.CURRENT_ROW, null) : boundary(syntaxFrame.getEnd(), scope);
            frame = new Relations.WindowFrame(Relations.FrameUnit.valueOf(syntaxFrame.getUnit().name()),
                boundary(syntaxFrame.getStart(), scope), end);
        }
        SlotId slotId = new SlotId(work.id(syntax, scope, "window-output").getValue());
        scope.windowSlots.add(new OutputSlot(slotId, "_window_" + syntax.getSourceRef().getSpan().getStart(), call.getType(), null));
        scope.windowComputations.add(new Relations.WindowComputation(new OutputAssignment(slotId, call),
            partitions, orders(specification.getOrderBy(), scope), frame));
        return new Expressions.SlotRef(work.id(syntax, scope, "window-reference"), call.getType(), syntax.getSourceRef(),
            slotId, Collections.<String>emptyList());
    }

    private Relations.FrameBound boundary(FrameBoundarySyntax syntax, ReferenceScope scope) {
        return new Relations.FrameBound(Relations.FrameBoundKind.valueOf(syntax.getKind().name()),
            syntax.getOffset() == null ? null : bind(syntax.getOffset(), scope));
    }

    List<RelationInstanceId> owners(BoundExpression expression, ReferenceScope scope) {
        Set<RelationInstanceId> result = new LinkedHashSet<>();
        collectOwners(expression, scope, result);
        return new ArrayList<>(result);
    }

    boolean correlated(BoundExpression expression) {
        if (expression instanceof Expressions.CorrelatedSlotRef || expression instanceof CorrelatedQualifiedFieldHandle) {
            return true;
        }
        for (BoundExpression child : IrTraversal.children(expression)) {
            if (correlated(child)) {
                return true;
            }
        }
        return false;
    }

    private void collectOwners(BoundExpression expression, ReferenceScope scope, Set<RelationInstanceId> owners) {
        if (expression instanceof QualifiedFieldHandle) {
            owners.add(((QualifiedFieldHandle) expression).getOwner());
        } else if (expression instanceof CorrelatedQualifiedFieldHandle) {
            owners.add(((CorrelatedQualifiedFieldHandle) expression).getOwner());
        } else if (expression instanceof Expressions.UnknownReference) {
            ((Expressions.UnknownReference) expression).getDisplayOwner().ifPresent(owners::add);
        } else if (expression instanceof Expressions.SlotRef) {
            SlotId slot = ((Expressions.SlotRef) expression).getSlotId();
            for (SourceBinding source : scope.sources) {
                for (OutputSlot candidate : BindingWork.slots(source.schema)) {
                    if (candidate.getId().equals(slot)) {
                        owners.add(source.instanceId);
                    }
                }
            }
        } else if (expression instanceof Expressions.Call) {
            for (BoundExpression child : ((Expressions.Call) expression).getArguments()) {
                collectOwners(child, scope, owners);
            }
        } else if (expression instanceof Expressions.Cast) {
            collectOwners(((Expressions.Cast) expression).getExpression(), scope, owners);
        } else if (expression instanceof Expressions.Subscript) {
            collectOwners(((Expressions.Subscript) expression).getExpression(), scope, owners);
            collectOwners(((Expressions.Subscript) expression).getIndex(), scope, owners);
        } else if (expression instanceof Expressions.FieldAccess) {
            collectOwners(((Expressions.FieldAccess) expression).getExpression(), scope, owners);
        } else if (expression instanceof Expressions.Case) {
            for (Expressions.WhenThen branch : ((Expressions.Case) expression).getBranches()) {
                collectOwners(branch.getCondition(), scope, owners);
                collectOwners(branch.getValue(), scope, owners);
            }
        }
    }
}
