package com.panfan.hivelineage.v2.ir.expression;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;

import java.util.List;
import java.util.Objects;

/**
 * Physical field owner is bound; existence and type can remain unverified.
 */
public final class CorrelatedQualifiedFieldHandle extends Expressions.Base {
    private final ScopeId outerScopeId;
    private final RelationInstanceId owner;
    private final FieldIdentity fieldIdentity;
    private final List<String> fieldPath;
    private final ExistenceState existence;

    public CorrelatedQualifiedFieldHandle(NodeId id, TypeRef type, SourceRef sourceRef, ScopeId outerScopeId, RelationInstanceId owner, FieldIdentity fieldIdentity, List<String> fieldPath, ExistenceState existence) {
        super(id, type, sourceRef);
        this.outerScopeId = Objects.requireNonNull(outerScopeId, "outerScopeId");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.fieldIdentity = Objects.requireNonNull(fieldIdentity, "fieldIdentity");
        this.fieldPath = ModelCollections.immutableList(fieldPath);
        this.existence = Objects.requireNonNull(existence, "existence");
    }

    public ScopeId getOuterScopeId() {
        return outerScopeId;
    }

    public RelationInstanceId getOwner() {
        return owner;
    }

    public FieldIdentity getFieldIdentity() {
        return fieldIdentity;
    }

    public List<String> getFieldPath() {
        return fieldPath;
    }

    public ExistenceState getExistence() {
        return existence;
    }

    public enum ExistenceState {VERIFIED, UNVERIFIED, UNKNOWN}

}
