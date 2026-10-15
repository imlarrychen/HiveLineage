package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.syntax.NameRefSyntax;

import java.util.List;

/** Coordinates fixed name lookup, lazy schema discovery and IR construction. */
final class ReferenceBinder {
    private final SourceSchemaLoader schemas;
    private final NameLookup names;
    private final BoundReferenceFactory references;

    ReferenceBinder(BindingWork work) {
        schemas = new SourceSchemaLoader(work);
        names = new NameLookup(schemas);
        references = new BoundReferenceFactory(work, schemas);
    }

    BoundExpression bind(NameRefSyntax syntax, ReferenceScope scope) {
        return references.create(syntax, scope, names.lookup(SemanticNames.parts(syntax.getName()), scope));
    }

    BoundExpression unknown(NodeId id, SourceRef source, List<SourceBinding> candidates, String code) {
        return references.unknown(id, source, candidates, code);
    }

    void loadSchema(SourceBinding binding, ReferenceScope scope) {
        schemas.load(binding);
    }
}
