package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.api.SourceDocument;
import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import java.util.Collections;

public final class DefinitionTextSourceProvider implements ScriptSourceProvider {
    @Override
    public LookupResult<SourceDocument> read(DefinitionRef reference) {
        if (!reference.getDefinitionText().isPresent()) {
            return LookupResult.unknown(new DiagnosticBag(Collections.singletonList(new Diagnostic(
                AnalysisStage.METADATA, "VIEW_DEFINITION_NOT_FOUND", "Definition text is unavailable from the inline source provider",
                reference.getSource()))));
        }
        return LookupResult.found(new SourceDocument(reference.getSource().getSourceId(), reference.getDefinitionText().get()));
    }

    @Override
    public boolean requiresExternalRead(DefinitionRef reference) {
        return false;
    }
}
