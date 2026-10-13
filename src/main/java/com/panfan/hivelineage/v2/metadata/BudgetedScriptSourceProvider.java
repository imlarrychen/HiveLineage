package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.api.SourceDocument;
import java.util.Objects;

/** Counts source-service calls against the same request budget as metadata reads. */
public final class BudgetedScriptSourceProvider implements ScriptSourceProvider {
    private final ScriptSourceProvider delegate;
    private final ExternalReadBudget budget;

    public BudgetedScriptSourceProvider(ScriptSourceProvider delegate, ExternalReadBudget budget) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    @Override
    public LookupResult<SourceDocument> read(DefinitionRef reference) {
        if (delegate.requiresExternalRead(reference)) {
            budget.beforeRead();
        }
        return delegate.read(reference);
    }

    @Override
    public boolean requiresExternalRead(DefinitionRef reference) {
        return false;
    }
}
