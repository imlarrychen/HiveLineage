package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.metadata.MetadataAccess;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.semantic.BindingContext;
import com.panfan.hivelineage.v2.semantic.BindingDemand;
import com.panfan.hivelineage.v2.semantic.BindingOutcome;
import com.panfan.hivelineage.v2.semantic.BindingResult;
import com.panfan.hivelineage.v2.semantic.PreparedDefinition;
import com.panfan.hivelineage.v2.semantic.SemanticBinder;
import com.panfan.hivelineage.v2.session.DefinitionRequest;
import com.panfan.hivelineage.v2.session.SessionSnapshot;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Retries binding only when the definition protocol makes progress.
 */
final class DefinitionBindingCoordinator {
    private final SemanticBinder binder;
    private final com.panfan.hivelineage.v2.semantic.FunctionCatalog functions;

    DefinitionBindingCoordinator(SemanticBinder binder, com.panfan.hivelineage.v2.semantic.FunctionCatalog functions) {
        this.binder = binder;
        this.functions = functions;
    }

    BindingResult bind(ScriptAnalysisRequest request, SyntaxStatement syntax, BindingDemand demand, SessionSnapshot session,
                       MetadataAccess access, com.panfan.hivelineage.v2.session.DefinitionCoordinator<PreparedDefinition> coordinator, Map<DefinitionId, PreparedDefinition> prepared,
                       Map<DefinitionId, DiagnosticBag> failedDefinitions) {
        List<Diagnostic> definitionDiagnostics = new ArrayList<>();
        for (int attempt = 0; attempt <= request.getResourceLimits().getMaxExpansionDepth(); attempt++) {
            BindingContext context = new BindingContext(session, access, request.getMetadataContext(), prepared,
                    functions, request.getDialect(), request.getContract(), failedDefinitions);
            BindingResult result = binder.bind(syntax, context, demand);
            if (result.getOutcome() != BindingOutcome.NEEDS_DEFINITION) {
                result.getUnit().ifPresent(unit -> unit.getRelations().forEach(relation -> {
                    if (relation instanceof com.panfan.hivelineage.v2.ir.relation.Relations.DefinitionInstance) {
                        PreparedDefinition used = prepared.get(((com.panfan.hivelineage.v2.ir.relation.Relations.DefinitionInstance) relation).getDefinitionId());
                        if (used != null) {
                            used.getSyntax().ifPresent(template -> definitionDiagnostics.addAll(template.getDiagnostics()));
                        }
                    }
                }));
                definitionDiagnostics.addAll(result.getDiagnostics().getDiagnostics());
                return new BindingResult(result.getOutcome(), result.getUnit(), result.getDefinitionRequests(),
                        new DiagnosticBag(DiagnosticBag.distinct(definitionDiagnostics)));
            }
            boolean progressed = false;
            for (DefinitionRequest definition : result.getDefinitionRequests()) {
                DefinitionId id = definition.getReference().getDefinitionId();
                if (prepared.containsKey(id) || failedDefinitions.containsKey(id)) {
                    continue;
                }
                LookupResult<PreparedDefinition> value = coordinator.prepare(definition);
                definitionDiagnostics.addAll(value.getDiagnostics().getDiagnostics());
                if (value.getValue().isPresent()) {
                    prepared.put(id, value.getValue().get());
                } else {
                    failedDefinitions.put(id, value.getDiagnostics());
                }
                progressed = true;
            }
            if (!progressed) {
                throw new AnalysisFailure(PipelineDiagnosticCode.DEFINITION_PROTOCOL_STALLED, "Definition preparation made no progress");
            }
        }
        throw new AnalysisFailure(PipelineDiagnosticCode.VIEW_EXPANSION_LIMIT, "Definition expansion exceeded configured retry limit");
    }

}
