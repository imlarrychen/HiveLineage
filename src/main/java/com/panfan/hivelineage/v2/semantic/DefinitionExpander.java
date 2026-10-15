package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.api.DefinitionPolicy;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.LookupStatus;
import com.panfan.hivelineage.v2.metadata.ObjectDescriptor;
import com.panfan.hivelineage.v2.session.CatalogEntry;
import com.panfan.hivelineage.v2.session.LogicalDefinition;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.statement.BoundQueryBlockReference;
import com.panfan.hivelineage.v2.ir.statement.QueryResult;
import com.panfan.hivelineage.v2.metadata.DefinitionRef;
import com.panfan.hivelineage.v2.session.DefinitionRequest;
import com.panfan.hivelineage.v2.syntax.CteSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionStatementSyntax;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.NamedFromSyntax;
import com.panfan.hivelineage.v2.syntax.QueryStatementSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxQuery;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Definition preparation and expansion have explicit outcomes; deferred work stays outside binding. */
final class DefinitionExpander {
    interface QueryBinding {
        BoundRelation bind(SyntaxQuery syntax, ReferenceScope parent, boolean outputs,
                           QueryResult.Role role, String prefix, String displayPath);
    }

    private final BindingWork work;
    private final QueryBinding queries;

    DefinitionExpander(BindingWork work, QueryBinding queries) {
        this.work = work;
        this.queries = queries;
    }

    Source discover(CatalogObjectResolver.Resolution resolved, boolean unqualified, String simple, NamedFromSyntax syntax) {
        LogicalDefinition logical = logical(resolved.local, unqualified, simple);
        if (resolved.uncertain) {
            work.warn("SESSION_OBJECT_UNCERTAIN", "Prior effects left object state uncertain", syntax.getSourceRef());
            return new Source(resolved.kind(), logical, null);
        }
        DefinitionRef reference = logical == null ? null : logical.getReference();
        if (work.context.getContract().isExpandViewLineage() && resolved.local == null && logical == null) {
            return externalSource(resolved, syntax);
        }
        return new Source(resolved.kind(), logical, reference);
    }

    private LogicalDefinition logical(CatalogEntry local, boolean unqualified, String simple) {
        LogicalDefinition logical = null;
        if (unqualified && work.context.getContract().getDefinitionPolicy() == DefinitionPolicy.CURRENT_API_CTE_REUSE) {
            logical = work.context.getSession().getScriptDefinitions().get(new QualifiedName(Collections.singletonList(simple)));
        }
        return local != null && local.getDefinition().isPresent() ? local.getDefinition().get() : logical;
    }

    private Source externalSource(CatalogObjectResolver.Resolution resolved, NamedFromSyntax syntax) {
        LookupResult<ObjectDescriptor> descriptor = work.context.getMetadata().lookupObject(resolved.name, work.context.getMetadataContext());
        work.diagnostics.addAll(descriptor.getDiagnostics().getDiagnostics());
        if (descriptor.getStatus() != LookupStatus.FOUND || descriptor.getValue().get().getKind() != ObjectDescriptor.Kind.VIEW) {
            return new Source(resolved.kind(), null, null);
        }
        DefinitionRef reference = descriptor.getValue().get().getDefinition().orElse(null);
        if (reference == null) {
            work.warn("VIEW_DEFINITION_NOT_FOUND", "View definition is unavailable", syntax.getSourceRef());
        }
        return new Source(BoundObjectReference.ObjectKind.VIEW, null, reference);
    }

    static final class Source {
        final BoundObjectReference.ObjectKind kind;
        final DefinitionRef reference;
        private final LogicalDefinition logical;

        Source(BoundObjectReference.ObjectKind kind, LogicalDefinition logical, DefinitionRef reference) {
            this.kind = kind;
            this.logical = logical;
            this.reference = reference;
        }

        boolean isLegacyCte() {
            return logical != null && logical.getKind() == LogicalDefinition.Kind.LEGACY_CTE;
        }

        PreparedDefinition prepared() {
            return logical != null && logical.getTemplate().isPresent() && logical.getTemplate().get() instanceof PreparedDefinition
                    ? (PreparedDefinition) logical.getTemplate().get() : null;
        }
    }

    List<IdentifierPart> columns(DefinitionRef reference, PreparedDefinition localPrepared) {
        PreparedDefinition prepared = localPrepared == null ? work.context.getDefinitions().get(reference.getDefinitionId()) : localPrepared;
        List<IdentifierPart> columns = new ArrayList<>();
        if (prepared != null && prepared.getSyntax().isPresent()
                && prepared.getSyntax().get().getStatement() instanceof DefinitionStatementSyntax) {
            DefinitionStatementSyntax statement = (DefinitionStatementSyntax) prepared.getSyntax().get().getStatement();
            for (com.panfan.hivelineage.v2.syntax.ColumnDefinitionSyntax column : statement.getDefinition().getSchema().getColumns()) {
                columns.add(column.getName());
            }
        }
        return Collections.unmodifiableList(columns);
    }

