package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.metadata.ColumnMetadata;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.LookupStatus;
import com.panfan.hivelineage.v2.metadata.RelationSchema;

import java.util.ArrayList;
import java.util.List;

/** Performs one metadata attempt and synchronizes a physical scan with its source state. */
final class SourceSchemaLoader {
    static final class Result {
        enum Status { UNCHANGED, LOADED, UNAVAILABLE, UNCERTAIN }

        final Status status;
        final OutputSchema schema;
        final DiagnosticBag diagnostics;

        Result(Status status, OutputSchema schema, DiagnosticBag diagnostics) {
            this.status = status;
            this.schema = schema;
            this.diagnostics = diagnostics;
        }
    }

    private final BindingWork work;

    SourceSchemaLoader(BindingWork work) {
        this.work = work;
    }

    Result load(SourceBinding binding) {
        int firstDiagnostic = work.diagnostics.size();
        Result.Status status = loadSchema(binding);
        return new Result(status, binding.schema(), new DiagnosticBag(work.diagnostics.subList(firstDiagnostic, work.diagnostics.size())));
    }

    private Result.Status loadSchema(SourceBinding binding) {
        if (binding.isUncertain()) {
            binding.markUncertain();
            return Result.Status.UNCERTAIN;
        }
        if (!binding.beginSchemaLoad()) {
            return Result.Status.UNCHANGED;
        }
        CatalogObjectResolver resolver = new CatalogObjectResolver(work.context);
        CatalogObjectResolver.Resolution resolved = resolver.canonical(binding.name, binding.objectId);
        resolved.requireAvailable();
        if (resolved.uncertain) {
            binding.markUncertain();
            work.warn("SESSION_OBJECT_UNCERTAIN", "Prior effects left object state uncertain", binding.source);
            return Result.Status.UNCERTAIN;
        }
        LookupResult<RelationSchema> result = resolver.schema(resolved);
        work.diagnostics.addAll(result.getDiagnostics().getDiagnostics());
        if (result.getStatus() != LookupStatus.FOUND) {
            binding.completeSchemaLoad(BindingWork.unknown(new NodeId(binding.relationId.getValue() + "/unknown-width"), "METADATA_NOT_FOUND"));
            work.warn(result.getStatus() == LookupStatus.ACCESS_ERROR ? "METADATA_ACCESS_ERROR" : "METADATA_NOT_FOUND",
                    "Schema is unavailable for " + binding.name.getDisplayName(), binding.source);
        } else {
            List<OutputSlot> slots = new ArrayList<>();
            for (ColumnMetadata column : result.getValue().get().getColumns()) {
                FieldIdentity field = work.field(binding.objectId, column.getName());
                slots.add(new OutputSlot(new SlotId(binding.relationId.getValue() + "/column/" + column.getPosition()),
                        SemanticNames.normalize(column.getName()), column.getType(), field));
            }
            binding.completeSchemaLoad(result.getValue().get().getState() == SchemaState.KNOWN ? OutputSchema.known(slots)
                    : BindingWork.partialSchema(slots, new NodeId(binding.relationId.getValue() + "/unknown-width"), "SCHEMA_UNKNOWN"));
            List<FieldIdentity> fields = new ArrayList<>();
            for (OutputSlot slot : slots) {
                fields.add(slot.getFieldIdentity().get());
            }
            com.panfan.hivelineage.v2.ir.statement.BoundObjectReference object = work.objects.get(binding.objectId);
            work.declare(binding.objectId, binding.name, object.getKind(), result.getValue().get().getState(), fields,
                    object.getDefinitionId().orElse(null));
        }
        com.panfan.hivelineage.v2.ir.relation.BoundRelation previous = work.relations.get(binding.relationId);
        if (previous instanceof Relations.Scan) {
            Relations.Scan scan = (Relations.Scan) previous;
            work.put(new Relations.Scan(scan.getId(), binding.schema(), scan.getQueryBlockId(), scan.getSourceOrder(),
                    scan.getSourceRef().orElse(null), scan.getObjectId(), scan.getVersion(), scan.getInstanceId()));
        }
        return result.getStatus() == LookupStatus.FOUND ? Result.Status.LOADED : Result.Status.UNAVAILABLE;
    }
}
