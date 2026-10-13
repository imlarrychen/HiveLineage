package com.panfan.hivelineage.v2.lexical;

import com.panfan.hivelineage.v2.frontend.ExpandedUnit;

/** Produces tokens only; no parser, metadata access, name binding or lineage inference. */
public interface SqlLexer {
    LexResult tokenize(ExpandedUnit unit, LexicalOptions options);
}
