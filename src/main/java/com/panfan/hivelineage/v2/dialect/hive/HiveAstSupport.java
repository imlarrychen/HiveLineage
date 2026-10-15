package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.InsertModeSyntax;
import com.panfan.hivelineage.v2.syntax.QuoteKind;
import com.panfan.hivelineage.v2.syntax.SyntaxName;
import com.panfan.hivelineage.v2.syntax.SyntaxOptions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.apache.hadoop.hive.ql.parse.ASTNode;

/** Syntax-name operations over independently owned indexing, source and diagnostic services. */
final class HiveAstSupport {
    private final HiveAstIndex index;
    private final HiveSourceMapper sources;
    private final HiveNativeVocabulary vocabulary = new HiveNativeVocabulary();
    private final HiveNormalizationDiagnostics diagnostics = new HiveNormalizationDiagnostics();

    HiveAstSupport(HiveParseArtifact artifact, SyntaxOptions options) {
        this.index = new HiveAstIndex(artifact.tree(), artifact.unit().getExpandedUnit().getExpandedSourceId(), options);
        this.sources = new HiveSourceMapper(artifact.unit(), artifact.tokens());
    }

    List<Diagnostic> diagnostics() {
        return diagnostics.snapshot();
    }

    void unsupported(ASTNode node, String reason) {
        diagnostics.unsupported(ref(node), reason);
    }

    NodeId id(ASTNode node) {
        return index.id(node);
    }

    NodeId id(ASTNode node, String role) {
        return index.id(node, role);
    }

    QueryBlockId block(ASTNode node) {
        return index.block(node);
    }

    SourceRef ref(ASTNode node) {
        return sources.ref(node);
    }

    boolean betweenHas(ASTNode left, ASTNode right, String symbol) {
        return sources.betweenHas(left, right, symbol);
    }

    InsertModeSyntax insertModeBefore(int end) {
        return sources.insertModeBefore(end);
    }

    boolean recoveredExpression(ASTNode node) {
        return sources.recoveredExpression(node);
    }

    List<IdentifierPart> cteColumns(ASTNode alias) {
        return sources.cteColumns(alias);
    }

    boolean synthetic(ASTNode node) {
        return node.getToken() == null || node.getToken().getTokenIndex() < 0;
    }

    String source(ASTNode node) {
        return sources.source(node);
    }

    String kind(ASTNode node) {
        return vocabulary.kind(node);
    }

    List<ASTNode> children(ASTNode node) {
        return index.children(node);
    }

    ASTNode at(ASTNode node, int position) {
        return index.at(node, position);
    }

    ASTNode child(ASTNode node, String kind) {
        for (ASTNode child : children(node)) {
            if (kind.equals(kind(child))) {
                return child;
            }
        }
        return null;
    }

    ASTNode first(ASTNode node, String kind) {
        if (node == null) {
            return null;
        }
        Deque<ASTNode> pending = new ArrayDeque<ASTNode>();
        pending.push(node);
        while (!pending.isEmpty()) {
            ASTNode next = pending.pop();
            if (kind.equals(kind(next))) {
                return next;
            }
            for (int index = next.getChildCount() - 1; index >= 0; index--) {
                pending.push(at(next, index));
            }
        }
        return null;
    }

    IdentifierPart part(ASTNode node) {
        String value = node.getText();
        QuoteKind quote = value.startsWith("`") ? QuoteKind.BACKTICK
                : value.startsWith("'") ? QuoteKind.SINGLE_QUOTE : QuoteKind.NONE;
        return new IdentifierPart(value, unquote(value), quote, ref(node));
    }

    SyntaxName name(ASTNode node) {
        List<IdentifierPart> parts = new ArrayList<IdentifierPart>();
        addNameParts(node, parts);
        return new SyntaxName(parts);
    }

    private void addNameParts(ASTNode node, List<IdentifierPart> parts) {
        Deque<ASTNode> pending = new ArrayDeque<ASTNode>();
        pending.push(node);
        while (!pending.isEmpty()) {
            ASTNode next = pending.pop();
            String kind = kind(next);
            if ("TOK_TABNAME".equals(kind) || "TOK_TABLE_OR_COL".equals(kind) || "DOT".equals(kind)) {
                for (int index = next.getChildCount() - 1; index >= 0; index--) {
                    pending.push(at(next, index));
                }
            } else {
                parts.add(part(next));
            }
        }
    }

    static String unquote(String text) {
        if (text.length() < 2) {
            return text;
        }
        char first = text.charAt(0);
        if ((first == '`' || first == '\'' || first == '"') && text.charAt(text.length() - 1) == first) {
            String decoded = text.substring(1, text.length() - 1);
            return decoded.replace("" + first + first, "" + first).replace("\\" + first, "" + first).replace("\\\\", "\\");
        }
        return text;
    }
}
