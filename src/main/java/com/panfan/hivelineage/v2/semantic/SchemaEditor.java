package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.metadata.ColumnMetadata;
import com.panfan.hivelineage.v2.syntax.ChangeColumnActionSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnChangeKindSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnDefinitionSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnPositionKindSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnsActionSyntax;

import java.util.ArrayList;
import java.util.List;

/** Immutable schema edits keep names, ordinal positions and partition roles consistent. */
final class SchemaEditor {
    private final List<ColumnMetadata> columns;

    SchemaEditor(List<ColumnMetadata> columns) {
        this.columns = indexed(columns);
    }

    List<ColumnMetadata> getColumns() {
        return columns;
    }

    SchemaEditor apply(ColumnsActionSyntax action) {
        List<ColumnMetadata> edited = new ArrayList<>();
        for (ColumnMetadata column : columns) {
            if (action.getKind() != ColumnChangeKindSyntax.REPLACE || column.getRole() == ColumnMetadata.Role.PARTITION) {
                edited.add(column);
            }
        }
        for (ColumnDefinitionSyntax addition : action.getColumns()) {
            String name = SemanticNames.normalize(addition.getName().getValue());
            requireUnique(edited, name);
            int position = firstPartition(edited);
            edited.add(position, new ColumnMetadata(name, position, SemanticTypes.declared(addition.getType()),
                    ColumnMetadata.Role.DATA, ColumnMetadata.Nullability.UNKNOWN));
        }
        return new SchemaEditor(edited);
    }

    SchemaEditor apply(ChangeColumnActionSyntax action) {
        List<ColumnMetadata> edited = new ArrayList<>(columns);
        int position = find(edited, action.getOriginalName().getValue());
        if (position < 0) {
            throw new BindingFailure("COLUMN_NOT_FOUND", "ALTER column does not exist in the known schema");
        }
        ColumnMetadata previous = edited.remove(position);
        if (previous.getRole() == ColumnMetadata.Role.PARTITION) {
            throw new BindingFailure("INVALID_PARTITION_COLUMN_CHANGE", "CHANGE COLUMN cannot modify a partition key");
        }
        String name = SemanticNames.normalize(action.getNewDefinition().getName().getValue());
        requireUnique(edited, name);
        position = insertionPosition(edited, action, position);
        edited.add(Math.min(position, edited.size()), new ColumnMetadata(name, position,
                SemanticTypes.declared(action.getNewDefinition().getType()), previous.getRole(), previous.getNullability()));
        return new SchemaEditor(edited);
    }

    private int insertionPosition(List<ColumnMetadata> edited, ChangeColumnActionSyntax action, int previous) {
        if (action.getPosition().getKind() == ColumnPositionKindSyntax.FIRST) {
            return 0;
        }
        if (action.getPosition().getKind() != ColumnPositionKindSyntax.AFTER) {
            return previous;
        }
        int preceding = find(edited, action.getPosition().getAfterColumn().getValue());
        if (preceding < 0) {
            throw new BindingFailure("COLUMN_NOT_FOUND", "ALTER AFTER column does not exist");
        }
        if (edited.get(preceding).getRole() == ColumnMetadata.Role.PARTITION) {
            throw new BindingFailure("INVALID_COLUMN_POSITION", "Data columns must precede partition keys");
        }
        return preceding + 1;
    }

    private void requireUnique(List<ColumnMetadata> edited, String name) {
        if (find(edited, name) >= 0) {
            throw new BindingFailure("DUPLICATE_COLUMN", "ALTER declares a duplicate column");
        }
    }

    private int firstPartition(List<ColumnMetadata> edited) {
        for (int index = 0; index < edited.size(); index++) {
            if (edited.get(index).getRole() == ColumnMetadata.Role.PARTITION) {
                return index;
            }
        }
        return edited.size();
    }

    private int find(List<ColumnMetadata> edited, String name) {
        for (int index = 0; index < edited.size(); index++) {
            if (SemanticNames.normalize(edited.get(index).getName()).equals(SemanticNames.normalize(name))) {
                return index;
            }
        }
        return -1;
    }

    private static List<ColumnMetadata> indexed(List<ColumnMetadata> columns) {
        List<ColumnMetadata> indexed = new ArrayList<>();
        for (ColumnMetadata column : columns) {
            indexed.add(new ColumnMetadata(column.getName(), indexed.size(), column.getType(), column.getRole(), column.getNullability()));
        }
        return ModelCollections.immutableList(indexed);
    }
}
