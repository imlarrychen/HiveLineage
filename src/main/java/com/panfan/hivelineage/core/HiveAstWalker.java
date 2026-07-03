package com.panfan.hivelineage.core;

import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.hive.ql.parse.ASTNode;

final class HiveAstWalker {
    ASTNode first(ASTNode node, int tokenType) {
        if (node == null) {
            return null;
        }
        if (node.getType() == tokenType) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            ASTNode found = first((ASTNode) node.getChild(i), tokenType);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    ASTNode directChild(ASTNode node, int tokenType) {
        if (node == null) {
            return null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            ASTNode child = (ASTNode) node.getChild(i);
            if (child.getType() == tokenType) {
                return child;
            }
        }
        return null;
    }

    List<ASTNode> collect(ASTNode node, int tokenType) {
        List<ASTNode> result = new ArrayList<>();
        collectInto(node, tokenType, result);
        return result;
    }

    private void collectInto(ASTNode node, int tokenType, List<ASTNode> result) {
        if (node == null) {
            return;
        }
        if (node.getType() == tokenType) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectInto((ASTNode) node.getChild(i), tokenType, result);
        }
    }
}
