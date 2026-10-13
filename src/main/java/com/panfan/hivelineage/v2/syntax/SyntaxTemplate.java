package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.frontend.ExpandedUnit;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.Objects;
import java.util.List;
import java.util.Collections;
import java.util.Optional;

/** Prepared external definition; no task-local binding state or metadata callbacks. */
public final class SyntaxTemplate {
    private final NodeId templateId;
    private final SyntaxStatement statement;
    private final SourceRef sourceRef;
    private final List<Diagnostic> diagnostics;
    private final Optional<ExpandedUnit> frontendEvidence;

    public SyntaxTemplate(NodeId templateId, SyntaxStatement statement, SourceRef sourceRef) {
        this(templateId, statement, sourceRef, Collections.<Diagnostic>emptyList(), Optional.<ExpandedUnit>empty());
    }

    public SyntaxTemplate(NodeId templateId, SyntaxStatement statement, SourceRef sourceRef,
                          List<Diagnostic> diagnostics, Optional<ExpandedUnit> frontendEvidence) {
        this.templateId = Objects.requireNonNull(templateId, "templateId");
        this.statement = Objects.requireNonNull(statement, "statement");
        this.sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
        this.diagnostics = ModelCollections.immutableList(diagnostics);
        this.frontendEvidence = Objects.requireNonNull(frontendEvidence, "frontendEvidence");
    }

    public NodeId getTemplateId() {
        return templateId;
    }

    public SyntaxStatement getStatement() {
        return statement;
    }

    public SourceRef getSourceRef() {
        return sourceRef;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    public Optional<ExpandedUnit> getFrontendEvidence() {
        return frontendEvidence;
    }
}
