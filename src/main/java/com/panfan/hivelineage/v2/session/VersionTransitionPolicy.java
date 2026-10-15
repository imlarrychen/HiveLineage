package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.ir.identity.DatasetVersionId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Owns version transitions and suppression together so skipped catalog branches cannot publish versions. */
final class VersionTransitionPolicy {
    private final Map<ObjectId, DataVersionState> versions;
    private final Set<ObjectId> suppressed = new LinkedHashSet<>();

    VersionTransitionPolicy(Map<ObjectId, DataVersionState> before) {
        versions = new LinkedHashMap<>(before);
    }

    void suppress(ObjectId id) {
        suppressed.add(id);
    }

    boolean isSuppressed(ObjectId id) {
        return suppressed.contains(id);
    }

    void drop(ObjectId id) {
        versions.remove(id);
        suppress(id);
    }

    void rename(ObjectId id, boolean uncertain) {
        DataVersionState version = versions.get(id);
        if (uncertain && version != null && version.getVersion().getState() != VersionRef.State.NOT_REQUESTED) {
            versions.put(id, new DataVersionState(VersionRef.unknown("RENAMED_OBJECT_STATE_UNCERTAIN"),
                    version.getPredecessors(), DataVersionState.Basis.UNKNOWN));
        }
    }

    void apply(SessionEffectPlan plan, Set<ObjectId> uncertain) {
        if (!plan.getBoundUnit().isPresent()) {
            return;
        }
        BoundAnalysisUnit unit = plan.getBoundUnit().get();
        SourceVersionCollector sources = null;
        for (BoundWrite write : unit.getWrites()) {
            if (!canApply(write, unit)) {
                continue;
            }
            if (sources == null) {
                sources = new SourceVersionCollector(unit);
            }
            versions.put(write.getTargetObject(), transition(write, sources.collect(write), uncertain.contains(write.getTargetObject())));
        }
    }

    DataVersionState transition(BoundWrite write, SourceVersionCollector.SourceVersions source, boolean targetUncertain) {
        Set<DatasetVersionId> predecessors = new LinkedHashSet<>(source.ids());
        boolean unknown = source.isUnknown() || targetUncertain || write.getAfterVersion().getState() == VersionRef.State.UNKNOWN
                || write.getPartitionScope().getKind() == PartitionScope.Kind.UNKNOWN;
        boolean replacesWholeDataset = write.getWriteMode() == BoundWrite.WriteMode.CREATE_AS
                || write.getWriteMode() == BoundWrite.WriteMode.OVERWRITE && write.getPartitionScope().getKind() == PartitionScope.Kind.UNPARTITIONED;
        unknown |= !replacesWholeDataset && write.getBeforeVersion().getState() == VersionRef.State.UNKNOWN;
        if (unknown || !replacesWholeDataset) {
            write.getBeforeVersion().getId().ifPresent(predecessors::add);
        }
        return new DataVersionState(unknown ? VersionRef.unknown("DATA_VERSION_UNCERTAIN") : write.getAfterVersion(),
                new ArrayList<>(predecessors), unknown ? DataVersionState.Basis.UNKNOWN : DataVersionState.Basis.ASSUMED_PREDECESSOR_SUCCESS);
    }

    Map<ObjectId, DataVersionState> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(versions));
    }

    private boolean canApply(BoundWrite write, BoundAnalysisUnit unit) {
        BoundObjectReference target = unit.getObjects().get(write.getTargetObject());
        return (target == null || target.getKind() != BoundObjectReference.ObjectKind.VIEW)
                && !isSuppressed(write.getTargetObject()) && write.getAfterVersion().getState() != VersionRef.State.NOT_REQUESTED;
    }
}
