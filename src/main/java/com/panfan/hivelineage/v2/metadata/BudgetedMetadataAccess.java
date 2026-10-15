package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.QualifiedName;
import java.util.Objects;

/** One request's budget over the shared captured workflow read boundary. */
public final class BudgetedMetadataAccess implements MetadataAccess {
    private final WorkflowMetadataAccess access;
    private final ExternalReadBudget budget;

    public BudgetedMetadataAccess(CapturedMetadataAccess access, ExternalReadBudget budget) {
        this((WorkflowMetadataAccess) access, budget);
    }

    public BudgetedMetadataAccess(WorkflowMetadataAccess access, ExternalReadBudget budget) {
        this.access = Objects.requireNonNull(access, "access");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    @Override
    public LookupResult<ObjectDescriptor> lookupObject(QualifiedName name, MetadataReadContext context) {
        return access.lookupObject(name, context, budget);
    }

    @Override
    public LookupResult<RelationSchema> lookupSchema(QualifiedName name, MetadataReadContext context) {
        return access.lookupSchema(name, context, budget);
    }

    @Override
    public LookupResult<RelationMetadata> lookupRelation(QualifiedName name, MetadataReadContext context) {
        return access.lookupRelation(name, context, budget);
    }
}
