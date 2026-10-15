package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.relation.BoundRelation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** FROM semantics explicitly distinguish predicate visibility from exposed output sources. */
final class BoundFrom {
    final BoundRelation relation;
    final List<SourceBinding> conditionSources;
    final List<SourceBinding> outputSources;

    BoundFrom(BoundRelation relation, List<SourceBinding> conditionSources, List<SourceBinding> outputSources) {
        this(relation, new Visibility(conditionSources, outputSources));
    }

    BoundFrom(BoundRelation relation, Visibility visibility) {
        this.relation = Objects.requireNonNull(relation, "relation");
        this.conditionSources = visibility.conditions;
        this.outputSources = visibility.outputs;
    }

    static final class Visibility {
        private final List<SourceBinding> conditions;
        private final List<SourceBinding> outputs;

        Visibility(List<SourceBinding> conditions, List<SourceBinding> outputs) {
            this.conditions = Collections.unmodifiableList(new ArrayList<>(conditions));
            this.outputs = Collections.unmodifiableList(new ArrayList<>(outputs));
            if (!this.conditions.containsAll(this.outputs)) {
                throw new IllegalArgumentException("Output sources must be visible to conditions");
            }
        }

        static Visibility join(BoundFrom left, BoundFrom right, boolean leftOnly) {
            List<SourceBinding> conditions = combine(left.outputSources, right.outputSources);
            return new Visibility(conditions, leftOnly ? left.outputSources : conditions);
        }

        void exposeConditions(ScopeSymbols symbols, List<SourceBinding> preceding) {
            symbols.replaceSources(combine(preceding, conditions));
        }
    }

    static BoundFrom source(BoundRelation relation, SourceBinding source) {
        return new BoundFrom(relation, Collections.singletonList(source), Collections.singletonList(source));
    }

    static List<SourceBinding> combine(List<SourceBinding> left, List<SourceBinding> right) {
        List<SourceBinding> sources = new ArrayList<>(left);
        sources.addAll(right);
        return sources;
    }
}
