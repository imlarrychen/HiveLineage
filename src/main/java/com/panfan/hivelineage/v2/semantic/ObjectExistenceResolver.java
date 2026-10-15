package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.metadata.LookupStatus;

/**
 * Script facts take precedence over a pinned external snapshot for catalog preconditions.
 */
final class ObjectExistenceResolver {
    private final BindingContext context;

    ObjectExistenceResolver(BindingContext context) {
        this.context = context;
    }

    LookupStatus lookup(QualifiedName name, ObjectId object, boolean temporary) {
        return new CatalogObjectResolver(context).existence(name, object, temporary);
    }
}
