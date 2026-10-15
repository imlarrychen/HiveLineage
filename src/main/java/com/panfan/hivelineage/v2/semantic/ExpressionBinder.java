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
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.syntax.BetweenSyntax;
import com.panfan.hivelineage.v2.syntax.BinarySyntax;
import com.panfan.hivelineage.v2.syntax.CallSyntax;
import com.panfan.hivelineage.v2.syntax.CaseSyntax;
import com.panfan.hivelineage.v2.syntax.CastSyntax;
import com.panfan.hivelineage.v2.syntax.FieldAccessSyntax;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.InListSyntax;
import com.panfan.hivelineage.v2.syntax.LiteralKind;
import com.panfan.hivelineage.v2.syntax.LiteralSyntax;
import com.panfan.hivelineage.v2.syntax.NameRefSyntax;
import com.panfan.hivelineage.v2.syntax.QuantifierSyntax;
import com.panfan.hivelineage.v2.syntax.SortItemSyntax;
import com.panfan.hivelineage.v2.syntax.StarSyntax;
import com.panfan.hivelineage.v2.syntax.SubqueryExpressionSyntax;
import com.panfan.hivelineage.v2.syntax.SubscriptSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxExpression;
import com.panfan.hivelineage.v2.syntax.SyntaxName;
import com.panfan.hivelineage.v2.syntax.UnarySyntax;
import com.panfan.hivelineage.v2.syntax.UnknownExpressionSyntax;
import com.panfan.hivelineage.v2.syntax.WindowCallSyntax;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
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
            return literal((LiteralSyntax) syntax, scope);
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
            return new CaseExpressionBinder(work, this).bind((CaseSyntax) syntax, scope);
        }
        if (syntax instanceof FieldAccessSyntax) {
            return fieldAccess((FieldAccessSyntax) syntax, scope);
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
            return new SubqueryExpressionBinder(work, this).bind((SubqueryExpressionSyntax) syntax, scope);
        }
        if (syntax instanceof WindowCallSyntax) {
            return new WindowExpressionBinder(work, this).bind((WindowCallSyntax) syntax, scope);
        }
        return unknown(syntax, scope, id, source);
    }

    private BoundExpression fieldAccess(FieldAccessSyntax access, ReferenceScope scope) {
        NodeId id = work.id(access, scope, "expression");
        SourceRef source = access.getSourceRef();
        if (access.getBase() instanceof NameRefSyntax) {
            List<IdentifierPart> parts = new ArrayList<>(((NameRefSyntax) access.getBase()).getName().getParts());
            parts.add(access.getField());
            return references.bind(new NameRefSyntax(access.getNodeId(), source, new SyntaxName(parts)), scope);
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

    private BoundExpression literal(LiteralSyntax literal, ReferenceScope scope) {
        NodeId id = work.id(literal, scope, "expression");
        SourceRef source = literal.getSourceRef();
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

    private BoundExpression unknown(SyntaxExpression syntax, ReferenceScope scope, NodeId id, SourceRef source) {
        if (syntax instanceof UnknownExpressionSyntax) {
            UnknownExpressionSyntax unknown = (UnknownExpressionSyntax) syntax;
            List<BoundExpression> children = new ArrayList<>();
            for (SyntaxExpression child : unknown.getKnownChildren()) {
                children.add(bind(child, scope));
            }
            work.warn("UNSUPPORTED_EXPRESSION", "Unknown expression: " + unknown.getReason(), source);
            return new Expressions.UnknownExpression(id, TypeRef.unknown(), source, children, unknown.getReason());
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
        TypeRef type = work.analyzeType(result);
        return new Expressions.Call(id, type, syntax.getSourceRef(), symbol, arguments, result.getDistinct());
    }

    BoundExpression operator(NodeId id, SourceRef source, String name, List<BoundExpression> arguments) {
        FunctionSymbol function = new ResolvedFunctionSymbol(new QualifiedName(Collections.singletonList(name)),
                FunctionSymbol.FunctionKind.OPERATOR, "operator." + name);
        Expressions.Call call = new Expressions.Call(id, TypeRef.unknown(), source, function, arguments, false);
        return new Expressions.Call(id, work.analyzeType(call), source, function, arguments, false);
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

    List<RelationInstanceId> owners(BoundExpression expression, ReferenceScope scope) {
        Set<RelationInstanceId> result = new LinkedHashSet<>();
        Map<SlotId, RelationInstanceId> slotOwners = scope.slotOwners();
        Deque<BoundExpression> pending = new ArrayDeque<>();
        Set<BoundExpression> visited = Collections.newSetFromMap(new IdentityHashMap<BoundExpression, Boolean>());
        pending.push(expression);
        while (!pending.isEmpty()) {
            BoundExpression current = pending.pop();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof QualifiedFieldHandle) {
                result.add(((QualifiedFieldHandle) current).getOwner());
            } else if (current instanceof CorrelatedQualifiedFieldHandle) {
                result.add(((CorrelatedQualifiedFieldHandle) current).getOwner());
            } else if (current instanceof Expressions.UnknownReference) {
                ((Expressions.UnknownReference) current).getDisplayOwner().ifPresent(result::add);
            } else if (current instanceof Expressions.SlotRef) {
                RelationInstanceId owner = slotOwners.get(((Expressions.SlotRef) current).getSlotId());
                if (owner != null) {
                    result.add(owner);
                }
            }
            List<BoundExpression> children = IrTraversal.children(current);
            for (int index = children.size() - 1; index >= 0; index--) {
                pending.push(children.get(index));
            }
        }
        return new ArrayList<>(result);
    }

    boolean correlated(BoundExpression expression) {
        Deque<BoundExpression> pending = new ArrayDeque<>();
        Set<BoundExpression> visited = Collections.newSetFromMap(new IdentityHashMap<BoundExpression, Boolean>());
        pending.push(expression);
        while (!pending.isEmpty()) {
            BoundExpression current = pending.pop();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof Expressions.CorrelatedSlotRef || current instanceof CorrelatedQualifiedFieldHandle) {
                return true;
            }
            pending.addAll(IrTraversal.children(current));
        }
        return false;
    }
}
