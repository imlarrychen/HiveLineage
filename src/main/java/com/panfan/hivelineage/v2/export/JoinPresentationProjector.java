package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.JoinFact;
import com.panfan.hivelineage.v2.analysis.JoinKind;
import com.panfan.hivelineage.v2.analysis.LineageEndpoint;
import com.panfan.hivelineage.v2.common.QualifiedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class JoinPresentationProjector {
    private final BoundNameIndex names;
    private final BoundExpressionFormatter expressions;
    private final SourceRenderer renderer;

    JoinPresentationProjector(BoundNameIndex names, BoundExpressionFormatter expressions, SourceRenderer renderer) {
        this.names = names;
        this.expressions = expressions;
        this.renderer = renderer;
    }

    JoinPresentation project(JoinFact fact) {
        Optional<String> source = fact.getConditionSource().getCompleteConditionSource().flatMap(renderer::render)
                .map(String::trim).filter(text -> !text.isEmpty());
        Optional<String> text = source.isPresent() ? source : fact.getConditionSource().getPredicate().flatMap(expressions::format);
        boolean extracted = source.isPresent() || fact.getConditionSource().getPredicate().map(expressions::extracted).orElse(false);
        return new JoinPresentation(code(fact.getKind()), typeName(fact.getKind()),
                objects(fact.getDirectReferences(), true), columns(fact.getDirectReferences(), true),
                objects(fact.getPhysicalReferences(), false), columns(fact.getPhysicalReferences(), false),
                new RenderedExpression(text, text.isPresent() && extracted));
    }

    private List<QualifiedName> objects(List<LineageEndpoint> references, boolean direct) {
        Map<String, QualifiedName> objects = new LinkedHashMap<String, QualifiedName>();
        for (LineageEndpoint endpoint : references) {
            Optional<QualifiedName> object = names.endpointName(endpoint);
            if (!object.isPresent() && direct) {
                object = names.nativeObject(endpoint, true);
            }
            object.ifPresent(value -> objects.putIfAbsent(value.getDisplayName(), value));
        }
        return new ArrayList<QualifiedName>(objects.values());
    }

    private List<ColumnAddress> columns(List<LineageEndpoint> references, boolean direct) {
        Map<String, ColumnAddress> columns = new LinkedHashMap<String, ColumnAddress>();
        for (LineageEndpoint endpoint : references) {
            names.columnAddress(endpoint, direct).ifPresent(column -> columns.putIfAbsent(
                    column.getObject().getDisplayName() + "." + String.join(".", column.getFieldPath()), column));
        }
        return new ArrayList<ColumnAddress>(columns.values());
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
