package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import java.util.List;

/** Set operation positional rules use the same declaration index as all other checks. */
final class SetOperatorVerification {
    private final VerificationContext context;
    private final VerificationDiagnostics diagnostics;

    SetOperatorVerification(VerificationContext context, VerificationDiagnostics diagnostics) {
        this.context = context;
        this.diagnostics = diagnostics;
    }

    void verify(Relations.SetOp set, OperatorOutputState state) {
        widths(set);
        if (set.getBranches().size() < 2) {
            diagnostics.error("IR_SET_BRANCH_COUNT", "Set operation needs at least two branches");
        }
        List<OutputSlot> outputs = context.declaredSlots(set);
        for (int position = 0; position < set.getMappings().size(); position++) {
            Relations.SetOutputMapping mapping = set.getMappings().get(position);
            if (!state.setOutput(mapping, set.getBranches().size())) {
                diagnostics.error("IR_SET_MAPPING_INVALID", "Set mapping output/branch count is invalid");
                continue;
            }
            if (set.getOutputSchema().getState() == SchemaState.KNOWN && !atPosition(outputs, mapping.getOutput(), position)) {
                diagnostics.error("IR_SET_POSITION_INVALID", "Set mappings do not follow output order");
            }
            branches(set, mapping, position);
        }
    }

    private void widths(Relations.SetOp set) {
        Integer knownWidth = null;
        for (NodeId id : set.getBranches()) {
            BoundRelation branch = context.relations().get(id);
            if (branch != null && branch.getOutputSchema().getState() == SchemaState.KNOWN) {
                int width = context.outputs(id).size();
                if (knownWidth != null && width != knownWidth) {
                    diagnostics.error("IR_SET_WIDTH_MISMATCH", "Set branches have different known widths");
                }
                knownWidth = width;
            }
        }
    }

    private void branches(Relations.SetOp set, Relations.SetOutputMapping mapping, int position) {
        for (int branchIndex = 0; branchIndex < set.getBranches().size(); branchIndex++) {
            BoundRelation branch = context.relations().get(set.getBranches().get(branchIndex));
            if (branch != null && mapping.getBranchSlots().get(branchIndex).getSlot().isPresent()) {
                SlotId slot = mapping.getBranchSlots().get(branchIndex).getSlot().get();
                if (!context.outputs(branch.getId()).contains(slot) || branch.getOutputSchema().getState() == SchemaState.KNOWN
                        && !atPosition(context.slots(branch.getId()), slot, position)) {
                    diagnostics.error("IR_SET_POSITION_INVALID", "Branch slot does not match positional mapping");
                }
            }
        }
    }

    private boolean atPosition(List<OutputSlot> slots, SlotId slot, int position) {
        return position < slots.size() && slots.get(position).getId().equals(slot);
    }
}
