package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;

public final class MergeUpdateSyntax extends MergeActionSyntax {
    private final List<AssignmentSyntax> assignments;

    public MergeUpdateSyntax(List<AssignmentSyntax> assignments) {
        super();
        this.assignments = ModelCollections.immutableList(assignments);
    }

    public List<AssignmentSyntax> getAssignments() {
        return assignments;
    }
}
