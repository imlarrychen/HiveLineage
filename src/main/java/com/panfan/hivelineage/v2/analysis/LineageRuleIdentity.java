package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.CorrelatedQualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.ExternalDatasetRelation;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable built-in protocol names; Java refactors must not change persisted rule identities. */
final class LineageRuleIdentity {
    static final String VERSION = "1";
    static final String IR_NAMESPACE = "ir";
    static final String FUNCTION_NAMESPACE = "function";
    private static final Map<Class<?>, String> OPERATIONS = operations();

    private LineageRuleIdentity() {
    }

    static RuleKey ir(String operation) {
        return new RuleKey(IR_NAMESPACE, operation, VERSION);
    }

    static RuleKey relation(BoundRelation relation) {
        return ir(operation(relation.getClass()));
    }

    static RuleKey expression(BoundExpression expression) {
        return ir(operation(expression.getClass()));
    }

    private static String operation(Class<?> type) {
        // Preserve the original naming contract for third-party IR types.
        return OPERATIONS.getOrDefault(type, type.getSimpleName());
    }

    private static Map<Class<?>, String> operations() {
        Map<Class<?>, String> operations = new LinkedHashMap<Class<?>, String>();
        operations.put(Relations.Scan.class, "Scan");
        operations.put(Relations.Project.class, "Project");
        operations.put(Relations.Filter.class, "Filter");
        operations.put(Relations.Join.class, "Join");
        operations.put(Relations.Aggregate.class, "Aggregate");
        operations.put(Relations.SetOp.class, "SetOp");
        operations.put(Relations.Window.class, "Window");
        operations.put(Relations.Generate.class, "Generate");
        operations.put(Relations.Sort.class, "Sort");
        operations.put(Relations.Limit.class, "Limit");
        operations.put(Relations.Distinct.class, "Distinct");
        operations.put(Relations.DefinitionInstance.class, "DefinitionInstance");
        operations.put(Relations.UnknownRelation.class, "UnknownRelation");
        operations.put(Relations.Values.class, "Values");
        operations.put(Expressions.Literal.class, "Literal");
        operations.put(Expressions.SlotRef.class, "SlotRef");
        operations.put(Expressions.CorrelatedSlotRef.class, "CorrelatedSlotRef");
        operations.put(Expressions.Call.class, "Call");
        operations.put(Expressions.Case.class, "Case");
        operations.put(Expressions.Cast.class, "Cast");
        operations.put(Expressions.FieldAccess.class, "FieldAccess");
        operations.put(Expressions.Subscript.class, "Subscript");
        operations.put(Expressions.ScalarSubquery.class, "ScalarSubquery");
        operations.put(Expressions.ExistsSubquery.class, "ExistsSubquery");
        operations.put(Expressions.InSubquery.class, "InSubquery");
        operations.put(Expressions.UnknownReference.class, "UnknownReference");
        operations.put(Expressions.UnknownExpression.class, "UnknownExpression");
        operations.put(Expressions.NotRequestedExpression.class, "NotRequestedExpression");
        operations.put(ExternalDatasetRelation.class, "ExternalDatasetRelation");
        operations.put(QualifiedFieldHandle.class, "QualifiedFieldHandle");
        operations.put(CorrelatedQualifiedFieldHandle.class, "CorrelatedQualifiedFieldHandle");
        return Collections.unmodifiableMap(operations);
    }
}
