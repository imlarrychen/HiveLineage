package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.v2.analysis.JoinFact;
import com.panfan.hivelineage.v2.analysis.JoinKind;
import com.panfan.hivelineage.v2.analysis.LineageEndpoint;
import com.panfan.hivelineage.v2.export.SourceRenderer;
import com.panfan.hivelineage.v2.export.BoundNameIndex;
import com.panfan.hivelineage.v2.export.BoundExpressionFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class LegacyJoinProjector {
    private final BoundNameIndex names;
    private final SourceRenderer renderer;
    private final BoundExpressionFormatter expressions;
    private final LegacyDirectNameRenderer directNames;

    LegacyJoinProjector(BoundNameIndex names, SourceRenderer renderer, BoundExpressionFormatter expressions) {
        this.names = names;
        this.renderer = renderer;
        this.expressions = expressions;
        this.directNames = new LegacyDirectNameRenderer(names, renderer);
    }

    JoinCondition project(JoinFact fact) {
        String expression = fact.getConditionSource().getCompleteConditionSource().flatMap(renderer::render).map(String::trim).orElseGet(() ->
                fact.getConditionSource().getPredicate().flatMap(expressions::format).orElse(null));
        return JoinCondition.builder().joinIndex(fact.getJoinIndex()).queryBlockPath(fact.getQueryBlockPath())
                .joinType(code(fact.getKind())).joinTypeName(typeName(fact.getKind()))
                .directJoinObjects(objects(fact.getDirectReferences(), true)).directJoinColumns(columns(fact, true))
                .joinTables(objects(fact.getPhysicalReferences(), false)).joinColumns(columns(fact, false))
                .joinExpression(expression).build();
    }

    private List<DataObjectRef> objects(List<LineageEndpoint> references, boolean direct) {
        Set<String> values = new LinkedHashSet<String>();
        for (LineageEndpoint reference : references) {
            (direct ? names.object(reference) : names.physicalObject(reference)).ifPresent(values::add);
        }
        List<DataObjectRef> result = new ArrayList<DataObjectRef>();
        for (String value : values) {
            result.add(new DataObjectRef(value));
        }
        return result;
    }

    private List<ColumnRef> columns(JoinFact fact, boolean direct) {
        Set<String> values = new LinkedHashSet<String>();
        List<LineageEndpoint> references = direct ? fact.getDirectReferences() : fact.getPhysicalReferences();
        for (LineageEndpoint reference : references) {
            java.util.Optional<String> unqualified = direct ? fact.getConditionSource().getPredicate().flatMap(
                    expression -> directNames.unqualified(reference, expression)) : java.util.Optional.empty();
            if (unqualified.isPresent()) {
                values.add(unqualified.get());
            } else {
                names.column(reference, direct).ifPresent(values::add);
            }
        }
        List<ColumnRef> result = new ArrayList<ColumnRef>();
        for (String value : values) {
            result.add(new ColumnRef(value));
        }
        return result;
    }

    private int code(JoinKind kind) {
        switch (kind) {
            case INNER:
                return 1;
            case LEFT:
                return 2;
            case RIGHT:
                return 3;
            case FULL:
                return 4;
            case LEFT_SEMI:
                return 5;
            case LEFT_ANTI:
                return 6;
            case CROSS:
            case IMPLICIT:
                return 7;
            default:
                return 0;
        }
    }

    private String typeName(JoinKind kind) {
        return kind == JoinKind.IMPLICIT ? "IMPLICIT_JOIN" : kind == JoinKind.UNKNOWN ? "UNKNOWN" : kind.name() + "_JOIN";
    }
}
