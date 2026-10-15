package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.api.StatementAnalysisResult;
import com.panfan.hivelineage.v2.api.StatementStatus;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.KnownOutputSegment;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.syntax.ChangeColumnActionSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnPositionKindSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionStatementSyntax;
import com.panfan.hivelineage.v2.syntax.UnknownAlterActionSyntax;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.analyzeWithCatalog;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertColumnSources;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.assertNoFailed;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.statement;
import static com.panfan.hivelineage.v2.testing.GeneratedSqlSupport.syntax;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * File-backed DDL tests inspect catalog proposals and later reads, including explicit uncertainty.
 */
@RunWith(Parameterized.class)
public class DdlCatalogEffectsIntegrationTest {
    private final LineageParseMode mode;

    public DdlCatalogEffectsIntegrationTest(LineageParseMode mode) {
        this.mode = mode;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> modes() {
        return Arrays.stream(LineageParseMode.values()).map(mode -> new Object[]{mode}).collect(Collectors.toList());
    }

    public static Set<String> scenarioFiles() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                "v2_ddl_effect_change_column_first_after_comments.sql",
                "v2_ddl_effect_replace_preserves_partition_roles.sql",
                "v2_ddl_effect_partition_scoped_column_changes_unknown.sql",
                "v2_ddl_effect_partition_add_drop_rename_unknown.sql",
                "v2_ddl_effect_serde_fileformat_bucket_unknown.sql",
                "v2_ddl_effect_maintenance_touch_compact_stats_unknown.sql",
                "v2_ddl_effect_temporary_shadow_alter_drop.sql",
                "v2_ddl_effect_rename_chain_identity_then_drop.sql",
                "v2_ddl_effect_view_rename_drop_if_exists.sql",
                "v2_ddl_effect_if_not_exists_known_noop_and_recreate.sql",
                "v2_ddl_effect_if_not_exists_unknown_precondition.sql",
                "v2_ddl_effect_create_like_unknown_schema.sql",
                "v2_ddl_effect_truncate_partition_unknown.sql",
                "v2_ddl_effect_change_column_invalid_keeps_schema.sql",
                "v2_ddl_effect_temporary_rename_alter_drop_aliases.sql",
                "v2_ddl_effect_replace_partition_name_collision.sql")));
    }

    @Test
    public void changeColumnKeepsTypeCommentAndFirstAfterOrderAndRejectsOldName() {
        String file = "v2_ddl_effect_change_column_first_after_comments.sql";
        ScriptAnalysisResult result = catalog(file, 6, 1);
        Effects.ObjectShape first = shape(result, 1);
        assertNames(first, "total", "id", "name");
        assertEquals(TypeRef.Kind.DECIMAL, slots(first).get(0).getType().getKind());
        assertNames(shape(result, 2), "total", "name", "user_id");
        ChangeColumnActionSyntax firstSyntax = (ChangeColumnActionSyntax) definition(file, 1).getDefinition().getAlterActions().get(0);
        assertEquals(ColumnPositionKindSyntax.FIRST, firstSyntax.getPosition().getKind());
        assertEquals("renamed total", firstSyntax.getNewDefinition().getComment());
        ChangeColumnActionSyntax afterSyntax = (ChangeColumnActionSyntax) definition(file, 2).getDefinition().getAlterActions().get(0);
        assertEquals(ColumnPositionKindSyntax.AFTER, afterSyntax.getPosition().getKind());
        assertEquals("name", afterSyntax.getPosition().getAfterColumn().getValue());
        success(result, 3);
        assertScanSchema(result, 3, "total", "name", "user_id");
        assertColumnSources(statement(result, 3), "dwd.result.amount", "dwd.ddl_change.total");
        assertColumnSources(statement(result, 3), "dwd.result.id", "dwd.ddl_change.user_id");
        failed(result, 4, "COLUMN_NOT_FOUND");
        success(result, 5);
        assertColumnSources(statement(result, 5), "dwd.result.id", "dwd.ddl_change.user_id");
    }

    @Test
    public void addAndReplaceKeepPartitionRolesAndDataColumnsBeforePartitionKeys() {
        ScriptAnalysisResult result = catalog("v2_ddl_effect_replace_preserves_partition_roles.sql", 5, 0);
        assertNames(shape(result, 0), "id", "name", "dt", "region");
        assertNames(shape(result, 1), "id", "name", "amount", "dt", "region");
        assertNames(shape(result, 2), "id", "amount", "dt", "region");
        assertNames(shape(result, 3), "id", "amount", "name", "dt", "region");
        for (int index = 0; index < 4; index++) {
            assertEquals(Arrays.asList("dt", "region"), shape(result, index).getPartitionColumns());
        }
        success(result, 4);
        assertScanSchema(result, 4, "id", "amount", "name", "dt", "region");
        assertColumnSources(statement(result, 4), "dwd.result.dt", "dwd.ddl_partition_roles.dt");
        assertColumnSources(statement(result, 4), "dwd.result.tag", "dwd.ddl_partition_roles.region");
    }

    @Test
    public void partitionScopedColumnsDoNotChangeTheWholeTableSchema() {
        String file = "v2_ddl_effect_partition_scoped_column_changes_unknown.sql";
        ScriptAnalysisResult result = catalog(file, 6, 0);
        assertUnknownActions(file, result, 3);
        assertNames(shape(result, 0), "id", "dt");
        uncertainRead(result, 4, "SESSION_OBJECT_UNCERTAIN");
        success(result, 5);
        assertColumnSources(statement(result, 5), "dwd.result.id", "ods.users.id");
    }

    @Test
    public void partitionAddDropAndRenameRetainUnknownEffectsInsteadOfEmptyActions() {
        String file = "v2_ddl_effect_partition_add_drop_rename_unknown.sql";
        ScriptAnalysisResult result = catalog(file, 5, 0);
        assertUnknownActions(file, result, 3);
        uncertainRead(result, 4, "SESSION_OBJECT_UNCERTAIN");
    }

    @Test
    public void serdeFormatAndBucketingHaveExplicitObjectScopedBoundaries() {
        String file = "v2_ddl_effect_serde_fileformat_bucket_unknown.sql";
        ScriptAnalysisResult result = catalog(file, 10, 0);
        assertUnknownActions(file, result, 4);
        uncertainRead(result, 5, "SESSION_OBJECT_UNCERTAIN");
        assertEquals(shape(result, 0).getObjectId(), ((Effects.RenameObject) effect(result, 6)).getObjectId());
        Effects.UnknownEffect addition = (Effects.UnknownEffect) effect(result, 7);
        assertEquals(Effects.Certainty.UNKNOWN, addition.getCertainty());
        assertEquals("CATALOG_SCHEMA_UNKNOWN", addition.getDiagnosticCode());
        assertEquals(Collections.singletonList(shape(result, 0).getObjectId()), addition.getAffectedObjects());
        assertFalse(addition.getCatalogMayChange());
        uncertainRead(result, 8, "SESSION_OBJECT_UNCERTAIN");
        assertEquals(SchemaState.UNKNOWN, unit(result, 8).getRelations().stream()
                .filter(relation -> relation instanceof Relations.Scan).findFirst().get().getOutputSchema().getState());
        success(result, 9);
        assertColumnSources(statement(result, 9), "dwd.result.id", "ods.users.id");
    }

    @Test
    public void maintenanceActionsHaveExplicitObjectScopedBoundaries() {
        String file = "v2_ddl_effect_maintenance_touch_compact_stats_unknown.sql";
        ScriptAnalysisResult result = catalog(file, 6, 0);
        assertUnknownActions(file, result, 4);
        uncertainRead(result, 5, "SESSION_OBJECT_UNCERTAIN");
    }

    @Test
    public void temporaryAlterAndDropWinNameLookupThenRevealThePermanentTable() {
        ScriptAnalysisResult result = catalog("v2_ddl_effect_temporary_shadow_alter_drop.sql", 8, 1);
        Effects.ObjectShape temporary = shape(result, 1);
        assertEquals(Effects.ObjectKind.TEMPORARY_TABLE, temporary.getKind());
        assertNames(shape(result, 2), "id", "name");
        assertEquals(temporary.getObjectId(), shape(result, 2).getObjectId());
        assertEquals(temporary.getLookupNames(), shape(result, 2).getLookupNames());
        success(result, 3);
        assertScanSchema(result, 3, "id", "name");
        Effects.DropObject drop = (Effects.DropObject) effect(result, 4);
        assertEquals(Effects.ObjectKind.TEMPORARY_TABLE, drop.getObjectKind());
        assertEquals(temporary.getObjectId(), drop.getObjectId());
        assertEquals(temporary.getName(), drop.getName().get());
        success(result, 5);
        assertScanSchema(result, 5, "id", "name");
        assertColumnSources(statement(result, 5), "dwd.result.name", "default.ddl_shadow.name");
        assertEquals(Effects.ObjectKind.TABLE, ((Effects.DropObject) effect(result, 6)).getObjectKind());
        failed(result, 7, "OBJECT_NOT_FOUND");
    }

    @Test
    public void sequentialRenamesReuseIdentityForAnotherAlterAndDrop() {
        ScriptAnalysisResult result = catalog("v2_ddl_effect_rename_chain_identity_then_drop.sql", 7, 1);
        ObjectId id = shape(result, 0).getObjectId();
        for (int index = 1; index < 3; index++) {
            assertEquals(id, ((Effects.RenameObject) effect(result, index)).getObjectId());
        }
        assertEquals("dwd.ddl_rename_c", ((Effects.RenameObject) effect(result, 2)).getNewName().getDisplayName());
        assertEquals(id, shape(result, 3).getObjectId());
        assertNames(shape(result, 3), "id", "name", "amount");
        success(result, 4);
        assertScanSchema(result, 4, "id", "name", "amount");
        assertColumnSources(statement(result, 4), "dwd.result.amount", "dwd.ddl_rename_c.amount");
        assertEquals(id, ((Effects.DropObject) effect(result, 5)).getObjectId());
        failed(result, 6, "OBJECT_NOT_FOUND");
    }

    @Test
    public void viewRenameKeepsDefinitionAndDropIfExistsDeletesIt() {
        ScriptAnalysisResult result = catalog("v2_ddl_effect_view_rename_drop_if_exists.sql", 6, 1);
        assertEquals(Effects.ObjectKind.VIEW, shape(result, 0).getKind());
        assertEquals(shape(result, 0).getObjectId(), ((Effects.RenameObject) effect(result, 1)).getObjectId());
        success(result, 2);
        assertColumnSources(statement(result, 2), "dwd.result.id", "dwd.ddl_view_b.id");
        assertColumnSources(statement(result, 2), "dwd.ddl_view_b.id", "ods.users.id");
        Effects.DropObject drop = (Effects.DropObject) effect(result, 3);
        assertEquals(Effects.ObjectKind.VIEW, drop.getObjectKind());
        assertEquals(Effects.Precondition.Kind.OBJECT_PRESENT, drop.getPrecondition().getKind());
        failed(result, 4, "OBJECT_NOT_FOUND");
        assertIgnored(result, 5);
        assertEquals(Effects.Certainty.CERTAIN, effect(result, 5).getCertainty());
        assertEquals(Effects.Precondition.Kind.OBJECT_PRESENT, effect(result, 5).getPrecondition().getKind());
    }

    @Test
    public void knownIfNotExistsIsNoopAndDroppedObjectCanBeRecreated() {
        ScriptAnalysisResult result = catalog("v2_ddl_effect_if_not_exists_known_noop_and_recreate.sql", 6, 0);
        assertTrue(unit(result, 1).getEffects().isEmpty());
        success(result, 2);
        assertScanSchema(result, 2, "id");
        assertColumnSources(statement(result, 2), "dwd.result.id", "dwd.ddl_if_known.id");
        assertEquals(Effects.Precondition.Kind.OBJECT_PRESENT, effect(result, 3).getPrecondition().getKind());
        assertNames(shape(result, 4), "name");
        assertEquals(Effects.Certainty.CERTAIN, effect(result, 4).getCertainty());
        assertEquals(Effects.Precondition.Kind.ALWAYS, effect(result, 4).getPrecondition().getKind());
        success(result, 5);
        assertScanSchema(result, 5, "name");
        assertColumnSources(statement(result, 5), "dwd.result.name", "dwd.ddl_if_known.name");
    }

    @Test
    public void unknownIfNotExistsRetainsConditionalCreationAndUnknownReads() {
        ScriptAnalysisResult result = catalog("v2_ddl_effect_if_not_exists_unknown_precondition.sql", 3, 0);
        assertEquals(Effects.Certainty.CONDITIONAL, effect(result, 0).getCertainty());
        assertEquals(Effects.Precondition.Kind.OBJECT_ABSENT, effect(result, 0).getPrecondition().getKind());
        uncertainRead(result, 1, "SESSION_OBJECT_UNCERTAIN");
        success(result, 2);
        assertColumnSources(statement(result, 2), "dwd.result.id", "ods.users.id");
    }

    @Test
    public void createLikeRetainsUnknownWidthForKnownAndUnknownSources() {
        ScriptAnalysisResult result = catalog("v2_ddl_effect_create_like_unknown_schema.sql", 4, 0);
        for (int index : new int[]{0, 2}) {
            assertIgnored(result, index);
            assertEquals(SchemaState.UNKNOWN, shape(result, index).getSchema().getState());
            diagnostic(result, index, "CREATE_LIKE_SCHEMA_UNKNOWN");
            assertTrue(unit(result, index).getWrites().isEmpty());
            assertTrue(slots(shape(result, index)).isEmpty());
        }
        for (int index : new int[]{1, 3}) {
            assertEquals(mode == LineageParseMode.TABLE_ONLY ? StatementStatus.SUCCESS : StatementStatus.PARTIAL_SUCCESS,
                    result.getStatements().get(index).getStatus());
        }
    }

    @Test
    public void partitionTruncateHasUnknownVersionEffectWithoutADataWrite() {
        ScriptAnalysisResult result = catalog("v2_ddl_effect_truncate_partition_unknown.sql", 4, 0);
        Effects.UnknownEffect truncate = (Effects.UnknownEffect) effect(result, 1);
        assertEquals("TRUNCATE_VERSION_UNKNOWN", truncate.getDiagnosticCode());
        assertEquals(Collections.singletonList(shape(result, 0).getObjectId()), truncate.getAffectedObjects());
        assertFalse(truncate.getCatalogMayChange());
        assertTrue(unit(result, 1).getWrites().isEmpty());
        uncertainRead(result, 2, "SESSION_OBJECT_UNCERTAIN");
        success(result, 3);
    }

    @Test
    public void rejectedColumnChangesKeepTheCommittedSchema() {
        ScriptAnalysisResult result = catalog("v2_ddl_effect_change_column_invalid_keeps_schema.sql", 6, 4);
        String[] codes = {"DUPLICATE_COLUMN", "COLUMN_NOT_FOUND", "INVALID_PARTITION_COLUMN_CHANGE", "INVALID_COLUMN_POSITION"};
        for (int index = 1; index < 5; index++) {
            failed(result, index, codes[index - 1]);
        }
        success(result, 5);
        assertScanSchema(result, 5, "id", "name", "dt");
        assertColumnSources(statement(result, 5), "dwd.result.id", "dwd.ddl_invalid_change.id");
        assertColumnSources(statement(result, 5), "dwd.result.dt", "dwd.ddl_invalid_change.dt");
    }

    @Test
    public void temporaryRenameUpdatesAliasesForLaterAlterAndDrop() {
        ScriptAnalysisResult result = catalog("v2_ddl_effect_temporary_rename_alter_drop_aliases.sql", 7, 1);
        ObjectId id = shape(result, 1).getObjectId();
        Effects.RenameObject rename = (Effects.RenameObject) effect(result, 2);
        assertEquals(id, rename.getObjectId());
        Effects.ObjectShape altered = shape(result, 3);
        assertEquals(id, altered.getObjectId());
        assertEquals(rename.getNewName(), altered.getName());
        assertEquals(Effects.ObjectKind.TEMPORARY_TABLE, altered.getKind());
        assertNames(altered, "id", "name");
        assertTrue(altered.getLookupNames().contains(new QualifiedName(Collections.singletonList("ddl_temp_new"))));
        assertTrue(altered.getLookupNames().contains(new QualifiedName(Arrays.asList("default", "ddl_temp_new"))));
        assertFalse(altered.getLookupNames().contains(new QualifiedName(Collections.singletonList("ddl_temp_old"))));
        success(result, 4);
        assertScanSchema(result, 4, "id", "name");
        Effects.DropObject drop = (Effects.DropObject) effect(result, 5);
        assertEquals(id, drop.getObjectId());
        assertEquals(Effects.ObjectKind.TEMPORARY_TABLE, drop.getObjectKind());
        failed(result, 6, "OBJECT_NOT_FOUND");
    }

    @Test
    public void replaceRejectsPartitionNameCollisionsWithoutChangingSchema() {
        ScriptAnalysisResult result = catalog("v2_ddl_effect_replace_partition_name_collision.sql", 3, 1);
        assertNames(shape(result, 0), "id", "name", "dt");
        assertEquals(Collections.singletonList("dt"), shape(result, 0).getPartitionColumns());
        failed(result, 1, "DUPLICATE_COLUMN");
        success(result, 2);
        assertScanSchema(result, 2, "id", "name", "dt");
        assertColumnSources(statement(result, 2), "dwd.result.name", "dwd.ddl_partition_collision.name");
        assertColumnSources(statement(result, 2), "dwd.result.dt", "dwd.ddl_partition_collision.dt");
    }

    private ScriptAnalysisResult catalog(String file, int total, int failed) {
        ScriptAnalysisResult result = analyzeWithCatalog(file, mode);
        assertEquals(file, total, result.getCounts().getTotal());
        assertEquals(file + ": " + result.getDiagnostics().getDiagnostics(), failed, result.getCounts().getFailed());
        if (failed == 0) {
            assertNoFailed(result);
        }
        return result;
    }

    private static BoundAnalysisUnit unit(ScriptAnalysisResult result, int index) {
        return result.getStatements().get(index).getBoundUnit().orElseThrow(() -> new AssertionError("No bound unit: " + index));
    }

    private static BoundEffect effect(ScriptAnalysisResult result, int index) {
        assertEquals(1, unit(result, index).getEffects().size());
        return unit(result, index).getEffects().get(0);
    }

    private static Effects.ObjectShape shape(ScriptAnalysisResult result, int index) {
        BoundEffect effect = unit(result, index).getEffects().get(0);
        return effect instanceof Effects.CreateObject ? ((Effects.CreateObject) effect).getShape()
                : ((Effects.AlterObject) effect).getReplacement();
    }

    private static List<OutputSlot> slots(Effects.ObjectShape shape) {
        return shape.getSchema().getSegments().stream().filter(segment -> segment instanceof KnownOutputSegment)
                .flatMap(segment -> ((KnownOutputSegment) segment).getSlots().stream()).collect(Collectors.toList());
    }

    private void assertScanSchema(ScriptAnalysisResult result, int index, String... columns) {
        Relations.Scan scan = (Relations.Scan) unit(result, index).getRelations().stream()
                .filter(relation -> relation instanceof Relations.Scan).findFirst().orElseThrow(() -> new AssertionError("No scan"));
        // Derived SELECT * is bound to resolve its enclosing projection even in TABLE_ONLY mode.
        assertEquals(SchemaState.KNOWN, scan.getOutputSchema().getState());
        assertEquals(Arrays.asList(columns), scan.getOutputSchema().getSegments().stream()
                .flatMap(segment -> ((KnownOutputSegment) segment).getSlots().stream())
                .map(OutputSlot::getDisplayName).collect(Collectors.toList()));
    }

    private static void assertNames(Effects.ObjectShape shape, String... names) {
        assertEquals(SchemaState.KNOWN, shape.getSchema().getState());
        assertEquals(Arrays.asList(names), slots(shape).stream().map(OutputSlot::getDisplayName).collect(Collectors.toList()));
    }

    private static DefinitionStatementSyntax definition(String file, int index) {
        return (DefinitionStatementSyntax) syntax(file).get(index).getStatement();
    }

    private void assertUnknownActions(String file, ScriptAnalysisResult result, int count) {
        ObjectId id = shape(result, 0).getObjectId();
        for (int index = 1; index <= count; index++) {
            assertIgnored(result, index);
            assertTrue(definition(file, index).getDefinition().getAlterActions().get(0) instanceof UnknownAlterActionSyntax);
            Effects.UnknownEffect effect = (Effects.UnknownEffect) effect(result, index);
            assertEquals(Effects.Certainty.UNKNOWN, effect.getCertainty());
            assertEquals(Collections.singletonList(id), effect.getAffectedObjects());
            assertFalse(effect.getCatalogMayChange());
            assertEquals("CATALOG_ACTION_NOT_BOUND", effect.getDiagnosticCode());
            assertTrue(unit(result, index).getWrites().isEmpty());
            diagnostic(result, index, "SESSION_EFFECT_UNCERTAIN");
        }
    }

    private static void assertIgnored(ScriptAnalysisResult result, int index) {
        StatementAnalysisResult statement = result.getStatements().get(index);
        assertEquals(StatementStatus.IGNORED, statement.getStatus());
        assertFalse(statement.isFormalLineageProduced());
    }

    private static void success(ScriptAnalysisResult result, int index) {
        assertEquals(result.getStatements().get(index).getDiagnostics().getDiagnostics().toString(),
                StatementStatus.SUCCESS, result.getStatements().get(index).getStatus());
        assertTrue(result.getStatements().get(index).isFormalLineageProduced());
    }

    private static void failed(ScriptAnalysisResult result, int index, String code) {
        StatementAnalysisResult statement = result.getStatements().get(index);
        assertEquals(StatementStatus.FAILED, statement.getStatus());
        assertEquals(code, statement.getFailureCode().get());
        assertFalse(statement.isFormalLineageProduced());
    }

    private static void uncertainRead(ScriptAnalysisResult result, int index, String code) {
        assertEquals(StatementStatus.PARTIAL_SUCCESS, result.getStatements().get(index).getStatus());
        diagnostic(result, index, code);
    }

    private static void diagnostic(ScriptAnalysisResult result, int index, String code) {
        assertTrue(code + ": " + result.getStatements().get(index).getDiagnostics().getDiagnostics(),
                result.getStatements().get(index).getDiagnostics().getDiagnostics().stream().anyMatch(item -> code.equals(item.getCode())));
    }
}
