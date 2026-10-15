package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.SourceSpan;
import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.lexical.LexToken;
import com.panfan.hivelineage.v2.lexical.LexedUnit;
import com.panfan.hivelineage.v2.lexical.TokenKind;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.QuoteKind;
import com.panfan.hivelineage.v2.syntax.SyntaxName;
import com.panfan.hivelineage.v2.syntax.SyntaxOptions;
import com.panfan.hivelineage.v2.syntax.InsertModeSyntax;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;

final class HiveAstSupport {
    private static final Map<Integer, String> NATIVE_SYMBOLS = nativeSymbols();
    private final LexedUnit unit;
    private final Map<ASTNode, Integer> nodeIndexes = new IdentityHashMap<ASTNode, Integer>();
    private final HiveTokenIndex tokens;
    private final List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();

    HiveAstSupport(HiveParseArtifact artifact, SyntaxOptions options) {
        this.unit = artifact.unit();
        this.tokens = artifact.tokens();
        if (artifact.tree() != null) {
            index(artifact.tree(), options);
        }
    }

    private void index(ASTNode root, SyntaxOptions options) {
        Deque<IndexedNode> pending = new ArrayDeque<IndexedNode>();
        pending.push(new IndexedNode(root, 1));
        while (!pending.isEmpty()) {
            IndexedNode next = pending.peek();
            if (!next.entered) {
                if (next.depth > options.getMaximumAstDepth()) {
                    throw new NormalizationLimit("NORMALIZATION_DEPTH_LIMIT", "AST depth exceeds the normalization resource limit");
                }
                if (nodeIndexes.containsKey(next.node)) {
                    pending.pop();
                    continue;
                }
                if (nodeIndexes.size() >= options.getMaximumAstNodeCount()) {
                    throw new NormalizationLimit("NORMALIZATION_NODE_LIMIT", "AST node count exceeds the normalization resource limit");
                }
                nodeIndexes.put(next.node, nodeIndexes.size());
                next.entered = true;
            }
            if (next.child < next.node.getChildCount()) {
                pending.push(new IndexedNode(at(next.node, next.child++), next.depth + 1));
            } else {
                pending.pop();
            }
        }
    }

    List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    void unsupported(ASTNode node, String reason) {
        diagnostics.add(new Diagnostic(AnalysisStage.NORMALIZATION, "UNSUPPORTED_NATIVE_EXPRESSION", reason, ref(node)));
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
        return tokens.between(end, start, symbol);
    }

    InsertModeSyntax insertModeBefore(int end) {
        return tokens.modeBefore(end);
    }

    boolean recoveredExpression(ASTNode node) {
        int index = node.getToken() == null ? -1 : node.getToken().getTokenIndex();
        return index >= 0 && index < unit.getTokens().size() && unit.getTokens().get(index).getKind() == TokenKind.UNKNOWN;
    }

    List<IdentifierPart> cteColumns(ASTNode alias) {
        List<IdentifierPart> columns = new ArrayList<IdentifierPart>();
        boolean list = false;
        List<LexToken> visible = tokens.visible();
        for (int index = tokens.firstVisibleAt(ref(alias).getSpan().getEnd()); index < visible.size(); index++) {
            LexToken token = visible.get(index);
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
        String symbol = NATIVE_SYMBOLS.get(node.getType());
        return symbol == null ? node.getText() : symbol;
    }

    private static Map<Integer, String> nativeSymbols() {
        Map<Integer, String> symbols = new HashMap<Integer, String>();
        for (int index = 0; index < HiveParser.tokenNames.length; index++) {
            symbols.put(index, HiveParser.tokenNames[index]);
        }
        // Hive's public vocabulary also includes virtual AST types beyond tokenNames.
        for (Field field : HiveParser.class.getDeclaredFields()) {
            if (field.getType() == int.class && Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())) {
                try {
                    symbols.put(field.getInt(null), field.getName());
                } catch (IllegalAccessException error) {
                    throw new IllegalStateException("Cannot read Hive token vocabulary", error);
                }
            }
        }
        return Collections.unmodifiableMap(symbols);
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

    static final class NormalizationLimit extends RuntimeException {
        private final String code;

        NormalizationLimit(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private static final class IndexedNode {
        private final ASTNode node;
        private final int depth;
        private int child;
        private boolean entered;

        private IndexedNode(ASTNode node, int depth) {
            this.node = node;
            this.depth = depth;
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
