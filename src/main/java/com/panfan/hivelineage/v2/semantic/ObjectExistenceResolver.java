package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.metadata.LookupStatus;
import com.panfan.hivelineage.v2.session.CatalogEntry;
import com.panfan.hivelineage.v2.session.OverlayEntry;

/** Script facts take precedence over a pinned external snapshot for catalog preconditions. */
final class ObjectExistenceResolver {
    private final BindingContext context;

    ObjectExistenceResolver(BindingContext context) {
        this.context = context;
    }

    LookupStatus lookup(QualifiedName name, ObjectId object, boolean temporary) {
        OverlayEntry overlay = context.getSession().getPersistentOverlay().get(name);
        if (overlay != null && overlay.getState() == OverlayEntry.State.DELETED) {
            return LookupStatus.NOT_FOUND;
        }
        if (context.getSession().getUncertainty().getObjects().contains(object)) {
            return LookupStatus.UNKNOWN;
        }
        if (temporary) {
            for (CatalogEntry entry : context.getSession().getTemporaryCatalog().values()) {
                if (entry.getObject().getName().equals(name)) {
                    return LookupStatus.FOUND;
                }
            }
            return LookupStatus.NOT_FOUND;
        }
        if (overlay != null) {
            return overlay.getState() == OverlayEntry.State.UPSERTED ? LookupStatus.FOUND : LookupStatus.UNKNOWN;
        }
        if (context.getSession().getUncertainty().isCatalogUncertain()) {
            return LookupStatus.UNKNOWN;
        }
        return context.getMetadata().lookupObject(name, context.getMetadataContext()).getStatus();
    }
}
