package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.UnknownOutputSegment;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.syntax.ColumnDefinitionSyntax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** CREATE proposals own declared schema construction and CREATE LIKE uncertainty. */
final class CreateObjectEffectBinder implements CatalogEffectBinder {
    @Override
    public EffectBindingResult bind(CatalogEffectRequest request) {
        List<String> partitions = new ArrayList<>();
        for (ColumnDefinitionSyntax column : request.body().getSchema().getPartitionColumns()) {
            partitions.add(SemanticNames.normalize(column.getName().getValue()));
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        OutputSchema schema = declaredSchema(request);
        if (request.body().getSchema().getLikeTable() != null) {
            schema = new OutputSchema(SchemaState.UNKNOWN, Collections.singletonList(
                    new UnknownOutputSegment(new NodeId(request.id().getValue() + "/like-schema"), "CREATE_LIKE_SCHEMA_UNKNOWN")));
            diagnostics.add(new Diagnostic(AnalysisStage.BINDING, "CREATE_LIKE_SCHEMA_UNKNOWN",
                    "CREATE LIKE source schema has not been inferred", request.sourceRef()));
        }
        Effects.ObjectShape shape = new Effects.ObjectShape(request.object(), request.name(), request.kind(), schema,
                VersionRef.notRequested(), request.body().getOptions().isTemporary()
                ? SemanticNames.temporaryLookupNames(request.body().getName(), request.context()) : Collections.singletonList(request.name()),
                partitions);
        return new EffectBindingResult(Collections.<BoundEffect>singletonList(new Effects.CreateObject(request.id(),
                request.certainty(), request.precondition(), shape)), new DiagnosticBag(diagnostics));
    }

    private OutputSchema declaredSchema(CatalogEffectRequest request) {
        List<ColumnDefinitionSyntax> columns = new ArrayList<>(request.body().getSchema().getColumns());
        columns.addAll(request.body().getSchema().getPartitionColumns());
        List<OutputSlot> slots = new ArrayList<>();
        for (ColumnDefinitionSyntax column : columns) {
            String name = SemanticNames.normalize(column.getName().getValue());
            FieldIdentity field = new FieldIdentity(request.object().getValue() + "/field/" + name, name);
            slots.add(new OutputSlot(new SlotId(request.id().getValue() + "/column-" + slots.size()), name,
                    SemanticTypes.declared(column.getType()), field));
        }
        return OutputSchema.known(slots);
    }
}
