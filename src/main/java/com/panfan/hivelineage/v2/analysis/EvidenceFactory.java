package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.EvidenceId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.List;
import java.util.Optional;

/**
 * Analysis-local proof creation and explicit retention for extension-rule intermediates.
 */
public interface EvidenceFactory {
    Dependency dependency(LineageEndpoint source, LineageEndpoint target, DependencyRole role, DependencyPrecision precision,
                          NodeId operation, String rule, List<EvidenceId> parents, Optional<SourceRef> sourceRef);

    void retain(Dependency dependency);

    List<Dependency> retarget(List<Dependency> dependencies, LineageEndpoint target, NodeId operation,
                              String rule, DependencyRole forcedRole, Optional<SourceRef> sourceRef);
}
