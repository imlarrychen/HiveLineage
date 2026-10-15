package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.analysis.DefaultJoinConditionAnalysis;
import com.panfan.hivelineage.v2.analysis.DefaultLineageEngine;
import com.panfan.hivelineage.v2.analysis.DefaultLineageRuleRegistry;
import com.panfan.hivelineage.v2.analysis.JoinConditionAnalysis;
import com.panfan.hivelineage.v2.analysis.LineageEngine;
import com.panfan.hivelineage.v2.analysis.LineageRuleRegistry;
import com.panfan.hivelineage.v2.frontend.DefaultVariableResolver;
import com.panfan.hivelineage.v2.frontend.VariableResolver;
import com.panfan.hivelineage.v2.semantic.AnalysisDemandPlanner;
import com.panfan.hivelineage.v2.semantic.DefaultAnalysisDemandPlanner;
import com.panfan.hivelineage.v2.semantic.DefaultFunctionCatalog;
import com.panfan.hivelineage.v2.semantic.DefaultIrVerifier;
import com.panfan.hivelineage.v2.semantic.FunctionCatalog;
import com.panfan.hivelineage.v2.semantic.IrVerifier;
import com.panfan.hivelineage.v2.session.DefaultSessionEffectCommitter;
import com.panfan.hivelineage.v2.session.DefaultSessionEffectPlanner;
import com.panfan.hivelineage.v2.session.SessionEffectCommitter;
import com.panfan.hivelineage.v2.session.SessionEffectPlanner;

import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Default assembly and replaceable stage capabilities, outside statement execution.
 */
public final class AnalyzerServices {
    final VariableResolver variables;
    final AnalysisDemandPlanner demands;
    final FunctionCatalog functions;
    final IrVerifier verifier;
    final IntFunction<LineageEngine> lineage;
    final IntFunction<JoinConditionAnalysis> joins;
    final LineageRuleRegistry rules;
    final SessionEffectPlanner effects;
    final SessionEffectCommitter committer;

    private AnalyzerServices(Builder builder) {
        variables = Objects.requireNonNull(builder.variables, "variables");
        demands = Objects.requireNonNull(builder.demands, "demands");
        functions = Objects.requireNonNull(builder.functions, "functions");
        verifier = Objects.requireNonNull(builder.verifier, "verifier");
        lineage = Objects.requireNonNull(builder.lineage, "lineage");
        joins = Objects.requireNonNull(builder.joins, "joins");
        rules = Objects.requireNonNull(builder.rules, "rules");
        effects = Objects.requireNonNull(builder.effects, "effects");
        committer = Objects.requireNonNull(builder.committer, "committer");
    }

    public static AnalyzerServices defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private VariableResolver variables = new DefaultVariableResolver();
        private AnalysisDemandPlanner demands = new DefaultAnalysisDemandPlanner();
        private FunctionCatalog functions = new DefaultFunctionCatalog();
        private IrVerifier verifier = new DefaultIrVerifier();
        private IntFunction<LineageEngine> lineage = DefaultLineageEngine::new;
        private IntFunction<JoinConditionAnalysis> joins = DefaultJoinConditionAnalysis::new;
        private LineageRuleRegistry rules = new DefaultLineageRuleRegistry();
        private SessionEffectPlanner effects = new DefaultSessionEffectPlanner();
        private SessionEffectCommitter committer = new DefaultSessionEffectCommitter();

        public Builder variables(VariableResolver value) {
            variables = value;
            return this;
        }

        public Builder demands(AnalysisDemandPlanner value) {
            demands = value;
            return this;
        }

        public Builder functions(FunctionCatalog value) {
            functions = value;
            return this;
        }

        public Builder verifier(IrVerifier value) {
            verifier = value;
            return this;
        }

        public Builder lineage(IntFunction<LineageEngine> value) {
            lineage = value;
            return this;
        }

        public Builder joins(IntFunction<JoinConditionAnalysis> value) {
            joins = value;
            return this;
        }

        public Builder rules(LineageRuleRegistry value) {
            rules = value;
            return this;
        }

        public Builder effects(SessionEffectPlanner value) {
            effects = value;
            return this;
        }

        public Builder committer(SessionEffectCommitter value) {
            committer = value;
            return this;
        }

        public AnalyzerServices build() {
            return new AnalyzerServices(this);
        }
    }
}
