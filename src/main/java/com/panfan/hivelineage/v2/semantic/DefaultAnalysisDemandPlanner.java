package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.api.AnalysisCapability;
import com.panfan.hivelineage.v2.api.AnalysisContract;
import com.panfan.hivelineage.v2.api.StatementPublicationPolicy;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;
import com.panfan.hivelineage.v2.syntax.QueryStatementSyntax;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class DefaultAnalysisDemandPlanner implements AnalysisDemandPlanner {
    @Override
    public BindingDemand plan(SyntaxStatement syntax, AnalysisContract contract) {
        Set<BindingRequirement> requirements = EnumSet.of(BindingRequirement.OBJECTS, BindingRequirement.SESSION_EFFECTS);
        Set<AnalysisCapability> capabilities = contract.getCapabilities().getCapabilities();
        if (capabilities.contains(AnalysisCapability.COLUMN_LINEAGE)
                && (!(syntax instanceof QueryStatementSyntax) || contract.getPublicationPolicy() == StatementPublicationPolicy.DETAILED)) {
            if (!(syntax instanceof com.panfan.hivelineage.v2.syntax.UpdateStatementSyntax)
                    && !(syntax instanceof com.panfan.hivelineage.v2.syntax.DeleteStatementSyntax)
                    && !(syntax instanceof com.panfan.hivelineage.v2.syntax.MergeStatementSyntax)) {
                requirements.add(BindingRequirement.OUTPUT_SCHEMA);
            }
            if (!(syntax instanceof QueryStatementSyntax)) {
                requirements.add(BindingRequirement.SINK_MAPPING);
            }
            requirements.add(BindingRequirement.CONDITION_FIELDS);
        }
        if (capabilities.contains(AnalysisCapability.JOIN_CONDITION)) {
            requirements.add(BindingRequirement.CONDITION_FIELDS);
        }
        if (contract.isExpandViewLineage()) {
            requirements.add(BindingRequirement.DEFINITION_EXPANSION);
        }
        if (capabilities.contains(AnalysisCapability.DATASET_VERSION_SIMULATION)) {
            requirements.add(BindingRequirement.DATASET_VERSIONS);
        }
        return new BindingDemand(requirements, Collections.<NodeId>singleton(syntax.getNodeId()));
    }
}
