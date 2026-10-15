package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.api.AnalysisCapability;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.syntax.CommandStatementSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionKindSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionStatementSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Dispatches pure effect proposals by semantic family after one catalog resolution. */
public final class DefaultEffectBinder implements EffectBinder {
    private final SessionCommandEffectBinder commands = new SessionCommandEffectBinder();
    private final Map<DefinitionKindSyntax, CatalogEffectBinder> catalogBinders = catalogBinders();

    @Override
    public EffectBindingResult bind(SyntaxStatement statement, BindingContext context, BindingDemand demand) {
        if (statement instanceof CommandStatementSyntax) {
            return commands.bind((CommandStatementSyntax) statement, context);
        }
        if (!(statement instanceof DefinitionStatementSyntax)
                || !context.getContract().getCapabilities().getCapabilities().contains(AnalysisCapability.CATALOG_SIMULATION)) {
            return empty();
        }
        DefinitionStatementSyntax definition = (DefinitionStatementSyntax) statement;
        if (definition.getDefinition().getQuery() != null) {
            return empty();
        }
        CatalogEffectRequest request = CatalogEffectRequest.resolve(definition, context);
        if (request.isNoOp()) {
            return empty();
        }
        return catalogBinders.get(request.body().getKind()).bind(request);
    }

    private static EffectBindingResult empty() {
        return new EffectBindingResult(Collections.<BoundEffect>emptyList(), DiagnosticBag.empty());
    }

    private static Map<DefinitionKindSyntax, CatalogEffectBinder> catalogBinders() {
        Map<DefinitionKindSyntax, CatalogEffectBinder> binders = new EnumMap<>(DefinitionKindSyntax.class);
        CatalogEffectBinder create = new CreateObjectEffectBinder();
        CatalogEffectBinder drop = new DropObjectEffectBinder();
        CatalogEffectBinder alter = new AlterObjectEffectBinder();
        binders.put(DefinitionKindSyntax.CREATE_TABLE, create);
        binders.put(DefinitionKindSyntax.CREATE_VIEW, create);
        binders.put(DefinitionKindSyntax.DROP_TABLE, drop);
        binders.put(DefinitionKindSyntax.DROP_VIEW, drop);
        binders.put(DefinitionKindSyntax.ALTER_TABLE, alter);
        binders.put(DefinitionKindSyntax.ALTER_VIEW, alter);
        binders.put(DefinitionKindSyntax.TRUNCATE_TABLE, alter);
        return Collections.unmodifiableMap(binders);
    }
}
