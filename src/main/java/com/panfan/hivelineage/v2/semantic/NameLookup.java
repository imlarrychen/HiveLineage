package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The single precedence policy: output aliases, longest local qualifier, ancestors, fields. */
final class NameLookup {
    private final SourceSchemaLoader schemas;

    NameLookup(SourceSchemaLoader schemas) {
        this.schemas = schemas;
    }

    Result lookup(List<String> parts, ReferenceScope origin) {
        if (parts.size() == 1 && origin.clauseLocation.outputAliasesVisible()) {
            List<OutputSlot> aliases = origin.symbols.outputAliases(parts.get(0));
            if (!aliases.isEmpty()) {
                return Result.aliases(origin, aliases);
            }
        }
        return lookupSource(parts, origin);
    }

    private Result lookupSource(List<String> parts, ReferenceScope scope) {
        for (int split = parts.size() - 1; split > 0; split--) {
            List<SourceBinding> owners = scope.symbols.qualified(String.join(".", parts.subList(0, split)));
            if (!owners.isEmpty()) {
                return Result.sources(scope, owners, parts.get(split), parts.subList(split + 1, parts.size()));
            }
        }
        if (parts.size() > 1 && scope.parent != null && ancestorAlias(parts, scope.parent)) {
            return lookupSource(parts, scope.parent);
        }
        if (parts.size() > 1 && !scope.symbols.hasStruct(parts.get(0))) {
            return scope.parent == null ? Result.unresolved(scope, "UNKNOWN_QUALIFIED_OWNER") : lookupSource(parts, scope.parent);
        }
        List<SourceBinding> owners = unqualified(parts.get(0), scope);
        if (owners.isEmpty() && scope.parent != null) {
            return lookupSource(parts, scope.parent);
        }
        owners = preferDeclaredGenerator(owners, parts.get(0));
        return Result.sources(scope, owners, parts.get(0), parts.subList(1, parts.size()));
    }

    private List<SourceBinding> unqualified(String column, ReferenceScope scope) {
        List<SourceBinding> owners = new ArrayList<>();
        List<SourceBinding> sources = scope.symbols.sources();
        for (SourceBinding candidate : sources) {
            if (sources.size() > 1) {
                schemas.load(candidate);
            }
            if (!candidate.columns(column).isEmpty() || candidate.schema().getState() == SchemaState.UNKNOWN
                    || (sources.size() == 1 && candidate.physical && candidate.schema().getState() != SchemaState.KNOWN)) {
                owners.add(candidate);
            }
        }
        return owners;
    }

    private List<SourceBinding> preferDeclaredGenerator(List<SourceBinding> owners, String column) {
        boolean declared = false;
        for (SourceBinding candidate : owners) {
            declared |= candidate.isGenerated() && !candidate.columns(column).isEmpty();
        }
        if (!declared || owners.size() < 2) {
            return owners;
        }
        List<SourceBinding> matches = new ArrayList<>();
        for (SourceBinding candidate : owners) {
            if (!candidate.physical || !candidate.columns(column).isEmpty() || candidate.schema().getState() == SchemaState.KNOWN) {
                matches.add(candidate);
            }
        }
        return matches;
    }

    private boolean ancestorAlias(List<String> parts, ReferenceScope scope) {
        for (ReferenceScope current = scope; current != null; current = current.parent) {
            for (int split = parts.size() - 1; split > 0; split--) {
                if (!current.symbols.qualified(String.join(".", parts.subList(0, split))).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    static final class Result {
        enum Kind { OUTPUT_ALIAS, UNIQUE_SOURCE, AMBIGUOUS, UNRESOLVED }

        final Kind kind;
        final ReferenceScope scope;
        final List<SourceBinding> candidates;
        final List<OutputSlot> aliases;
        final String column;
        final List<String> path;
        final String diagnostic;

        private Result(Kind kind, ReferenceScope scope, List<SourceBinding> candidates, List<OutputSlot> aliases,
                       String column, List<String> path, String diagnostic) {
            this.kind = kind;
            this.scope = scope;
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.aliases = Collections.unmodifiableList(new ArrayList<>(aliases));
            this.column = column;
            this.path = Collections.unmodifiableList(new ArrayList<>(path));
            this.diagnostic = diagnostic;
        }

        static Result aliases(ReferenceScope scope, List<OutputSlot> aliases) {
            return new Result(Kind.OUTPUT_ALIAS, scope, Collections.<SourceBinding>emptyList(), aliases,
                    null, Collections.<String>emptyList(), null);
        }

        static Result sources(ReferenceScope scope, List<SourceBinding> owners, String column, List<String> path) {
            Kind kind = owners.size() == 1 ? Kind.UNIQUE_SOURCE : owners.isEmpty() ? Kind.UNRESOLVED : Kind.AMBIGUOUS;
            return new Result(kind, scope, owners.isEmpty() ? scope.symbols.sources() : owners,
                    Collections.<OutputSlot>emptyList(), column, path, "AMBIGUOUS_COLUMN_OWNER");
        }

        static Result unresolved(ReferenceScope scope, String diagnostic) {
            return new Result(Kind.UNRESOLVED, scope, scope.symbols.sources(), Collections.<OutputSlot>emptyList(),
                    null, Collections.<String>emptyList(), diagnostic);
        }
    }
}
