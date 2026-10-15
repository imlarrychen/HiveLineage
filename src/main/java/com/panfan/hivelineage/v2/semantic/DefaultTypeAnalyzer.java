package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.expression.ResolvedFunctionSymbol;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Structural inference consumes declared overloads; unknown functions stay unknown.
 */
public final class DefaultTypeAnalyzer implements TypeAnalyzer {
    @Override
    public TypeAnalysisResult analyze(BoundExpression expression, FunctionCatalog functions) {
        return analyze(expression, functions, "");
    }

    @Override
    public TypeAnalysisResult analyze(BoundExpression expression, FunctionCatalog functions, String semanticVersion) {
        TypeRef type = expression.getType();
        if (expression instanceof Expressions.Cast) {
            type = ((Expressions.Cast) expression).getTargetType();
        } else if (expression instanceof Expressions.Case) {
            Expressions.Case cases = (Expressions.Case) expression;
            List<TypeRef> branches = new ArrayList<>();
            for (Expressions.WhenThen branch : cases.getBranches()) {
                branches.add(analyze(branch.getValue(), functions, semanticVersion).getType());
            }
            if (cases.getElseValue().isPresent()) {
                branches.add(analyze(cases.getElseValue().get(), functions, semanticVersion).getType());
            }
            type = SemanticTypes.common(branches);
        } else if (expression instanceof Expressions.Call) {
            type = call((Expressions.Call) expression, functions, semanticVersion);
        }
        return new TypeAnalysisResult(type, type.getKind() != TypeRef.Kind.UNKNOWN, DiagnosticBag.empty());
    }

    private TypeRef call(Expressions.Call call, FunctionCatalog functions, String semanticVersion) {
        List<TypeRef> arguments = new ArrayList<>();
        for (BoundExpression argument : call.getArguments()) {
            arguments.add(analyze(argument, functions, semanticVersion).getType());
        }
        FunctionSymbol symbol = call.getFunction();
        String name = SemanticNames.normalize(symbol.getName().getDisplayName());
        if (symbol.getKind() == FunctionSymbol.FunctionKind.OPERATOR) {
            return OperatorTypeRules.infer(name, arguments);
        }
        Optional<FunctionDescriptor> descriptor = functions.find(symbol.getName(), semanticVersion);
        if (!descriptor.isPresent() || descriptor.get().isOpaqueBehavior()) {
            return TypeRef.unknown();
        }
        List<TypeRef> results = new ArrayList<>();
        int best = -1;
        for (FunctionSignature signature : descriptor.get().getOverloads()) {
            int score = match(signature, arguments);
            if (score < 0 || signature.getResultTypes().size() != 1) {
                continue;
            }
            if (score > best) {
                results.clear();
                best = score;
            }
            if (score == best) {
                results.add(signature.getResultTypes().get(0));
            }
        }
        TypeRef declared = results.isEmpty() ? TypeRef.unknown() : commonResults(results);
        if (declared.getKind() != TypeRef.Kind.UNKNOWN || best < 0) {
            return declared;
        }
        // Legacy built-ins with argument-dependent results have an intentionally generic signature.
        // Extensions without a declared result never inherit a guessed argument type.
        if (symbol instanceof ResolvedFunctionSymbol && ((ResolvedFunctionSymbol) symbol).getRuleKey().startsWith("hive.")) {
            return arguments.isEmpty() ? TypeRef.unknown() : SemanticTypes.common(arguments);
        }
        return TypeRef.unknown();
    }

    private int match(FunctionSignature signature, List<TypeRef> actual) {
        List<TypeRef> expected = signature.getArguments();
        if (!signature.isVariadic() && actual.size() != expected.size()
                || signature.isVariadic() && !expected.isEmpty() && actual.size() < expected.size() - 1) {
            return -1;
        }
        int score = 0;
        for (int index = 0; index < actual.size(); index++) {
            TypeRef target = expected.isEmpty() ? TypeRef.unknown() : expected.get(Math.min(index, expected.size() - 1));
            TypeRef input = actual.get(index);
            int argumentScore = argumentMatch(target, input);
            if (argumentScore < 0) {
                return -1;
            }
            score += argumentScore;
        }
        return score;
    }

    private int argumentMatch(TypeRef target, TypeRef input) {
        if (target.getKind() == TypeRef.Kind.UNKNOWN || input.getKind() == TypeRef.Kind.UNKNOWN
                || input.getKind() == TypeRef.Kind.NULL) {
            return 0;
        }
        if (target.getKind() != input.getKind()) {
            return target.getKind() == TypeRef.Kind.DECIMAL && input.getKind() == TypeRef.Kind.INTEGER ? 1 : -1;
        }
        if (!target.getArguments().isEmpty() && target.getArguments().size() != input.getArguments().size()
                || !target.getFields().isEmpty() && target.getFields().size() != input.getFields().size()) {
            return -1;
        }
        int score = 2;
        for (int index = 0; index < target.getArguments().size(); index++) {
            int nested = argumentMatch(target.getArguments().get(index), input.getArguments().get(index));
            if (nested < 0) {
                return -1;
            }
            score += nested;
        }
        for (int index = 0; index < target.getFields().size(); index++) {
            TypeRef.StructField expected = target.getFields().get(index);
            TypeRef.StructField actual = input.getFields().get(index);
            int nested = argumentMatch(expected.getType(), actual.getType());
            if (!expected.getName().equalsIgnoreCase(actual.getName()) || nested < 0) {
                return -1;
            }
            score += nested;
        }
        return score;
    }

    private TypeRef commonResults(List<TypeRef> results) {
        TypeRef first = results.get(0);
        for (TypeRef result : results) {
            if (first.getKind() == result.getKind() && !sameShape(first, result)) {
                return TypeRef.unknown();
            }
        }
        return SemanticTypes.common(results);
    }

    private boolean sameShape(TypeRef left, TypeRef right) {
        if (left.getKind() != right.getKind() || left.getArguments().size() != right.getArguments().size()
                || left.getFields().size() != right.getFields().size()) {
            return false;
        }
        for (int index = 0; index < left.getArguments().size(); index++) {
            if (!sameShape(left.getArguments().get(index), right.getArguments().get(index))) {
                return false;
            }
        }
        for (int index = 0; index < left.getFields().size(); index++) {
            TypeRef.StructField a = left.getFields().get(index);
            TypeRef.StructField b = right.getFields().get(index);
            if (!a.getName().equalsIgnoreCase(b.getName()) || !sameShape(a.getType(), b.getType())) {
                return false;
            }
        }
        return true;
    }

}
