package com.panfan.hivelineage.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;

final class HiveAstExpressionExtractor {
    String inferColumnName(ASTNode expression) {
        if (expression.getType() == HiveParser.TOK_TABLE_OR_COL && expression.getChildCount() == 1) {
            return stripIdentifier(expression.getChild(0).getText());
        }
        if (".".equals(expression.getText()) && expression.getChildCount() == 2) {
            return stripIdentifier(expression.getChild(1).getText());
        }
        return unparse(expression);
    }

    String firstColumnReference(ASTNode node) {
        if (node == null) {
            return null;
        }
        if (node.getType() == HiveParser.TOK_TABLE_OR_COL && node.getChildCount() == 1) {
            return stripIdentifier(node.getChild(0).getText()).toLowerCase(Locale.ROOT);
        }
        if (".".equals(node.getText()) && node.getChildCount() == 2) {
            return unparse(node).toLowerCase(Locale.ROOT);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String found = firstColumnReference((ASTNode) node.getChild(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    String unparse(ASTNode expression) {
        if (expression == null) {
            return "";
        }
        String text = expression.getText();
        if (expression.getType() == HiveParser.TOK_ALLCOLREF) {
            return "*";
        }
        if (expression.getType() == HiveParser.TOK_TABLE_OR_COL && expression.getChildCount() == 1) {
            return stripIdentifier(expression.getChild(0).getText());
        }
        if (".".equals(text) && expression.getChildCount() == 2) {
            return unparse((ASTNode) expression.getChild(0)) + "." + stripIdentifier(expression.getChild(1).getText());
        }
        if ("[".equals(text) && expression.getChildCount() == 2) {
            return unparse((ASTNode) expression.getChild(0)) + "[" + unparse((ASTNode) expression.getChild(1)) + "]";
        }
        if (expression.getType() == HiveParser.TOK_FUNCTION && expression.getChildCount() > 0) {
            String functionName = stripIdentifier(expression.getChild(0).getText());
            List<String> args = new ArrayList<>();
            for (int i = 1; i < expression.getChildCount(); i++) {
                args.add(unparse((ASTNode) expression.getChild(i)));
            }
            if (functionName.toUpperCase(Locale.ROOT).startsWith("TOK_") && args.size() == 1) {
                return "cast(" + args.get(0) + " as " + functionName.substring(4).toLowerCase(Locale.ROOT) + ")";
            }
            if ("when".equalsIgnoreCase(functionName) && args.size() >= 2) {
                return renderCaseWhen(args);
            }
            return functionName + "(" + joinWithComma(args) + ")";
        }
        if ("TOK_FUNCTIONSTAR".equals(text) && expression.getChildCount() > 0) {
            return stripIdentifier(expression.getChild(0).getText()) + "(*)";
        }
        if (expression.getChildCount() == 2 && isBinaryOperator(text)) {
            return unparse((ASTNode) expression.getChild(0)) + " " + text + " " + unparse((ASTNode) expression.getChild(1));
        }
        if (expression.getChildCount() == 1 && isUnaryOperator(text)) {
            return text + unparse((ASTNode) expression.getChild(0));
        }
        if (expression.getChildCount() == 0) {
            return stripIdentifier(text);
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < expression.getChildCount(); i++) {
            parts.add(unparse((ASTNode) expression.getChild(i)));
        }
        return text + "(" + joinWithComma(parts) + ")";
    }

    String stripIdentifier(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '`' && trimmed.charAt(trimmed.length() - 1) == '`') {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private boolean isBinaryOperator(String text) {
        return "+".equals(text) || "-".equals(text) || "*".equals(text) || "/".equals(text) || "%".equals(text)
            || "=".equals(text) || "==".equals(text) || "<>".equals(text) || "!=".equals(text)
            || "<".equals(text) || "<=".equals(text) || ">".equals(text) || ">=".equals(text);
    }

    private boolean isUnaryOperator(String text) {
        return "+".equals(text) || "-".equals(text) || "~".equals(text);
    }

    private String joinWithComma(List<String> parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(part);
        }
        return builder.toString();
    }

    private String renderCaseWhen(List<String> args) {
        StringBuilder builder = new StringBuilder("case");
        int pairEnd = args.size() % 2 == 0 ? args.size() : args.size() - 1;
        for (int i = 0; i + 1 < pairEnd; i += 2) {
            builder.append(" when ")
                .append(args.get(i))
                .append(" then ")
                .append(args.get(i + 1));
        }
        if (args.size() % 2 == 1) {
            builder.append(" else ").append(args.get(args.size() - 1));
        }
        return builder.append(" end").toString();
    }
}
