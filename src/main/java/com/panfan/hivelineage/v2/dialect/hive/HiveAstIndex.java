package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.syntax.SyntaxOptions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hive.ql.parse.ASTNode;

/** Owns deterministic preorder identities and the depth/node resource boundary. */
final class HiveAstIndex {
    private final String sourceId;
    private final Map<ASTNode, Integer> nodeIndexes = new IdentityHashMap<ASTNode, Integer>();

    HiveAstIndex(ASTNode root, String sourceId, SyntaxOptions options) {
        this.sourceId = sourceId;
        if (root != null) {
            index(root, options);
        }
    }

    private void index(ASTNode root, SyntaxOptions options) {
        Deque<IndexedNode> pending = new ArrayDeque<IndexedNode>();
        pending.push(new IndexedNode(root, 1));
        while (!pending.isEmpty()) {
            IndexedNode next = pending.peek();
            if (!next.entered) {
                if (next.depth > options.getMaximumAstDepth()) {
                    throw new HiveNormalizationLimit("NORMALIZATION_DEPTH_LIMIT", "AST depth exceeds the normalization resource limit");
                }
                if (nodeIndexes.containsKey(next.node)) {
                    pending.pop();
                    continue;
                }
                if (nodeIndexes.size() >= options.getMaximumAstNodeCount()) {
                    throw new HiveNormalizationLimit("NORMALIZATION_NODE_LIMIT", "AST node count exceeds the normalization resource limit");
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

    NodeId id(ASTNode node) {
        return new NodeId(sourceId + ":node:" + nodeIndexes.get(node));
    }

    NodeId id(ASTNode node, String role) {
        return new NodeId(id(node).getValue() + ":" + role);
    }

    QueryBlockId block(ASTNode node) {
        return new QueryBlockId(id(node).getValue() + ":query");
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

}
