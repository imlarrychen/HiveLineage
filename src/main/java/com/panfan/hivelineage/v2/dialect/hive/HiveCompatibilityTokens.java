package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.lexical.LexToken;
import com.panfan.hivelineage.v2.lexical.LexedUnit;
import com.panfan.hivelineage.v2.lexical.TokenChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Hive's INTO column grammar also accepts the established OVERWRITE-column extension. */
final class HiveCompatibilityTokens {
    static Set<Integer> overwriteColumnExtensions(LexedUnit unit) {
        List<LexToken> visible = new ArrayList<LexToken>();
        for (LexToken token : unit.getTokens()) {
            if (token.getChannel() == TokenChannel.DEFAULT) {
                visible.add(token);
            }
        }
        Set<Integer> replacements = new HashSet<Integer>();
        for (int index = 0; index + 2 < visible.size(); index++) {
            if (!"insert".equalsIgnoreCase(visible.get(index).getLexeme())) {
                continue;
            }
            LexToken mode = visible.get(index + 1);
            if ("overwrite".equalsIgnoreCase(mode.getLexeme()) && hasColumns(visible, index + 2)) {
                replacements.add(mode.getIndex());
            }
        }
        return replacements;
    }

    private static boolean hasColumns(List<LexToken> tokens, int start) {
        int index = start;
        if ("table".equalsIgnoreCase(tokens.get(index).getLexeme())) {
            index++;
        }
        if (index >= tokens.size() || "directory".equalsIgnoreCase(tokens.get(index).getLexeme())) {
            return false;
        }
        index++;
        while (index + 1 < tokens.size() && ".".equals(tokens.get(index).getLexeme())) {
            index += 2;
        }
        if (index < tokens.size() && "partition".equalsIgnoreCase(tokens.get(index).getLexeme())) {
            index += 2;
            while (index < tokens.size() && !")".equals(tokens.get(index).getLexeme())) {
                index++;
            }
            index++;
        }
        return index < tokens.size() && "(".equals(tokens.get(index).getLexeme());
    }
}
