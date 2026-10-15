package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.metadata.DefinitionRef;

import java.util.List;
import java.util.Set;

/**
 * Binder emits this structural request instead of parsing external definition text.
 */
public final class DefinitionRequest {
    private final DefinitionRef reference;
    private final Set<BindingRequirement> requirements;
    private final List<DefinitionId> expansionPath;
    private final int remainingDepth;

    public DefinitionRequest(DefinitionRef reference, Set<BindingRequirement> requirements,
                             List<DefinitionId> expansionPath, int remainingDepth) {
        this.reference = ModelChecks.value(reference, "reference");
        this.requirements = ModelCollections.immutableSet(requirements);
        this.expansionPath = ModelCollections.immutableList(expansionPath);
        this.remainingDepth = ModelChecks.nonNegative(remainingDepth, "remainingDepth");
    }

    public DefinitionRef getReference() {
        return reference;
    }

    public Set<BindingRequirement> getRequirements() {
        return requirements;
    }

    public List<DefinitionId> getExpansionPath() {
        return expansionPath;
    }

    public int getRemainingDepth() {
        return remainingDepth;
    }
}

