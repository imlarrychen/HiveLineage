package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.identity.SlotId;

import java.util.List;
import java.util.Map;

/**
 * Public expression capability for relation rules. Each evaluation uses an explicit,
 * immutable snapshot of its slot and row environment, with no memo shared across calls.
 */
public final class RelationEvaluationContext {
    @FunctionalInterface
    public interface ExpressionEvaluator {
        ExpressionFacts evaluate(BoundExpression expression, Map<SlotId, ExpressionFacts> slots, List<Dependency> rows);
    }

    /** Owned immutable inputs; row refinement reuses the slot snapshot for wide relations. */
    public static final class Environment {
        private final Map<SlotId, ExpressionFacts> slots;
        private final List<Dependency> rows;

        public Environment(Map<SlotId, ExpressionFacts> slots, List<Dependency> rows) {
            this.slots = ModelCollections.immutableMap(slots);
            this.rows = ModelCollections.immutableList(rows);
        }

        private Environment(LineageRuleContext context) {
            this.slots = context.getInputSlots();
            this.rows = context.getInputRows();
        }

        private Environment(Environment source, List<Dependency> refinedRows) {
            this.slots = source.slots;
            this.rows = ModelCollections.immutableList(refinedRows);
        }

        public Map<SlotId, ExpressionFacts> getSlots() {
            return slots;
        }

        public List<Dependency> getRows() {
            return rows;
        }

        public Environment withRows(List<Dependency> refinedRows) {
            return new Environment(this, refinedRows);
        }
    }

    private final LineageRuleContext context;
    private final Environment environment;
    private final ExpressionEvaluator evaluator;

    public RelationEvaluationContext(String rulesVersion, Map<SlotId, ExpressionFacts> slots, List<Dependency> rows,
                                     EvidenceFactory evidenceFactory, ExpressionEvaluator evaluator) {
        this.context = new LineageRuleContext(rulesVersion, slots, rows, evidenceFactory);
        this.environment = new Environment(context);
        this.evaluator = ModelChecks.value(evaluator, "evaluator");
    }

    public ExpressionFacts resolve(BoundExpression expression) {
        return resolve(expression, environment);
    }

    /** Convenience entry point for mutable caller inputs. Reuse Environment for repeated evaluations. */
    public ExpressionFacts resolve(BoundExpression expression, Map<SlotId, ExpressionFacts> slots, List<Dependency> rows) {
        return resolve(expression, new Environment(slots, rows));
    }

    public ExpressionFacts resolve(BoundExpression expression, Environment evaluationEnvironment) {
        ModelChecks.value(evaluationEnvironment, "evaluationEnvironment");
        return evaluator.evaluate(ModelChecks.value(expression, "expression"),
                evaluationEnvironment.getSlots(), evaluationEnvironment.getRows());
    }

    public Environment getEnvironment() {
        return environment;
    }

    public String getRulesVersion() {
        return context.getRulesVersion();
    }

    public Map<SlotId, ExpressionFacts> getInputSlots() {
        return context.getInputSlots();
    }

    public List<Dependency> getInputRows() {
        return context.getInputRows();
    }

    public EvidenceFactory getEvidenceFactory() {
        return context.getEvidenceFactory();
    }

    LineageRuleContext ruleContext() {
        return context;
    }
}