    Result expand(DefinitionRef reference, ReferenceScope scope, NamedFromSyntax source, boolean outputs,
                  PreparedDefinition localPrepared) {
        if (work.context.getFailedDefinitions().containsKey(reference.getDefinitionId())) {
            work.diagnostics.addAll(work.context.getFailedDefinitions().get(reference.getDefinitionId()).getDiagnostics());
            work.partial = true;
            return Result.unavailable();
        }
        if (work.definitionPath.contains(reference.getDefinitionId())) {
            work.warn("VIEW_EXPANSION_CYCLE", "Definition cycle detected", source.getSourceRef());
            return Result.unavailable();
        }
        if (work.definitionPath.size() >= work.context.getContract().getMaxViewExpandDepth()) {
            work.warn("VIEW_EXPANSION_DEPTH_EXCEEDED", "Definition expansion depth exceeded", source.getSourceRef());
            return Result.unavailable();
        }
        PreparedDefinition prepared = localPrepared == null ? work.context.getDefinitions().get(reference.getDefinitionId()) : localPrepared;
        if (prepared == null) {
            work.definitionRequests.add(new DefinitionRequest(reference, work.demand.getRequirements(), work.definitionPath,
                    Math.max(0, work.context.getContract().getMaxViewExpandDepth() - work.definitionPath.size())));
            return Result.deferred();
        }
        SyntaxQuery syntax = querySyntax(prepared);
        if (syntax != null) {
            return Result.expanded(bindSyntax(reference, scope, source, outputs, syntax));
        }
        if (prepared.getBoundRelation().isPresent() && prepared.getBoundUnit().isPresent()) {
            importBoundGraph(prepared);
            return Result.expanded(prepared.getBoundRelation().get());
        }
        work.warn("DEFINITION_STRUCTURE_INCOMPLETE", "Definition has no complete graph or syntax", source.getSourceRef());
        return Result.unavailable();
    }

    private SyntaxQuery querySyntax(PreparedDefinition prepared) {
        if (!prepared.getSyntax().isPresent()) {
            return null;
        }
        SyntaxStatement statement = prepared.getSyntax().get().getStatement();
        if (statement instanceof QueryStatementSyntax) {
            return ((QueryStatementSyntax) statement).getQuery();
        }
        return statement instanceof DefinitionStatementSyntax ? ((DefinitionStatementSyntax) statement).getDefinition().getQuery() : null;
    }

    private BoundRelation bindSyntax(DefinitionRef reference, ReferenceScope scope, NamedFromSyntax source,
                                     boolean outputs, SyntaxQuery syntax) {
        work.definitionPath.add(reference.getDefinitionId());
        try {
            ReferenceScope definitionScope = new ReferenceScope(work, null,
                    new ScopeId(scope.prefix + source.getNodeId().getValue() + "/definition-context"),
                    scope.block, scope.prefix + source.getNodeId().getValue() + "/definition/", Collections.<CteSyntax>emptyList());
            definitionScope.database = reference.getDefinitionContext().getDefaultDatabase();
            definitionScope.analyzeJoins = false;
            return queries.bind(syntax, definitionScope, outputs, QueryResult.Role.DEFINITION, definitionScope.prefix,
                    scope.displayPath + ".definition");
        } finally {
            work.definitionPath.remove(work.definitionPath.size() - 1);
        }
    }

    private void importBoundGraph(PreparedDefinition prepared) {
        for (BoundRelation relation : prepared.getBoundUnit().get().getRelations()) {
            work.put(relation);
        }
        work.objects.putAll(prepared.getBoundUnit().get().getObjects());
        work.scopes.putAll(prepared.getBoundUnit().get().getScopes());
        work.relationReferences.putAll(prepared.getBoundUnit().get().getRelationReferences());
        for (Map.Entry<QueryBlockId, BoundQueryBlockReference> entry : prepared.getBoundUnit().get().getQueryBlocks().entrySet()) {
            work.queryBlocks.put(entry.getKey(), new BoundQueryBlockReference(entry.getKey(), entry.getValue().getPath(), false));
        }
    }

    static final class Result {
        enum Status { EXPANDED, DEFERRED, UNAVAILABLE }

        final Status status;
        private final BoundRelation relation;

        private Result(Status status, BoundRelation relation) {
            this.status = status;
            this.relation = relation;
        }

        static Result expanded(BoundRelation relation) {
            return new Result(Status.EXPANDED, java.util.Objects.requireNonNull(relation, "relation"));
        }

        static Result deferred() {
            return new Result(Status.DEFERRED, null);
        }

        static Result unavailable() {
            return new Result(Status.UNAVAILABLE, null);
        }

        BoundRelation relation() {
            if (status != Status.EXPANDED) {
                throw new IllegalStateException("Only an expanded definition has a relation");
            }
            return relation;
        }
    }
}
