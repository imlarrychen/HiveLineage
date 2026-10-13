package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.SourceSpan;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.lexical.LexToken;
import com.panfan.hivelineage.v2.lexical.LexedUnit;
import com.panfan.hivelineage.v2.lexical.TokenKind;
import com.panfan.hivelineage.v2.lexical.TokenChannel;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.QuoteKind;
import com.panfan.hivelineage.v2.syntax.SyntaxName;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;

final class HiveAstSupport {
    private final LexedUnit unit;
    private final Map<ASTNode, Integer> nodeIndexes = new IdentityHashMap<ASTNode, Integer>();

    HiveAstSupport(HiveParseArtifact artifact) {
        this.unit = artifact.unit();
        if (artifact.tree() != null) {
            index(artifact.tree());
        }
    }

    private void index(ASTNode node) {
        nodeIndexes.put(node, nodeIndexes.size());
        for (ASTNode child : children(node)) {
            index(child);
        }
    }

    NodeId id(ASTNode node) {
        return new NodeId(unit.getExpandedUnit().getExpandedSourceId() + ":node:" + nodeIndexes.get(node));
    }

    NodeId id(ASTNode node, String role) {
        return new NodeId(id(node).getValue() + ":" + role);
    }

    QueryBlockId block(ASTNode node) {
        return new QueryBlockId(id(node).getValue() + ":query");
    }

    SourceRef ref(ASTNode node) {
        String sourceId = unit.getExpandedUnit().getExpandedSourceId();
        if (node.getTokenStartIndex() < 0 || node.getTokenStopIndex() < node.getTokenStartIndex()) {
            return SourceRef.synthetic(sourceId);
        }
        int startIndex = node.getTokenStartIndex();
        int stopIndex = Math.max(startIndex, node.getTokenStopIndex());
        List<LexToken> tokens = unit.getTokens();
        if (startIndex >= tokens.size()) {
            return SourceRef.synthetic(sourceId);
        }
        int start = tokens.get(startIndex).getSourceRef().getSpan().getStart();
        int end = tokens.get(Math.min(stopIndex, tokens.size() - 1)).getSourceRef().getSpan().getEnd();
        return new SourceRef(sourceId, new SourceSpan(sourceId, start, Math.max(start, end)));
    }

    boolean betweenHas(ASTNode left, ASTNode right, String symbol) {
        int end = ref(left).getSpan().getEnd();
        int start = ref(right).getSpan().getStart();
        for (LexToken token : unit.getTokens()) {
            int position = token.getSourceRef().getSpan().getStart();
            if (position >= end && position < start && symbol.equals(token.getDialectSymbol().getSymbolName())) {
                return true;
            }
        }
        return false;
    }

    boolean recoveredExpression(ASTNode node) {
        int index = node.getToken() == null ? -1 : node.getToken().getTokenIndex();
        return index >= 0 && index < unit.getTokens().size() && unit.getTokens().get(index).getKind() == TokenKind.UNKNOWN;
    }

    List<IdentifierPart> cteColumns(ASTNode alias) {
        List<IdentifierPart> columns = new ArrayList<IdentifierPart>();
        boolean list = false;
        for (LexToken token : unit.getTokens()) {
            if (token.getSourceRef().getSpan().getStart() < ref(alias).getSpan().getEnd()
                    || token.getChannel() != TokenChannel.DEFAULT) {
                continue;
            }
            if (!list) {
                if (!"(".equals(token.getLexeme())) {
                    return columns;
                }
                list = true;
            } else if (")".equals(token.getLexeme())) {
                return columns;
            } else if (!",".equals(token.getLexeme())) {
                columns.add(new IdentifierPart(token.getLexeme(), unquote(token.getLexeme()),
                        token.getLexeme().startsWith("`") ? QuoteKind.BACKTICK : QuoteKind.NONE, token.getSourceRef()));
            }
        }
        return columns;
    }

    boolean synthetic(ASTNode node) {
        return node.getToken() == null || node.getToken().getTokenIndex() < 0;
    }

    String source(ASTNode node) {
        SourceSpan range = ref(node).getSpan();
        return unit.getExpandedUnit().getExpandedText().substring(range.getStart(), range.getEnd());
    }

    String kind(ASTNode node) {
        if (node == null || node.getType() < 0) {
            return "";
        }
        String text = node.getText();
        if (text != null && text.toUpperCase(java.util.Locale.ROOT).startsWith("TOK_")) {
            return text.toUpperCase(java.util.Locale.ROOT);
        }
        return node.getType() < HiveParser.tokenNames.length ? HiveParser.tokenNames[node.getType()] : text;
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
        if (node == null || kind.equals(kind(node))) {
            return node;
        }
        for (ASTNode child : children(node)) {
            ASTNode found = first(child, kind);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    List<ASTNode> children(ASTNode node) {
        List<ASTNode> result = new ArrayList<ASTNode>();
        if (node != null) {
            for (int index = 0; index < node.getChildCount(); index++) {
                result.add((ASTNode) node.getChild(index));
            }
        }
        return result;
    }

    ASTNode at(ASTNode node, int index) {
        return (ASTNode) node.getChild(index);
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
        String kind = kind(node);
        if ("TOK_TABNAME".equals(kind) || "TOK_TABLE_OR_COL".equals(kind) || "DOT".equals(kind)) {
            for (ASTNode child : children(node)) {
                addNameParts(child, parts);
            }
        } else {
            parts.add(part(node));
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
