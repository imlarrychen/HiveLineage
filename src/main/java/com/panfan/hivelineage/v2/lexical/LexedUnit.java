package com.panfan.hivelineage.v2.lexical;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.frontend.ExpandedUnit;
import java.util.List;
import java.util.Objects;

/** Reusable immutable token buffer; parser cursors are not part of this model. */
public final class LexedUnit {
    private final ExpandedUnit expandedUnit;
    private final List<LexToken> tokens;
    private final List<Trivia> trivia;

    public LexedUnit(ExpandedUnit expandedUnit, List<LexToken> tokens, List<Trivia> trivia) {
        this.expandedUnit = Objects.requireNonNull(expandedUnit, "expandedUnit");
        this.tokens = ModelCollections.immutableList(tokens);
        this.trivia = ModelCollections.immutableList(trivia);
    }

    public ExpandedUnit getExpandedUnit() {
        return expandedUnit;
    }

    public List<LexToken> getTokens() {
        return tokens;
    }

    public List<Trivia> getTrivia() {
        return trivia;
    }
}
