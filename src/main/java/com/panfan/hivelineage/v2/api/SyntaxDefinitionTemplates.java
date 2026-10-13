package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.semantic.PreparedDefinition;
import com.panfan.hivelineage.v2.session.DefinitionTemplate;
import com.panfan.hivelineage.v2.syntax.CteSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionStatementSyntax;
import com.panfan.hivelineage.v2.syntax.InsertStatementSyntax;
import com.panfan.hivelineage.v2.syntax.QueryStatementSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxQuery;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;
import com.panfan.hivelineage.v2.syntax.SyntaxTemplate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class SyntaxDefinitionTemplates {
    private SyntaxDefinitionTemplates() {
    }

    static Map<DefinitionId, DefinitionTemplate> prepare(SyntaxStatement syntax, BoundAnalysisUnit unit) {
        Map<DefinitionId, SyntaxStatement> bodies = new LinkedHashMap<>();
        if (syntax instanceof DefinitionStatementSyntax) {
            SyntaxQuery query = ((DefinitionStatementSyntax) syntax).getDefinition().getQuery();
            if (query != null) {
                bodies.put(new DefinitionId(syntax.getNodeId().getValue() + "/definition"), queryStatement(query));
                addCtes(bodies, query.getCtes());
            }
        } else if (syntax instanceof InsertStatementSyntax) {
            addCtes(bodies, ((InsertStatementSyntax) syntax).getCtes());
        } else if (syntax instanceof QueryStatementSyntax) {
            addCtes(bodies, ((QueryStatementSyntax) syntax).getQuery().getCtes());
        }
        Map<DefinitionId, DefinitionTemplate> templates = new LinkedHashMap<>();
        for (BoundEffect effect : unit.getEffects()) {
            if (effect instanceof Effects.RegisterDefinition) {
                Effects.RegisterDefinition definition = (Effects.RegisterDefinition) effect;
                SyntaxStatement body = bodies.get(definition.getDefinitionId());
                if (body != null) {
                    Optional<BoundRelation> relation = unit.getRelations().stream().filter(node -> node.getId().equals(definition.getRelationRoot())).findFirst();
                    SyntaxTemplate template = new SyntaxTemplate(new NodeId(definition.getDefinitionId().getValue() + ":syntax"), body, body.getSourceRef());
                    templates.put(definition.getDefinitionId(), new PreparedDefinition(definition.getDefinitionId(), Optional.of(template), relation,
                        body.getSourceRef(), Optional.of(unit)));
                }
            }
        }
        return templates;
    }

    private static void addCtes(Map<DefinitionId, SyntaxStatement> bodies, java.util.List<CteSyntax> ctes) {
        for (CteSyntax cte : ctes) {
            bodies.put(new DefinitionId(cte.getNodeId().getValue() + "/definition"), queryStatement(cte.getQuery()));
        }
    }

    private static QueryStatementSyntax queryStatement(SyntaxQuery query) {
        return new QueryStatementSyntax(new NodeId(query.getNodeId().getValue() + ":definition-statement"), query.getSourceRef(), query);
    }
}
