package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.LookupStatus;
import com.panfan.hivelineage.v2.metadata.RelationSchema;
import com.panfan.hivelineage.v2.session.CatalogEntry;
import com.panfan.hivelineage.v2.session.OverlayEntry;
import com.panfan.hivelineage.v2.syntax.SyntaxName;

import java.util.List;
import java.util.Optional;

/**
 * One identity and session-state lookup for source, sink and catalog binders.
 */
final class CatalogObjectResolver {
    private final BindingContext context;

    CatalogObjectResolver(BindingContext context) {
        this.context = context;
    }

    Resolution resolve(SyntaxName syntax, Optional<QualifiedName> database, boolean temporaryVisible) {
        List<String> parts = SemanticNames.parts(syntax);
        QualifiedName requested = new QualifiedName(parts);
        if (temporaryVisible) {
            for (CatalogEntry entry : context.getSession().getTemporaryCatalog().values()) {
                if (entry.getLookupNames().contains(requested)
                        || parts.size() == 1 && last(entry.getObject().getName()).equals(parts.get(0))) {
                    return resolution(entry.getObject().getName(), entry.getObject().getObjectId(), entry, null);
                }
            }
        }
        if (parts.size() == 1 && context.getSession().getUncertainty().isDatabaseUncertain()) {
            throw new BindingFailure("DATABASE_NOT_RESOLVED", "Unqualified object requires a certain current database");
        }
        QualifiedName qualified = SemanticNames.qualify(parts, database);
        OverlayEntry overlay = context.getSession().getPersistentOverlay().get(qualified);
        if (overlay == null) {
            for (OverlayEntry candidate : context.getSession().getPersistentOverlay().values()) {
                if (candidate.getEntry().isPresent() && candidate.getEntry().get().getLookupNames().contains(qualified)) {
                    overlay = candidate;
                    break;
                }
            }
        }
        CatalogEntry local = overlay == null ? null : overlay.getEntry().orElse(null);
        QualifiedName name = local == null ? qualified : local.getObject().getName();
        return resolution(name, local == null ? identity(name) : local.getObject().getObjectId(), local, overlay);
    }

    Resolution canonical(QualifiedName name, ObjectId object) {
        for (CatalogEntry entry : context.getSession().getTemporaryCatalog().values()) {
            if (entry.getObject().getObjectId().equals(object) || entry.getObject().getName().equals(name)) {
                return resolution(entry.getObject().getName(), entry.getObject().getObjectId(), entry, null);
            }
        }
        OverlayEntry overlay = context.getSession().getPersistentOverlay().get(name);
        CatalogEntry local = overlay == null ? null : overlay.getEntry().orElse(null);
        return resolution(name, local == null ? object : local.getObject().getObjectId(), local, overlay);
    }

    ObjectId identity(QualifiedName name) {
        return new ObjectId(context.getMetadataContext().getSourceId() + ":"
                + context.getMetadataContext().getClusterName() + ":" + name.getDisplayName());
    }

    LookupResult<RelationSchema> schema(Resolution resolution) {
        resolution.requireAvailable();
        if (resolution.uncertain) {
            return LookupResult.unknown(DiagnosticBag.empty());
        }
        if (resolution.local != null) {
            return resolution.local.getSchema().isPresent() ? LookupResult.found(resolution.local.getSchema().get())
                    : LookupResult.unknown(DiagnosticBag.empty());
        }
        return context.getMetadata().lookupSchema(resolution.name, context.getMetadataContext());
    }

    LookupStatus existence(QualifiedName name, ObjectId object, boolean temporary) {
        if (temporary) {
            for (CatalogEntry entry : context.getSession().getTemporaryCatalog().values()) {
                if (entry.getObject().getName().equals(name)) {
                    return context.getSession().getUncertainty().getObjects().contains(entry.getObject().getObjectId())
                            ? LookupStatus.UNKNOWN : LookupStatus.FOUND;
                }
            }
            return LookupStatus.NOT_FOUND;
        }
        OverlayEntry overlay = context.getSession().getPersistentOverlay().get(name);
        if (overlay != null && overlay.getState() == OverlayEntry.State.DELETED) {
            return LookupStatus.NOT_FOUND;
        }
        if (context.getSession().getUncertainty().getObjects().contains(object)) {
            return LookupStatus.UNKNOWN;
        }
        if (overlay != null) {
            return overlay.getState() == OverlayEntry.State.UPSERTED ? LookupStatus.FOUND : LookupStatus.UNKNOWN;
        }
        return context.getSession().getUncertainty().isCatalogUncertain() ? LookupStatus.UNKNOWN
                : context.getMetadata().lookupObject(name, context.getMetadataContext()).getStatus();
    }

    private Resolution resolution(QualifiedName name, ObjectId object, CatalogEntry local, OverlayEntry overlay) {
        boolean deleted = overlay != null && overlay.getState() == OverlayEntry.State.DELETED;
        boolean uncertain = context.getSession().getUncertainty().getObjects().contains(object)
                || overlay != null && overlay.getState() == OverlayEntry.State.UNKNOWN
                || local == null && context.getSession().getUncertainty().isCatalogUncertain();
        return new Resolution(name, object, local, deleted, uncertain);
    }

    private String last(QualifiedName name) {
        return SemanticNames.normalize(name.getParts().get(name.getParts().size() - 1));
    }

    static final class Resolution {
        final QualifiedName name;
        final ObjectId object;
        final CatalogEntry local;
        final boolean deleted;
        final boolean uncertain;

        Resolution(QualifiedName name, ObjectId object, CatalogEntry local, boolean deleted, boolean uncertain) {
            this.name = name;
            this.object = object;
            this.local = local;
            this.deleted = deleted;
            this.uncertain = uncertain;
        }

        void requireAvailable() {
            if (deleted) {
                throw new BindingFailure("OBJECT_NOT_FOUND", "Object was dropped earlier in this script");
            }
        }

        BoundObjectReference.ObjectKind kind() {
            if (local == null) {
                return BoundObjectReference.ObjectKind.TABLE;
            }
            switch (local.getObject().getKind()) {
                case TEMPORARY_TABLE:
                    return BoundObjectReference.ObjectKind.TEMPORARY;
                case VIEW:
                    return BoundObjectReference.ObjectKind.VIEW;
                default:
                    return BoundObjectReference.ObjectKind.TABLE;
            }
        }
    }
}
