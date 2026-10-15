package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;

/** Verifies requested versions without deciding or repairing version transitions. */
final class VersionBindingVerification {
    private final VerificationContext context;
    private final BindingDemand demand;
    private final VerificationDiagnostics diagnostics;

    VersionBindingVerification(VerificationContext context, BindingDemand demand, VerificationDiagnostics diagnostics) {
        this.context = context;
        this.demand = demand;
        this.diagnostics = diagnostics;
    }

    void relation(BoundRelation relation) {
        boolean requested = demand.getRegions().isEmpty() || demand.getRegions().contains(context.unit().getStatementId())
                || demand.getRegions().contains(relation.getId());
        if (requested && demand.getRequirements().contains(BindingRequirement.DATASET_VERSIONS) && missingReadVersion(relation)) {
            diagnostics.need("Requested dataset read version was not bound", null);
        }
    }

    private boolean missingReadVersion(BoundRelation relation) {
        if (relation instanceof Relations.Scan) {
            return ((Relations.Scan) relation).getVersion().getState() == VersionRef.State.NOT_REQUESTED;
        }
        return relation instanceof Relations.DefinitionInstance && ((Relations.DefinitionInstance) relation).getPhysicalObject().isPresent()
                && ((Relations.DefinitionInstance) relation).getVersion().getState() == VersionRef.State.NOT_REQUESTED;
    }

    void write(BoundWrite write) {
        BoundObjectReference target = context.unit().getObjects().get(write.getTargetObject());
        if (demand.getRequirements().contains(BindingRequirement.DATASET_VERSIONS)
                && (target == null || target.getKind() != BoundObjectReference.ObjectKind.VIEW)
                && (write.getBeforeVersion().getState() == VersionRef.State.NOT_REQUESTED
                || write.getAfterVersion().getState() == VersionRef.State.NOT_REQUESTED)) {
            diagnostics.need("Requested dataset write versions were not bound", null);
        }
    }
}
