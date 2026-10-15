package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.metadata.LookupStatus;
import com.panfan.hivelineage.v2.metadata.ObjectDescriptor;
import com.panfan.hivelineage.v2.session.CatalogEntry;
import com.panfan.hivelineage.v2.syntax.DefinitionBodySyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionKindSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionStatementSyntax;

import java.util.Collections;
import java.util.List;

/** Shared immutable object resolution and precondition decision for a catalog proposal. */
final class CatalogEffectRequest {
    private final DefinitionStatementSyntax statement;
    private final BindingContext context;
    private final QualifiedName name;
    private final ObjectId object;
    private final CatalogEntry local;
    private final Effects.ObjectKind kind;
    private final LookupStatus existence;

    private CatalogEffectRequest(DefinitionStatementSyntax statement, BindingContext context, QualifiedName name,
                                 ObjectId object, CatalogEntry local) {
        this.statement = statement;
        this.context = context;
        this.name = name;
        this.object = object;
        this.local = local;
        kind = objectKind();
        existence = body().getOptions().isIfNotExists()
                ? new ObjectExistenceResolver(context).lookup(name, object, body().getOptions().isTemporary()) : LookupStatus.UNKNOWN;
    }

    static CatalogEffectRequest resolve(DefinitionStatementSyntax statement, BindingContext context) {
        DefinitionBodySyntax body = statement.getDefinition();
        CatalogObjectResolver resolver = new CatalogObjectResolver(context);
        if (body.getKind() == DefinitionKindSyntax.CREATE_TABLE || body.getKind() == DefinitionKindSyntax.CREATE_VIEW) {
            QualifiedName name = SemanticNames.qualify(body.getName(), context, body.getOptions().isTemporary());
            return new CatalogEffectRequest(statement, context, name, resolver.identity(name), null);
        }
        boolean view = body.getKind() == DefinitionKindSyntax.DROP_VIEW || body.getKind() == DefinitionKindSyntax.ALTER_VIEW;
        CatalogObjectResolver.Resolution resolved = resolver.resolve(body.getName(), context.getSession().getCurrentDatabase(), !view);
        return new CatalogEffectRequest(statement, context, resolved.name, resolved.object, resolved.local);
    }

    boolean isNoOp() {
        return body().getOptions().isIfNotExists() && existence == LookupStatus.FOUND;
    }

    Effects.Precondition precondition() {
        if (body().getOptions().isIfNotExists() && existence != LookupStatus.NOT_FOUND) {
            return new Effects.Precondition(Effects.Precondition.Kind.OBJECT_ABSENT, object);
        }
        if (!body().getOptions().isIfNotExists() && body().getOptions().isIfExists()) {
            return new Effects.Precondition(Effects.Precondition.Kind.OBJECT_PRESENT, object);
        }
        return new Effects.Precondition(Effects.Precondition.Kind.ALWAYS, null);
    }

    Effects.Certainty certainty() {
        return body().getOptions().isIfNotExists() && existence != LookupStatus.NOT_FOUND
                ? Effects.Certainty.CONDITIONAL : Effects.Certainty.CERTAIN;
    }

    List<QualifiedName> lookupNames() {
        return local == null ? Collections.singletonList(name) : local.getLookupNames();
    }

    private Effects.ObjectKind objectKind() {
        if (local != null) {
            if (local.getObject().getKind() == ObjectDescriptor.Kind.TEMPORARY_TABLE) {
                return Effects.ObjectKind.TEMPORARY_TABLE;
            }
            return local.getObject().getKind() == ObjectDescriptor.Kind.VIEW ? Effects.ObjectKind.VIEW : Effects.ObjectKind.TABLE;
        }
        if (body().getOptions().isTemporary()) {
            return Effects.ObjectKind.TEMPORARY_TABLE;
        }
        return body().getKind() == DefinitionKindSyntax.CREATE_VIEW || body().getKind() == DefinitionKindSyntax.DROP_VIEW
                || body().getKind() == DefinitionKindSyntax.ALTER_VIEW ? Effects.ObjectKind.VIEW : Effects.ObjectKind.TABLE;
    }

    DefinitionBodySyntax body() {
        return statement.getDefinition();
    }

    BindingContext context() {
        return context;
    }

    QualifiedName name() {
        return name;
    }

    ObjectId object() {
        return object;
    }

    Effects.ObjectKind kind() {
        return kind;
    }

    NodeId id() {
        return new NodeId(statement.getNodeId().getValue() + "/catalog-effect");
    }

    SourceRef sourceRef() {
        return statement.getSourceRef();
    }
}
