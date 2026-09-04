package com.panfan.hivelineage.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SetOperationParts {
    private final String functionName;
    private final List<String> branches;

    SetOperationParts(String functionName, List<String> branches) {
        this.functionName = functionName;
        this.branches = Collections.unmodifiableList(new ArrayList<>(branches));
    }

    String getFunctionName() {
        return functionName;
    }

    List<String> getBranches() {
        return branches;
    }
}
