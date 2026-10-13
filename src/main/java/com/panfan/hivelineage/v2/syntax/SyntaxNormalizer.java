package com.panfan.hivelineage.v2.syntax;

/** Converts a dialect artifact to structural Syntax IR without semantic source inference. */
public interface SyntaxNormalizer<P> {
    SyntaxResult normalize(P parseArtifact, SyntaxOptions options);
}
