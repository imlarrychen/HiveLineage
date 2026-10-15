package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.metadata.ColumnMetadata;
import com.panfan.hivelineage.v2.syntax.ChangeColumnActionSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnChangeKindSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnDefinitionSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnPositionKindSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnPositionSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnsActionSyntax;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.QuoteKind;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** Schema rules can be exercised without catalog, session, parser or query binding. */
public class SchemaEditorTest {
    @Test
    public void addAndReplacePreservePartitionRolesAndReindexEveryColumn() {
        SchemaEditor original = schema();
        SchemaEditor added = original.apply(new ColumnsActionSyntax(ColumnChangeKindSyntax.ADD,
                Collections.singletonList(definition("c"))));
        assertNames(original, "a", "b", "dt");
        assertNames(added, "a", "b", "c", "dt");
        assertEquals(ColumnMetadata.Role.PARTITION, added.getColumns().get(3).getRole());
        SchemaEditor replaced = added.apply(new ColumnsActionSyntax(ColumnChangeKindSyntax.REPLACE,
                Arrays.asList(definition("x"), definition("y"))));
        assertNames(replaced, "x", "y", "dt");
        assertEquals(ColumnMetadata.Role.PARTITION, replaced.getColumns().get(2).getRole());
        assertThrows(UnsupportedOperationException.class, () -> replaced.getColumns().clear());
    }

    @Test
    public void firstAndAfterChangesPreserveNullabilityAndDoNotMutatePreviousSchema() {
        SchemaEditor original = schema();
        SchemaEditor first = original.apply(change("b", "B2", ColumnPositionKindSyntax.FIRST, null));
        assertNames(first, "b2", "a", "dt");
        assertEquals(ColumnMetadata.Nullability.NOT_NULL, first.getColumns().get(0).getNullability());
        SchemaEditor after = first.apply(change("b2", "b3", ColumnPositionKindSyntax.AFTER, "a"));
        assertNames(after, "a", "b3", "dt");
        assertNames(original, "a", "b", "dt");
    }

    @Test
    public void failedEditsKeepOriginalSchemaAndReportTheExistingFailureCode() {
        SchemaEditor original = schema();
        assertFailure(original, change("missing", "x", ColumnPositionKindSyntax.UNCHANGED, null), "COLUMN_NOT_FOUND");
        assertFailure(original, change("dt", "day", ColumnPositionKindSyntax.FIRST, null), "INVALID_PARTITION_COLUMN_CHANGE");
        assertFailure(original, change("a", "B", ColumnPositionKindSyntax.UNCHANGED, null), "DUPLICATE_COLUMN");
        assertFailure(original, change("a", "x", ColumnPositionKindSyntax.AFTER, "missing"), "COLUMN_NOT_FOUND");
        assertFailure(original, change("a", "x", ColumnPositionKindSyntax.AFTER, "dt"), "INVALID_COLUMN_POSITION");
        assertNames(original, "a", "b", "dt");
    }

    @Test
    public void replacementCannotReusePartitionNamesOrPartiallyApplyDuplicateAdditions() {
        SchemaEditor original = schema();
        BindingFailure partition = assertThrows(BindingFailure.class, () -> original.apply(new ColumnsActionSyntax(
                ColumnChangeKindSyntax.REPLACE, Collections.singletonList(definition("DT")))));
        assertEquals("DUPLICATE_COLUMN", partition.getCode());
        BindingFailure duplicate = assertThrows(BindingFailure.class, () -> original.apply(new ColumnsActionSyntax(
                ColumnChangeKindSyntax.ADD, Arrays.asList(definition("c"), definition("C")))));
        assertEquals("DUPLICATE_COLUMN", duplicate.getCode());
        assertNames(original, "a", "b", "dt");
    }

    private void assertFailure(SchemaEditor original, ChangeColumnActionSyntax action, String code) {
        BindingFailure failure = assertThrows(BindingFailure.class, () -> original.apply(action));
        assertEquals(code, failure.getCode());
    }

    private static SchemaEditor schema() {
        return new SchemaEditor(Arrays.asList(
                new ColumnMetadata("a", 0, TypeRef.unknown(), ColumnMetadata.Role.DATA, ColumnMetadata.Nullability.UNKNOWN),
                new ColumnMetadata("b", 1, TypeRef.unknown(), ColumnMetadata.Role.DATA, ColumnMetadata.Nullability.NOT_NULL),
                new ColumnMetadata("dt", 2, TypeRef.unknown(), ColumnMetadata.Role.PARTITION, ColumnMetadata.Nullability.UNKNOWN)));
    }

    private static ChangeColumnActionSyntax change(String original, String replacement, ColumnPositionKindSyntax kind, String after) {
        return new ChangeColumnActionSyntax(identifier(original), definition(replacement),
                new ColumnPositionSyntax(kind, after == null ? null : identifier(after)));
    }

    private static ColumnDefinitionSyntax definition(String name) {
        return new ColumnDefinitionSyntax(identifier(name), null, null);
    }

    private static IdentifierPart identifier(String name) {
        return new IdentifierPart(name, name, QuoteKind.NONE, SourceRef.synthetic("test"));
    }

    private static void assertNames(SchemaEditor schema, String... expected) {
        List<ColumnMetadata> columns = schema.getColumns();
        assertEquals(Arrays.asList(expected), columns.stream().map(ColumnMetadata::getName).collect(Collectors.toList()));
        for (int index = 0; index < columns.size(); index++) {
            assertEquals(index, columns.get(index).getPosition());
        }
    }
}
