package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import java.util.ArrayList;
import java.util.List;

/** Limited structural inference; unknown metadata is retained rather than rejected. */
public final class DefaultTypeAnalyzer implements TypeAnalyzer {
    @Override
    public TypeAnalysisResult analyze(BoundExpression expression, FunctionCatalog functions) {
        TypeRef type = expression.getType();
        if (expression instanceof Expressions.Cast) {
            type = ((Expressions.Cast) expression).getTargetType();
        } else if (expression instanceof Expressions.Case) {
            List<TypeRef> branches = new ArrayList<>();
            for (Expressions.WhenThen branch : ((Expressions.Case) expression).getBranches()) {
                branches.add(analyze(branch.getValue(), functions).getType());
            }
            if (((Expressions.Case) expression).getElseValue().isPresent()) {
                branches.add(analyze(((Expressions.Case) expression).getElseValue().get(), functions).getType());
            }
            type = SemanticTypes.common(branches);
        } else if (expression instanceof Expressions.Call) {
            Expressions.Call call = (Expressions.Call) expression;
            String name = SemanticNames.normalize(call.getFunction().getName().getDisplayName());
            if ("count".equals(name) || "row_number".equals(name) || "rank".equals(name)
                || "dense_rank".equals(name) || "size".equals(name) || "length".equals(name)) {
                type = SemanticTypes.type(TypeRef.Kind.INTEGER);
            } else if ("avg".equals(name) || "percent_rank".equals(name) || "cume_dist".equals(name)) {
                type = SemanticTypes.type(TypeRef.Kind.DECIMAL);
            } else if ("and".equals(name) || "or".equals(name) || "not".equals(name)
                || name.contains("equal") || name.contains("less") || name.contains("greater")
                || "in".equals(name) || "between".equals(name) || "like".equals(name) || "rlike".equals(name)
                || "is_null".equals(name) || "is_not_null".equals(name)) {
                type = SemanticTypes.type(TypeRef.Kind.BOOLEAN);
            } else if ("upper".equals(name) || "lower".equals(name) || "concat".equals(name)
                || "concat_ws".equals(name) || "substring".equals(name) || "substr".equals(name)
                || "regexp_replace".equals(name) || "date_format".equals(name)) {
                type = SemanticTypes.type(TypeRef.Kind.STRING);
            } else if (call.getFunction().getKind() == com.panfan.hivelineage.v2.ir.expression.FunctionSymbol.FunctionKind.OPAQUE) {
                type = TypeRef.unknown();
            } else if (!call.getArguments().isEmpty()) {
                List<TypeRef> argumentTypes = new ArrayList<>();
                for (BoundExpression argument : call.getArguments()) {
                    argumentTypes.add(analyze(argument, functions).getType());
                }
                type = SemanticTypes.common(argumentTypes);
            }
        }
        return new TypeAnalysisResult(type, type.getKind() != TypeRef.Kind.UNKNOWN, DiagnosticBag.empty());
    }
}
