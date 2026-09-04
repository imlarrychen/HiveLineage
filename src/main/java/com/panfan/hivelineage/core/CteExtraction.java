package com.panfan.hivelineage.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class CteExtraction {
    private final List<CteBlock> cteBlocks;
    private final String rootSql;

    CteExtraction(List<CteBlock> cteBlocks, String rootSql) {
        this.cteBlocks = Collections.unmodifiableList(new ArrayList<>(cteBlocks));
        this.rootSql = rootSql;
    }

    List<CteBlock> getCteBlocks() {
        return cteBlocks;
    }

    String getRootSql() {
        return rootSql;
    }
}
