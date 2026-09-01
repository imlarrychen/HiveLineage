package com.panfan.hivelineage.core;

import com.panfan.hivelineage.result.DataObjectRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class HiveSetOperationStarExpander {
    private final HiveTargetColumnResolver targetColumnResolver;

    HiveSetOperationStarExpander(HiveTargetColumnResolver targetColumnResolver) {
        this.targetColumnResolver = targetColumnResolver;
    }

    SetOperationExpansionResult expand(ColumnResolutionContext context, String sql) {
        List<Boundary> boundaries = topLevelBoundaries(sql);
        if (boundaries.isEmpty()) {
            return new SetOperationExpansionResult(sql, null, Collections.<Integer>emptyList(), Collections.<Integer>emptySet());
        }
        StringBuilder output = new StringBuilder();
        List<Integer> widths = new ArrayList<>();
        Set<Integer> unresolved = new LinkedHashSet<>();
        int branchStart = 0;
        for (Boundary boundary : boundaries) {
            appendBranch(context, sql.substring(branchStart, boundary.start), output, widths, unresolved);
            output.append(sql.substring(boundary.start, boundary.nextBranch));
            branchStart = boundary.nextBranch;
        }
        appendBranch(context, sql.substring(branchStart), output, widths, unresolved);
        return new SetOperationExpansionResult(output.toString(), boundaries.get(0).operator, widths, unresolved);
    }

    private void appendBranch(
        ColumnResolutionContext context,
        String branchSql,
        StringBuilder output,
        List<Integer> widths,
        Set<Integer> unresolved
    ) {
        int selectIndex = HiveSqlTextUtils.topLevelKeywordIndex(branchSql, "select", 0);
        int fromIndex = HiveSqlTextUtils.topLevelKeywordIndex(branchSql, "from", selectIndex < 0 ? 0 : selectIndex + 6);
        if (selectIndex < 0 || fromIndex < 0) {
            output.append(branchSql);
            widths.add(0);
            return;
        }
        Map<String, String> aliases = targetColumnResolver.extractTopLevelSourceAliases(branchSql);
        List<DataObjectRef> inputs = new ArrayList<>();
        for (String source : new LinkedHashSet<String>(aliases.values())) {
            inputs.add(new DataObjectRef(source));
        }
        QualifiedOwnerResolver resolver = new QualifiedOwnerResolver(
            inputs,
            targetColumnResolver.extractExplicitSourceAliases(branchSql)
        );
        List<String> expandedItems = new ArrayList<>();
        int position = 0;
        for (String item : HiveSqlTextUtils.splitSelectItems(branchSql.substring(selectIndex + 6, fromIndex))) {
            List<String> itemExpansion = targetColumnResolver.expandStarSelectItems(
                context, null, Collections.singletonList(item), inputs, resolver
            );
            QualifiedColumnReference reference = QualifiedColumnReference.parse(HiveSqlTextUtils.stripAlias(item));
            if (reference != null && reference.isStar() && itemExpansion.size() == 1
                && itemExpansion.get(0).equals(item)) {
                unresolved.add(position);
            }
            expandedItems.addAll(itemExpansion);
            position += itemExpansion.size();
        }
        widths.add(expandedItems.size());
        output.append(branchSql.substring(0, selectIndex + 6))
            .append(' ').append(HiveSqlTextUtils.joinWithComma(expandedItems)).append(' ')
            .append(branchSql.substring(fromIndex));
    }

    private List<Boundary> topLevelBoundaries(String sql) {
        List<Boundary> boundaries = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int position = 0;
        while (position < sql.length()) {
            char ch = sql.charAt(position);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                position++;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                position++;
                continue;
            }
            if (ch == '(') {
                depth++;
                position++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                position++;
                continue;
            }
            if (depth == 0) {
                String operator = operatorAt(sql, position);
                if (operator != null) {
                    int next = HiveSqlTextUtils.skipWhitespace(sql, position + operator.length());
                    if ("union".equals(operator) && HiveSqlTextUtils.matchesKeywordAt(sql, next, "all")) {
                        next = HiveSqlTextUtils.skipWhitespace(sql, next + 3);
                    }
                    boundaries.add(new Boundary(position, next, operator));
                    position = next;
                    continue;
                }
            }
            position++;
        }
        return boundaries;
    }

    private String operatorAt(String sql, int position) {
        String[] operators = {"union", "intersect", "except"};
        for (String operator : operators) {
            if (HiveSqlTextUtils.matchesKeywordAt(sql, position, operator)) {
                return operator;
            }
        }
        return null;
    }

    private static final class Boundary {
        private final int start;
        private final int nextBranch;
        private final String operator;
        private Boundary(int start, int nextBranch, String operator) {
            this.start = start;
            this.nextBranch = nextBranch;
            this.operator = operator;
        }
    }
}
