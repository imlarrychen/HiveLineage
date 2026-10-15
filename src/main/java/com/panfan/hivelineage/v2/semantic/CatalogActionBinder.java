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
import com.panfan.hivelineage.v2.metadata.ObjectDescriptor;
import com.panfan.hivelineage.v2.metadata.RelationSchema;
import com.panfan.hivelineage.v2.session.CatalogEntry;
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
        CatalogEntry local = local(name, object);
        Effects.ObjectKind objectKind = local != null && local.getObject().getKind() == ObjectDescriptor.Kind.TEMPORARY_TABLE
            ? Effects.ObjectKind.TEMPORARY_TABLE : local != null && local.getObject().getKind() == ObjectDescriptor.Kind.VIEW
                ? Effects.ObjectKind.VIEW : body.getKind() == com.panfan.hivelineage.v2.syntax.DefinitionKindSyntax.ALTER_VIEW
                    ? Effects.ObjectKind.VIEW : Effects.ObjectKind.TABLE;
        int actionIndex = 0;
        for (AlterActionSyntax action : body.getAlterActions()) {
            NodeId actionId = new NodeId(id.getValue() + "/" + actionIndex++);
            if (action instanceof RenameActionSyntax) {
                effects.add(new Effects.RenameObject(actionId, Effects.Certainty.CERTAIN, precondition, object,
                    SemanticNames.qualify(((RenameActionSyntax) action).getNewName(), context,
                        objectKind == Effects.ObjectKind.TEMPORARY_TABLE), name));
                continue;
            }
            if (!(action instanceof ColumnsActionSyntax) && !(action instanceof ChangeColumnActionSyntax)) {
                effects.add(new Effects.UnknownEffect(actionId, Effects.Certainty.UNKNOWN, precondition,
                    Collections.singletonList(object), Collections.<Effects.SessionKey>emptyList(), false,
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
                    Collections.singletonList(object), Collections.<Effects.SessionKey>emptyList(), false, "CATALOG_SCHEMA_UNKNOWN"));
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
                    columns.addAll(partitions);
                    addColumns(columns, change.getColumns());
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
                if (previous.getRole() == ColumnMetadata.Role.PARTITION) {
                    throw new BindingFailure("INVALID_PARTITION_COLUMN_CHANGE", "CHANGE COLUMN cannot modify a partition key");
                }
                if (find(columns, change.getNewDefinition().getName().getValue()) >= 0) {
                    throw new BindingFailure("DUPLICATE_COLUMN", "ALTER declares a duplicate column");
                }
                if (change.getPosition().getKind() == ColumnPositionKindSyntax.FIRST) {
                    position = 0;
                } else if (change.getPosition().getKind() == ColumnPositionKindSyntax.AFTER) {
                    int preceding = find(columns, change.getPosition().getAfterColumn().getValue());
                    if (preceding < 0) {
                        throw new BindingFailure("COLUMN_NOT_FOUND", "ALTER AFTER column does not exist");
                    }
                    if (columns.get(preceding).getRole() == ColumnMetadata.Role.PARTITION) {
                        throw new BindingFailure("INVALID_COLUMN_POSITION", "Data columns must precede partition keys");
                    }
                    position = preceding + 1;
                }
                ColumnMetadata replacement = new ColumnMetadata(SemanticNames.normalize(change.getNewDefinition().getName().getValue()),
                    position, SemanticTypes.declared(change.getNewDefinition().getType()), previous.getRole(), previous.getNullability());
                columns.add(Math.min(position, columns.size()), replacement);
            }
            List<OutputSlot> slots = new ArrayList<>();
            List<String> partitions = new ArrayList<>();
            int position = 0;
            for (ColumnMetadata column : columns) {
                String columnName = SemanticNames.normalize(column.getName());
                if (column.getRole() == ColumnMetadata.Role.PARTITION) {
                    partitions.add(columnName);
                }
                FieldIdentity field = new FieldIdentity(object.getValue() + "/field/" + columnName, columnName);
                slots.add(new OutputSlot(new SlotId(actionId.getValue() + "/column-" + position++), columnName, column.getType(), field));
            }
            effects.add(new Effects.AlterObject(actionId, Effects.Certainty.CERTAIN, precondition,
                new Effects.ObjectShape(object, name, objectKind, OutputSchema.known(slots), VersionRef.notRequested(),
                    local == null ? Collections.singletonList(name) : local.getLookupNames(), partitions)));
        }
        return effects;
    }

    RelationSchema schema(QualifiedName name, ObjectId object) {
        CatalogObjectResolver resolver = new CatalogObjectResolver(context);
        LookupResult<RelationSchema> result = resolver.schema(resolver.canonical(name, object));
        return result.getStatus() == LookupStatus.FOUND ? result.getValue().get() : null;
    }

    private CatalogEntry local(QualifiedName name, ObjectId object) {
        return new CatalogObjectResolver(context).canonical(name, object).local;
    }

    private void addColumns(List<ColumnMetadata> columns, List<ColumnDefinitionSyntax> additions) {
        for (ColumnDefinitionSyntax addition : additions) {
            String name = SemanticNames.normalize(addition.getName().getValue());
            if (find(columns, name) >= 0) {
                throw new BindingFailure("DUPLICATE_COLUMN", "ALTER declares a duplicate column");
            }
            int position = columns.size();
            for (int index = 0; index < columns.size(); index++) {
                if (columns.get(index).getRole() == ColumnMetadata.Role.PARTITION) {
                    position = index;
                    break;
                }
            }
            columns.add(position, new ColumnMetadata(name, position, SemanticTypes.declared(addition.getType()),
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
