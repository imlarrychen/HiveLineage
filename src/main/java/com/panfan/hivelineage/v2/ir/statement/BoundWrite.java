package com.panfan.hivelineage.v2.ir.statement;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.quality.UnknownMapping;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bound contribution, separate from the complete post-write dataset and execution fact. */
public final class BoundWrite {
    public enum WriteMode { APPEND, OVERWRITE, UPDATE, DELETE, MERGE_ACTION, CREATE_AS }
    private final NodeId id;
    private final NodeId sourceRelation;
    private final ObjectId targetObject;
    private final VersionRef beforeVersion;
    private final VersionRef afterVersion;
    private final WriteMode writeMode;
    private final List<WriteAssignment> assignments;
    private final PartitionScope partitionScope;
    private final BoundExpression condition;
    private final MappingCoverage mappingCoverage;
    private final List<UnknownMapping> unknownMappings;

    public BoundWrite(NodeId id, ObjectId targetObject, VersionRef beforeVersion, VersionRef afterVersion, WriteMode writeMode, List<WriteAssignment> assignments, PartitionScope partitionScope, BoundExpression condition, MappingCoverage mappingCoverage, List<UnknownMapping> unknownMappings) {
        this(id, targetObject, beforeVersion, afterVersion, writeMode, assignments, partitionScope, condition, mappingCoverage, unknownMappings, null);
    }

    public BoundWrite(NodeId id, ObjectId targetObject, VersionRef beforeVersion, VersionRef afterVersion, WriteMode writeMode, List<WriteAssignment> assignments, PartitionScope partitionScope, BoundExpression condition, MappingCoverage mappingCoverage, List<UnknownMapping> unknownMappings, NodeId sourceRelation) {
        this.id = Objects.requireNonNull(id, "id");
        this.sourceRelation = sourceRelation;
        this.targetObject = Objects.requireNonNull(targetObject, "targetObject");
        this.beforeVersion = Objects.requireNonNull(beforeVersion, "beforeVersion");
        this.afterVersion = Objects.requireNonNull(afterVersion, "afterVersion");
        this.writeMode = Objects.requireNonNull(writeMode, "writeMode");
        this.assignments = ModelCollections.immutableList(assignments);
        this.partitionScope = Objects.requireNonNull(partitionScope, "partitionScope");
        this.condition = condition;
        this.mappingCoverage = Objects.requireNonNull(mappingCoverage, "mappingCoverage");
        this.unknownMappings = ModelCollections.immutableList(unknownMappings);
    }

    public Optional<NodeId> getSourceRelation() {
        return Optional.ofNullable(sourceRelation);
    }

    public NodeId getId() {
        return id;
    }

    public ObjectId getTargetObject() {
        return targetObject;
    }

    public VersionRef getBeforeVersion() {
        return beforeVersion;
    }

    public VersionRef getAfterVersion() {
        return afterVersion;
    }

    public WriteMode getWriteMode() {
        return writeMode;
    }

    public List<WriteAssignment> getAssignments() {
        return assignments;
    }

    public PartitionScope getPartitionScope() {
        return partitionScope;
    }

    public Optional<BoundExpression> getCondition() {
        return Optional.ofNullable(condition);
    }

    public MappingCoverage getMappingCoverage() {
        return mappingCoverage;
    }

    public List<UnknownMapping> getUnknownMappings() {
        return unknownMappings;
    }
}
