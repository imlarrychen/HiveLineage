package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.api.SourceDocument;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.SourceSpan;

import java.util.Map;
import java.util.Optional;

/**
 * Restores only the supplied span; never tokenizes text or interprets a field name.
 */
public final class DefaultSourceRenderer implements SourceRenderer {
    private final Map<String, SourceDocument> sources;

    public DefaultSourceRenderer(Map<String, SourceDocument> sources) {
        this.sources = ModelCollections.immutableMap(sources);
    }

    @Override
    public Optional<String> render(SourceRef reference) {
        SourceDocument source = sources.get(reference.getSourceId());
        if (source == null) {
            return Optional.empty();
        }
        SourceSpan span = reference.getSpan();
        if (span.getEnd() > source.getText().length()) {
            return Optional.empty();
        }
        return Optional.of(source.getText().substring(span.getStart(), span.getEnd()));
    }
}
