package com.panfan.hivelineage.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class SetOperationExpansionResult {
    private final String sql;
    private final String operator;
    private final List<Integer> branchWidths;
    private final Set<Integer> unresolvedPositions;

    SetOperationExpansionResult(String sql, String operator, List<Integer> branchWidths, Set<Integer> unresolvedPositions) {
        this.sql = sql;
        this.operator = operator;
        this.branchWidths = Collections.unmodifiableList(new ArrayList<>(branchWidths));
        this.unresolvedPositions = Collections.unmodifiableSet(new LinkedHashSet<>(unresolvedPositions));
    }

    String getSql() { return sql; }
    boolean isSetOperation() { return operator != null; }
    String getOperator() { return operator; }
    boolean hasConsistentBranchWidths() {
        return branchWidths.isEmpty() || Collections.frequency(branchWidths, branchWidths.get(0)) == branchWidths.size();
    }
    boolean isUnresolvedPosition(int position) { return unresolvedPositions.contains(position); }
}
