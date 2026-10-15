package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Checks that each declared output is mapped or produced exactly once. */
final class OperatorOutputState {
    private final BoundRelation relation;
    private final VerificationContext context;
    private final VerificationDiagnostics diagnostics;
    private final Set<SlotId> output;
    private final Set<SlotId> produced = new LinkedHashSet<>();

    OperatorOutputState(BoundRelation relation, VerificationContext context, VerificationDiagnostics diagnostics) {
        this.relation = relation;
        this.context = context;
        this.diagnostics = diagnostics;
        output = context.declaredOutputs(relation);
    }

    void mappings() {
        Set<SlotId> inputs = context.inputSlots(relation);
        for (SlotMapping mapping : VerificationSupport.mappings(relation)) {
            if (!inputs.contains(mapping.getInput()) || !output.contains(mapping.getOutput())) {
                diagnostics.error("IR_SLOT_MAPPING_INVALID", "Transparent mapping references an absent input/output");
            }
            if (!produced.add(mapping.getOutput())) {
                diagnostics.error("IR_DUPLICATE_MAPPING", "Output has multiple transparent mappings");
            }
        }
    }

    void assignments(List<OutputAssignment> assignments) {
        for (OutputAssignment assignment : assignments) {
            if (!output.contains(assignment.getOutput()) || !produced.add(assignment.getOutput())) {
                diagnostics.error("IR_ASSIGNMENT_INVALID", "Output assignment absent or duplicated");
            }
        }
    }

    void generated(List<SlotId> generated) {
        for (SlotId slot : generated) {
            if (!output.contains(slot) || !produced.add(slot)) {
                diagnostics.error("IR_GENERATE_SLOT_INVALID", "Generated slot absent or duplicated");
            }
        }
    }

    boolean setOutput(Relations.SetOutputMapping mapping, int branches) {
        return output.contains(mapping.getOutput()) && produced.add(mapping.getOutput())
                && mapping.getBranchSlots().size() == branches;
    }

    int width() {
        return output.size();
    }

    void allProduced() {
        produced.addAll(output);
    }

    void complete() {
        if (!(relation instanceof Relations.Scan) && !(relation instanceof Relations.UnknownRelation)
                && relation.getOutputSchema().getState() == SchemaState.KNOWN && !produced.equals(output)) {
            diagnostics.error("IR_OUTPUT_MAPPING_INCOMPLETE", "Known output is not fully assigned or mapped");
        }
    }
}
