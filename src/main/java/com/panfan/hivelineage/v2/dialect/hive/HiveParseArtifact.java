package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.lexical.LexedUnit;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;
import java.util.Collections;

import com.panfan.hivelineage.v2.syntax.SyntaxStatement;
import org.apache.hadoop.hive.ql.parse.ASTNode;

/**
 * Opaque dialect result. Native AST is visible only to this adapter package.
 */
public final class HiveParseArtifact {
    private final LexedUnit unit;
    private final ASTNode tree;
    private final SyntaxStatement command;
    private final List<Diagnostic> diagnostics;
    private final HiveTokenIndex tokens;

    HiveParseArtifact(LexedUnit unit, ASTNode tree, SyntaxStatement command) {
        this(unit, tree, command, Collections.<Diagnostic>emptyList());
    }

    HiveParseArtifact(LexedUnit unit, ASTNode tree, SyntaxStatement command, List<Diagnostic> diagnostics) {
        this(unit, tree, command, diagnostics, new HiveTokenIndex(unit));
    }

    HiveParseArtifact(LexedUnit unit, ASTNode tree, SyntaxStatement command, List<Diagnostic> diagnostics, HiveTokenIndex tokens) {
        this.diagnostics = ModelCollections.immutableList(diagnostics);
        this.unit = unit;
        this.tree = tree;
        this.command = command;
        this.tokens = tokens;
    }

    List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    LexedUnit unit() {
        return unit;
    }

    ASTNode tree() {
        return tree;
    }

    SyntaxStatement command() {
        return command;
    }

    HiveTokenIndex tokens() {
        return tokens;
    }
}
