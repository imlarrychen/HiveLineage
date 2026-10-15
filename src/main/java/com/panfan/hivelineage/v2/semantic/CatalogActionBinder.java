package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.metadata.ColumnMetadata;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.LookupStatus;
import com.panfan.hivelineage.v2.metadata.RelationSchema;
import com.panfan.hivelineage.v2.syntax.AlterActionSyntax;
import com.panfan.hivelineage.v2.syntax.ChangeColumnActionSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnsActionSyntax;
import com.panfan.hivelineage.v2.syntax.RenameActionSyntax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Binds ALTER intent; SchemaEditor alone owns column mutations and their invariants. */
final class CatalogActionBinder {
    List<BoundEffect> alter(CatalogEffectRequest request) {
        List<BoundEffect> effects = new ArrayList<>();
        SchemaEditor schema = null;
        int actionIndex = 0;
        for (AlterActionSyntax action : request.body().getAlterActions()) {
            NodeId id = new NodeId(request.id().getValue() + "/" + actionIndex++);
            if (action instanceof RenameActionSyntax) {
                effects.add(rename(request, (RenameActionSyntax) action, id));
            } else if (action instanceof ColumnsActionSyntax || action instanceof ChangeColumnActionSyntax) {
                schema = schema == null ? loadSchema(request) : schema;
                if (schema == null) {
                    effects.add(unknown(request, id, "CATALOG_SCHEMA_UNKNOWN"));
                } else {
                    schema = edit(schema, action);
                    effects.add(alter(request, id, schema));
                }
            } else {
                effects.add(unknown(request, id, "CATALOG_ACTION_NOT_BOUND"));
            }
        }
        return effects;
    }

    private SchemaEditor edit(SchemaEditor schema, AlterActionSyntax action) {
        if (action instanceof ColumnsActionSyntax) {
            return schema.apply((ColumnsActionSyntax) action);
        }
        return schema.apply((ChangeColumnActionSyntax) action);
    }

    private SchemaEditor loadSchema(CatalogEffectRequest request) {
        CatalogObjectResolver resolver = new CatalogObjectResolver(request.context());
        LookupResult<RelationSchema> result = resolver.schema(resolver.canonical(request.name(), request.object()));
        if (result.getStatus() != LookupStatus.FOUND || result.getValue().get().getState() != SchemaState.KNOWN) {
            return null;
        }
        return new SchemaEditor(result.getValue().get().getColumns());
    }

    private BoundEffect rename(CatalogEffectRequest request, RenameActionSyntax action, NodeId id) {
        return new Effects.RenameObject(id, Effects.Certainty.CERTAIN, request.precondition(), request.object(),
                SemanticNames.qualify(action.getNewName(), request.context(), request.kind() == Effects.ObjectKind.TEMPORARY_TABLE),
                request.name());
    }

    private BoundEffect unknown(CatalogEffectRequest request, NodeId id, String code) {
        return new Effects.UnknownEffect(id, Effects.Certainty.UNKNOWN, request.precondition(),
                Collections.singletonList(request.object()), Collections.<Effects.SessionKey>emptyList(), false, code);
    }

    private BoundEffect alter(CatalogEffectRequest request, NodeId id, SchemaEditor schema) {
        List<OutputSlot> slots = new ArrayList<>();
        List<String> partitions = new ArrayList<>();
        for (ColumnMetadata column : schema.getColumns()) {
            String name = SemanticNames.normalize(column.getName());
            if (column.getRole() == ColumnMetadata.Role.PARTITION) {
                partitions.add(name);
            }
            FieldIdentity field = new FieldIdentity(request.object().getValue() + "/field/" + name, name);
            slots.add(new OutputSlot(new SlotId(id.getValue() + "/column-" + slots.size()), name, column.getType(), field));
        }
        return new Effects.AlterObject(id, Effects.Certainty.CERTAIN, request.precondition(),
                new Effects.ObjectShape(request.object(), request.name(), request.kind(), OutputSchema.known(slots),
                        VersionRef.notRequested(), request.lookupNames(), partitions));
    }
}
