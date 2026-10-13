package com.panfan.hivelineage.v2.ir.statement;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import java.util.Objects;
import java.util.Set;
import java.util.Collections;

/**
 * A verifier-issued claim, not a verifier implementation. Callers must trust the issuer.
 * This immutable carrier does not run validation or assert Hive execution legality.
 * The exact immutable unit is retained so a claim cannot be reused on a different graph.
 */
public final class VerificationCertificate {
    public enum VerificationCheck { STRUCTURE, BINDING }
    private final BoundAnalysisUnit unit;
    private final String verifierId;
    private final Set<BindingRequirement> requirements;
    private final Set<VerificationCheck> checks;
    private final Set<NodeId> regions;

    public VerificationCertificate(BoundAnalysisUnit unit, String verifierId, Set<BindingRequirement> requirements, Set<VerificationCheck> checks) {
        this(unit, verifierId, requirements, checks, Collections.<NodeId>emptySet());
    }

    public VerificationCertificate(BoundAnalysisUnit unit, String verifierId, Set<BindingRequirement> requirements, Set<VerificationCheck> checks, Set<NodeId> regions) {
        this.unit = Objects.requireNonNull(unit, "unit");
        this.verifierId = Objects.requireNonNull(verifierId, "verifierId");
        if (verifierId.trim().isEmpty()) {
            throw new IllegalArgumentException("verifierId must not be blank");
        }
        this.requirements = ModelCollections.immutableSet(requirements);
        this.checks = ModelCollections.immutableSet(checks);
        this.regions = ModelCollections.immutableSet(regions);
    }

    public BoundAnalysisUnit getUnit() {
        return unit;
    }

    public NodeId getStatementId() {
        return unit.getStatementId();
    }

    public String getVerifierId() {
        return verifierId;
    }

    public Set<BindingRequirement> getRequirements() {
        return requirements;
    }

    public Set<VerificationCheck> getChecks() {
        return checks;
    }

    /** Empty means the whole analysis unit; otherwise only these declared regions are claimed. */
    public Set<NodeId> getRegions() {
        return regions;
    }

    public boolean coversWholeUnit() {
        return regions.isEmpty() || regions.contains(unit.getStatementId());
    }
}
