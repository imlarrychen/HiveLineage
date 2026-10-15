package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.AnalysisQuality;
import com.panfan.hivelineage.v2.api.StatementIdentity;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;

public final class ColumnLineageRecord {
    private final StatementIdentity statement;
    private final ColumnAddress target;
    private final List<ColumnAddress> sources;
    private final ColumnDerivationKind derivation;
    private final RenderedExpression expression;
    private final List<String> functionNames;
    private final AnalysisQuality quality;

    public ColumnLineageRecord(StatementIdentity statement, ColumnAddress target, List<ColumnAddress> sources,
                               ColumnDerivationKind derivation, RenderedExpression expression,
                               List<String> functionNames, AnalysisQuality quality) {
        this.statement = ModelChecks.value(statement, "statement");
        this.target = ModelChecks.value(target, "target");
        this.sources = ModelCollections.immutableList(sources);
        this.derivation = ModelChecks.value(derivation, "derivation");
        this.expression = ModelChecks.value(expression, "expression");
        this.functionNames = ModelCollections.immutableList(functionNames);
        this.quality = ModelChecks.value(quality, "quality");
    }

    public StatementIdentity getStatement() {
        return statement;
    }

    public ColumnAddress getTarget() {
        return target;
    }

    public List<ColumnAddress> getSources() {
        return sources;
    }

    /** Value-expression classification, retaining UNKNOWN when the proof is incomplete. */
    public ColumnDerivationKind getDerivation() {
        return derivation;
    }

    public RenderedExpression getExpression() {
        return expression;
    }

    /** Function calls collected from the bound expression, independently of source-column count. */
    public List<String> getFunctionNames() {
        return functionNames;
    }

    public AnalysisQuality getQuality() {
        return quality;
    }
}
