package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
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
import com.panfan.hivelineage.v2.session.CatalogEntry;
import com.panfan.hivelineage.v2.session.OverlayEntry;
import com.panfan.hivelineage.v2.syntax.AlterActionSyntax;
import com.panfan.hivelineage.v2.syntax.ChangeColumnActionSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnChangeKindSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnDefinitionSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnPositionKindSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnsActionSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionBodySyntax;
import com.panfan.hivelineage.v2.syntax.RenameActionSyntax;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Structural ALTER changes use a known local/read schema, otherwise retain uncertainty. */
final class CatalogActionBinder {
    private final BindingContext context;

    CatalogActionBinder(BindingContext context) {
        this.context = context;
    }

    List<BoundEffect> alter(DefinitionBodySyntax body, QualifiedName name, ObjectId object,
                           NodeId id, Effects.Precondition precondition) {
        List<BoundEffect> effects = new ArrayList<>();
        List<ColumnMetadata> columns = null;
        int actionIndex = 0;
        for (AlterActionSyntax action : body.getAlterActions()) {
            NodeId actionId = new NodeId(id.getValue() + "/" + actionIndex++);
            if (action instanceof RenameActionSyntax) {
                effects.add(new Effects.RenameObject(actionId, Effects.Certainty.CERTAIN, precondition, object,
                    SemanticNames.qualify(((RenameActionSyntax) action).getNewName(), context, false), name));
                continue;
            }
            if (!(action instanceof ColumnsActionSyntax) && !(action instanceof ChangeColumnActionSyntax)) {
                effects.add(new Effects.UnknownEffect(actionId, Effects.Certainty.UNKNOWN, precondition,
                    Collections.singletonList(object), Collections.<Effects.SessionKey>emptyList(), true,
                    "CATALOG_ACTION_NOT_BOUND"));
                continue;
            }
            if (columns == null) {
                RelationSchema schema = schema(name, object);
                if (schema != null && schema.getState() == SchemaState.KNOWN) {
                    columns = new ArrayList<>(schema.getColumns());
                }
            }
            if (columns == null) {
                effects.add(new Effects.UnknownEffect(actionId, Effects.Certainty.UNKNOWN, precondition,
                    Collections.singletonList(object), Collections.<Effects.SessionKey>emptyList(), true, "CATALOG_SCHEMA_UNKNOWN"));
                continue;
            }
            if (action instanceof ColumnsActionSyntax) {
                ColumnsActionSyntax change = (ColumnsActionSyntax) action;
                if (change.getKind() == ColumnChangeKindSyntax.REPLACE) {
                    List<ColumnMetadata> partitions = new ArrayList<>();
                    for (ColumnMetadata column : columns) {
                        if (column.getRole() == ColumnMetadata.Role.PARTITION) {
                            partitions.add(column);
                        }
                    }
                    columns.clear();
                    addColumns(columns, change.getColumns());
                    columns.addAll(partitions);
                } else {
                    addColumns(columns, change.getColumns());
                }
            } else {
                ChangeColumnActionSyntax change = (ChangeColumnActionSyntax) action;
                int position = find(columns, change.getOriginalName().getValue());
                if (position < 0) {
                    throw new BindingFailure("COLUMN_NOT_FOUND", "ALTER column does not exist in the known schema");
                }
                ColumnMetadata previous = columns.remove(position);
                if (change.getPosition().getKind() == ColumnPositionKindSyntax.FIRST) {
                    position = 0;
                } else if (change.getPosition().getKind() == ColumnPositionKindSyntax.AFTER) {
                    int preceding = find(columns, change.getPosition().getAfterColumn().getValue());
                    if (preceding < 0) {
                        throw new BindingFailure("COLUMN_NOT_FOUND", "ALTER AFTER column does not exist");
                    }
                    position = preceding + 1;
                }
                ColumnMetadata replacement = new ColumnMetadata(SemanticNames.normalize(change.getNewDefinition().getName().getValue()),
                    position, SemanticTypes.declared(change.getNewDefinition().getType()), previous.getRole(), previous.getNullability());
                columns.add(Math.min(position, columns.size()), replacement);
            }
            List<OutputSlot> slots = new ArrayList<>();
            int position = 0;
            for (ColumnMetadata column : columns) {
                String columnName = SemanticNames.normalize(column.getName());
                FieldIdentity field = new FieldIdentity(object.getValue() + "/field/" + columnName, columnName);
                slots.add(new OutputSlot(new SlotId(actionId.getValue() + "/column-" + position++), columnName, column.getType(), field));
            }
            effects.add(new Effects.AlterObject(actionId, Effects.Certainty.CERTAIN, precondition,
                new Effects.ObjectShape(object, name, body.getOptions().isTemporary() ? Effects.ObjectKind.TEMPORARY_TABLE
                    : Effects.ObjectKind.TABLE, OutputSchema.known(slots), VersionRef.notRequested())));
        }
        return effects;
    }

    RelationSchema schema(QualifiedName name, ObjectId object) {
        if (context.getSession().getUncertainty().getObjects().contains(object)) {
            return null;
        }
        OverlayEntry overlay = context.getSession().getPersistentOverlay().get(name);
        if (overlay != null) {
            if (overlay.getEntry().isPresent()) {
                return overlay.getEntry().get().getSchema().orElse(null);
            }
            return null;
        }
        for (CatalogEntry entry : context.getSession().getTemporaryCatalog().values()) {
            if (entry.getObject().getObjectId().equals(object)) {
                return entry.getSchema().orElse(null);
            }
        }
        LookupResult<RelationSchema> metadata = context.getMetadata().lookupSchema(name, context.getMetadataContext());
        return metadata.getStatus() == LookupStatus.FOUND ? metadata.getValue().get() : null;
    }

    private void addColumns(List<ColumnMetadata> columns, List<ColumnDefinitionSyntax> additions) {
        for (ColumnDefinitionSyntax addition : additions) {
            String name = SemanticNames.normalize(addition.getName().getValue());
            if (find(columns, name) >= 0) {
                throw new BindingFailure("DUPLICATE_COLUMN", "ALTER declares a duplicate column");
            }
            columns.add(new ColumnMetadata(name, columns.size(), SemanticTypes.declared(addition.getType()),
                ColumnMetadata.Role.DATA, ColumnMetadata.Nullability.UNKNOWN));
        }
    }

    private int find(List<ColumnMetadata> columns, String name) {
        for (int index = 0; index < columns.size(); index++) {
            if (SemanticNames.normalize(columns.get(index).getName()).equals(SemanticNames.normalize(name))) {
                return index;
            }
        }
        return -1;
    }
}

