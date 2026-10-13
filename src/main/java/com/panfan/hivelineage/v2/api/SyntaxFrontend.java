package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.dialect.DialectProfile;
import com.panfan.hivelineage.v2.frontend.ExpandedUnit;
import com.panfan.hivelineage.v2.syntax.SyntaxResult;

/** Orchestration boundary; implementations keep lexical, parsing and normalization stages separate. */
public interface SyntaxFrontend {
    SyntaxResult parse(ExpandedUnit unit, DialectProfile dialect);
}
