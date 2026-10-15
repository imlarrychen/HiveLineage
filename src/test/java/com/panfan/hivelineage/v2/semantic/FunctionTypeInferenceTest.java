package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.expression.ResolvedFunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class FunctionTypeInferenceTest {
    @Test
    public void overloadsUseTheActualSemanticVersionAndPreferExactTypes() {
        ResolvedFunctionSymbol symbol = new ResolvedFunctionSymbol(name("convert"), FunctionSymbol.FunctionKind.SCALAR, "extension.convert");
        FunctionDescriptor descriptor = new FunctionDescriptor(symbol, Arrays.asList(
            signature(TypeRef.Kind.DECIMAL, TypeRef.Kind.DATE),
            signature(TypeRef.Kind.INTEGER, TypeRef.Kind.STRING)), Collections.<FunctionOutputDependency>emptyList(),
            Collections.<String>emptyList(), false);
        FunctionCatalog functions = (name, version) -> "version-2".equals(version) ? Optional.of(descriptor) : Optional.empty();
        Expressions.Call call = call(symbol, literal(TypeRef.Kind.INTEGER));
        assertEquals(TypeRef.Kind.STRING, new DefaultTypeAnalyzer().analyze(call, functions, "version-2").getType().getKind());
        assertEquals(TypeRef.Kind.UNKNOWN, new DefaultTypeAnalyzer().analyze(call, functions, "version-1").getType().getKind());
    }

    @Test
    public void variadicSignaturesAndUnknownArgumentsRemainConservative() {
        ResolvedFunctionSymbol symbol = new ResolvedFunctionSymbol(name("join_values"), FunctionSymbol.FunctionKind.SCALAR, "extension.join");
        FunctionSignature variadic = new FunctionSignature(Arrays.asList(type(TypeRef.Kind.STRING)), true,
            Arrays.asList(type(TypeRef.Kind.DATE)));
        FunctionDescriptor descriptor = new FunctionDescriptor(symbol, Arrays.asList(variadic),
            Collections.<FunctionOutputDependency>emptyList(), Collections.<String>emptyList(), false);
        FunctionCatalog functions = (name, version) -> Optional.of(descriptor);
        assertEquals(TypeRef.Kind.DATE, new DefaultTypeAnalyzer().analyze(call(symbol,
            literal(TypeRef.Kind.STRING), literal(TypeRef.Kind.STRING)), functions).getType().getKind());
        assertEquals(TypeRef.Kind.UNKNOWN, new DefaultTypeAnalyzer().analyze(call(symbol,
            literal(TypeRef.Kind.INTEGER)), functions).getType().getKind());
    }

    @Test
    public void functionNamesCannotImpersonateOperatorTypeRules() {
        ResolvedFunctionSymbol symbol = new ResolvedFunctionSymbol(name("greater_customer"), FunctionSymbol.FunctionKind.SCALAR, "extension.test");
        FunctionDescriptor descriptor = new FunctionDescriptor(symbol, Arrays.asList(signature(TypeRef.Kind.INTEGER, TypeRef.Kind.STRING)),
            Collections.<FunctionOutputDependency>emptyList(), Collections.<String>emptyList(), false);
        assertEquals(TypeRef.Kind.STRING, new DefaultTypeAnalyzer().analyze(call(symbol, literal(TypeRef.Kind.INTEGER)),
            (name, version) -> Optional.of(descriptor)).getType().getKind());
    }

    @Test
    public void ambiguousUnknownArgumentsDoNotChooseAnArbitraryOverload() {
        ResolvedFunctionSymbol symbol = new ResolvedFunctionSymbol(name("convert"), FunctionSymbol.FunctionKind.SCALAR, "extension.convert");
        FunctionDescriptor descriptor = new FunctionDescriptor(symbol, Arrays.asList(signature(TypeRef.Kind.STRING, TypeRef.Kind.DATE),
            signature(TypeRef.Kind.INTEGER, TypeRef.Kind.STRING)), Collections.<FunctionOutputDependency>emptyList(),
            Collections.<String>emptyList(), false);
        assertEquals(TypeRef.Kind.UNKNOWN, new DefaultTypeAnalyzer().analyze(call(symbol,
            new Expressions.UnknownExpression(new NodeId("argument"), TypeRef.unknown(), null,
                Collections.<BoundExpression>emptyList(), "UNKNOWN")), (name, version) -> Optional.of(descriptor)).getType().getKind());
    }

    @Test
    public void negatedRangeOperatorMustBeBoolean() {
        ResolvedFunctionSymbol operator = new ResolvedFunctionSymbol(name("not_between"),
            FunctionSymbol.FunctionKind.OPERATOR, "operator.not_between");
        assertEquals(TypeRef.Kind.BOOLEAN, new DefaultTypeAnalyzer().analyze(call(operator,
            literal(TypeRef.Kind.INTEGER), literal(TypeRef.Kind.INTEGER), literal(TypeRef.Kind.INTEGER)),
            new DefaultFunctionCatalog()).getType().getKind());
    }

    @Test
    public void negatedMembershipOperatorMustBeBoolean() {
        ResolvedFunctionSymbol operator = new ResolvedFunctionSymbol(name("not_in"),
            FunctionSymbol.FunctionKind.OPERATOR, "operator.not_in");
        assertEquals(TypeRef.Kind.BOOLEAN, new DefaultTypeAnalyzer().analyze(call(operator,
            literal(TypeRef.Kind.INTEGER), literal(TypeRef.Kind.INTEGER)), new DefaultFunctionCatalog()).getType().getKind());
    }

    private FunctionSignature signature(TypeRef.Kind input, TypeRef.Kind result) {
        return new FunctionSignature(Arrays.asList(type(input)), false, Arrays.asList(type(result)));
    }

    private Expressions.Call call(ResolvedFunctionSymbol symbol, BoundExpression... arguments) {
        return new Expressions.Call(new NodeId("call"), TypeRef.unknown(), null, symbol, Arrays.asList(arguments), false);
    }

    private BoundExpression literal(TypeRef.Kind kind) {
        Expressions.LiteralValue.Kind literal = Expressions.LiteralValue.Kind.valueOf(kind.name());
        return new Expressions.Literal(new NodeId("argument"), type(kind), null,
            new Expressions.LiteralValue(literal, kind == TypeRef.Kind.STRING ? "text" : "1"));
    }

    private TypeRef type(TypeRef.Kind kind) {
        return SemanticTypes.type(kind);
    }
    private QualifiedName name(String value) {
        return new QualifiedName(Arrays.asList(value));
    }
}
