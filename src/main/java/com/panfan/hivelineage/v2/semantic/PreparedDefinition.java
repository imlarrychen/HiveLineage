package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.session.DefinitionTemplate;
import com.panfan.hivelineage.v2.syntax.SyntaxTemplate;
import java.util.Optional;

public final class PreparedDefinition implements DefinitionTemplate {
    private final DefinitionId definitionId;
    private final Optional<SyntaxTemplate> syntax;
    private final Optional<BoundRelation> boundRelation;
    private final SourceRef source;
    private final Optional<BoundAnalysisUnit> boundUnit;

    public PreparedDefinition(DefinitionId definitionId, Optional<SyntaxTemplate> syntax, Optional<BoundRelation> boundRelation, SourceRef source) {
        this(definitionId, syntax, boundRelation, source, Optional.<BoundAnalysisUnit>empty());
    }

    public PreparedDefinition(DefinitionId definitionId, Optional<SyntaxTemplate> syntax,
                              Optional<BoundRelation> boundRelation, SourceRef source,
                              Optional<BoundAnalysisUnit> boundUnit) {
        this.definitionId = ModelChecks.value(definitionId, "definitionId");
        this.syntax = ModelChecks.value(syntax, "syntax");
        this.boundRelation = ModelChecks.value(boundRelation, "boundRelation");
        this.source = ModelChecks.value(source, "source");
        this.boundUnit = ModelChecks.value(boundUnit, "boundUnit");
        if (!syntax.isPresent() && !boundRelation.isPresent()) {
            throw new IllegalArgumentException("a prepared definition requires structured syntax or bound structure");
        }
    }

    public DefinitionId getDefinitionId() {
        return definitionId;
    }

    public Optional<SyntaxTemplate> getSyntax() {
        return syntax;
    }

    public Optional<BoundRelation> getBoundRelation() {
        return boundRelation;
    }

    public SourceRef getSource() {
        return source;
    }

    public Optional<BoundAnalysisUnit> getBoundUnit() {
        return boundUnit;
    }
}
