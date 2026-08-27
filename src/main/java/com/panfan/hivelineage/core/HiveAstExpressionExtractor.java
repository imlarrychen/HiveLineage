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
            if (expression.getChildCount() == 1) {
                ASTNode owner = (ASTNode) expression.getChild(0);
                if (owner.getType() == HiveParser.TOK_TABNAME && owner.getChildCount() > 0) {
                    StringBuilder qualifiedOwner = new StringBuilder();
                    for (int i = 0; i < owner.getChildCount(); i++) {
                        if (qualifiedOwner.length() > 0) {
                            qualifiedOwner.append('.');
                        }
                        qualifiedOwner.append(stripIdentifier(((ASTNode) owner.getChild(i)).getText()));
                    }
                    return qualifiedOwner.append(".*").toString();
                }
            }
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
            ASTNode windowSpec = null;
            for (int i = 1; i < expression.getChildCount(); i++) {
                ASTNode child = (ASTNode) expression.getChild(i);
                if (isWindowSpec(child)) {
                    windowSpec = child;
                } else {
                    args.add(unparse(child));
                }
            }
            if (functionName.toUpperCase(Locale.ROOT).startsWith("TOK_") && args.size() == 1) {
                return "cast(" + args.get(0) + " as " + functionName.substring(4).toLowerCase(Locale.ROOT) + ")";
            }
            if ("when".equalsIgnoreCase(functionName) && args.size() >= 2) {
                return renderCaseWhen(args);
            }
            String functionCall = functionName + "(" + joinWithComma(args) + ")";
            if (windowSpec != null) {
                return functionCall + " " + renderWindowSpec(windowSpec);
            }
            return functionCall;
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
        return isArithmeticOperator(text) || isComparisonOperator(text) || isLogicalOperator(text);
    }

    private boolean isArithmeticOperator(String text) {
        return "+".equals(text) || "-".equals(text) || "*".equals(text)
            || "/".equals(text) || "%".equals(text);
    }

    private boolean isComparisonOperator(String text) {
        return isEqualityOperator(text) || isOrderingOperator(text);
    }

    private boolean isEqualityOperator(String text) {
        return "=".equals(text) || "==".equals(text) || "<>".equals(text) || "!=".equals(text);
    }

    private boolean isOrderingOperator(String text) {
        return "<".equals(text) || "<=".equals(text) || ">".equals(text) || ">=".equals(text);
    }

    private boolean isLogicalOperator(String text) {
        return "and".equalsIgnoreCase(text) || "or".equalsIgnoreCase(text);
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

    private boolean isWindowSpec(ASTNode node) {
        return node != null && "TOK_WINDOWSPEC".equalsIgnoreCase(node.getText());
    }

    private String renderWindowSpec(ASTNode windowSpec) {
        List<String> clauses = new ArrayList<>();
        for (int i = 0; i < windowSpec.getChildCount(); i++) {
            String clause = renderWindowClause((ASTNode) windowSpec.getChild(i));
            if (!clause.isEmpty()) {
                clauses.add(clause);
            }
        }
        return "over(" + joinWithSpace(clauses) + ")";
    }

    private String renderWindowClause(ASTNode node) {
        if (node == null) {
            return "";
        }
        String text = node.getText();
        if ("TOK_PARTITIONINGSPEC".equalsIgnoreCase(text)) {
            List<String> clauses = new ArrayList<>();
            for (int i = 0; i < node.getChildCount(); i++) {
                String clause = renderWindowClause((ASTNode) node.getChild(i));
                if (!clause.isEmpty()) {
                    clauses.add(clause);
                }
            }
            return joinWithSpace(clauses);
        }
        if ("TOK_DISTRIBUTEBY".equalsIgnoreCase(text)) {
            return "partition by " + renderChildList(node);
        }
        if ("TOK_ORDERBY".equalsIgnoreCase(text)) {
            List<String> orderItems = new ArrayList<>();
            for (int i = 0; i < node.getChildCount(); i++) {
                orderItems.add(renderOrderItem((ASTNode) node.getChild(i)));
            }
            return "order by " + joinWithComma(orderItems);
        }
        return unparse(node);
    }

    private String renderOrderItem(ASTNode node) {
        if (node == null) {
            return "";
        }
        String text = node.getText();
        if ("TOK_TABSORTCOLNAMEDESC".equalsIgnoreCase(text) && node.getChildCount() > 0) {
            return renderSortExpression((ASTNode) node.getChild(0)) + " desc";
        }
        if ("TOK_TABSORTCOLNAMEASC".equalsIgnoreCase(text) && node.getChildCount() > 0) {
            return renderSortExpression((ASTNode) node.getChild(0)) + " asc";
        }
        return renderSortExpression(node);
    }

    private String renderSortExpression(ASTNode node) {
        if (node == null) {
            return "";
        }
        String text = node.getText();
        if (("TOK_NULLS_FIRST".equalsIgnoreCase(text) || "TOK_NULLS_LAST".equalsIgnoreCase(text))
            && node.getChildCount() > 0) {
            return renderSortExpression((ASTNode) node.getChild(0));
        }
        return unparse(node);
    }

    private String renderChildList(ASTNode node) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            parts.add(unparse((ASTNode) node.getChild(i)));
        }
        return joinWithComma(parts);
    }

    private String joinWithSpace(List<String> parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(part);
        }
        return builder.toString();
    }
}
