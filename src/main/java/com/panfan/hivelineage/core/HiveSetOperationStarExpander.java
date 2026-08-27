package com.panfan.hivelineage.core;

import com.panfan.hivelineage.result.DataObjectRef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class HiveSetOperationStarExpander {
    private final HiveTargetColumnResolver targetColumnResolver;

    HiveSetOperationStarExpander(HiveTargetColumnResolver targetColumnResolver) {
        this.targetColumnResolver = targetColumnResolver;
    }

    String expand(ColumnResolutionContext context, String sql) {
        List<Integer> unionIndexes = topLevelUnionIndexes(sql);
        if (unionIndexes.isEmpty()) {
            return sql;
        }
        StringBuilder expanded = new StringBuilder();
        int branchStart = 0;
        for (Integer unionIndex : unionIndexes) {
            expanded.append(expandBranch(context, sql.substring(branchStart, unionIndex)));
            int nextBranch = HiveSqlTextUtils.skipWhitespace(sql, unionIndex + 5);
            if (HiveSqlTextUtils.matchesKeywordAt(sql, nextBranch, "all")) {
                nextBranch = HiveSqlTextUtils.skipWhitespace(sql, nextBranch + 3);
            }
            expanded.append(sql.substring(unionIndex, nextBranch));
            branchStart = nextBranch;
        }
        expanded.append(expandBranch(context, sql.substring(branchStart)));
        return expanded.toString();
    }

    private String expandBranch(ColumnResolutionContext context, String branchSql) {
        int selectIndex = HiveSqlTextUtils.topLevelKeywordIndex(branchSql, "select", 0);
        int fromIndex = HiveSqlTextUtils.topLevelKeywordIndex(branchSql, "from", selectIndex < 0 ? 0 : selectIndex + 6);
        if (selectIndex < 0 || fromIndex < 0) {
            return branchSql;
        }
        Map<String, String> aliases = targetColumnResolver.extractTopLevelSourceAliases(branchSql);
        Set<String> physicalNames = new LinkedHashSet<>(aliases.values());
        List<DataObjectRef> branchInputs = new ArrayList<>();
        for (String physicalName : physicalNames) {
            branchInputs.add(new DataObjectRef(physicalName));
        }
        Map<String, String> explicitAliases = targetColumnResolver.extractExplicitSourceAliases(branchSql);
        QualifiedOwnerResolver ownerResolver = new QualifiedOwnerResolver(branchInputs, explicitAliases);
        List<String> selectItems = HiveSqlTextUtils.splitSelectItems(
            branchSql.substring(selectIndex + 6, fromIndex)
        );
        List<String> expandedItems = targetColumnResolver.expandStarSelectItems(
            context,
            null,
            selectItems,
            branchInputs,
            ownerResolver
        );
        if (expandedItems.equals(selectItems)) {
            return branchSql;
        }
        return branchSql.substring(0, selectIndex + 6)
            + " " + HiveSqlTextUtils.joinWithComma(expandedItems) + " "
            + branchSql.substring(fromIndex);
    }

    private List<Integer> topLevelUnionIndexes(String sql) {
        List<Integer> indexes = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        for (int position = 0; position < sql.length(); position++) {
            char ch = sql.charAt(position);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && HiveSqlTextUtils.matchesKeywordAt(sql, position, "union")) {
                indexes.add(position);
                position += 4;
            }
        }
        return indexes;
    }
}
