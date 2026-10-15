package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.lexical.LexToken;
import com.panfan.hivelineage.v2.lexical.LexedUnit;
import com.panfan.hivelineage.v2.lexical.TokenChannel;
import com.panfan.hivelineage.v2.syntax.OtherStatementSyntax;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Locale;

/** Recovery is confined to non-query DDL auditing; it never invents a valid definition or effect. */
final class HiveDdlAuditRecovery {
    private static final Set<String> FAMILIES = new HashSet<String>(Arrays.asList(
            "TABLE", "VIEW", "DATABASE", "SCHEMA", "FUNCTION", "MACRO", "INDEX"));
    OtherStatementSyntax recover(LexedUnit unit) {
        List<String> words = new ArrayList<String>();
        for (LexToken token : unit.getTokens()) {
            if (token.getChannel() == TokenChannel.DEFAULT) {
                words.add(token.getLexeme().toUpperCase(Locale.ROOT));
            }
        }
        if (words.size() < 2 || words.contains("SELECT") || words.contains("WITH")) {
            return null;
        }
        String operation = words.get(0);
        if (!"CREATE".equals(operation) && !"DROP".equals(operation) && !"ALTER".equals(operation) && !"TRUNCATE".equals(operation)) {
            return null;
        }
        int object = 1;
        if ("TEMPORARY".equals(words.get(object)) || "EXTERNAL".equals(words.get(object))) {
            object++;
        }
        if (object >= words.size()) {
            return null;
        }
        String family = words.get(object);
        if (!FAMILIES.contains(family)) {
            return null;
        }
        return new OtherStatementSyntax(new NodeId(unit.getExpandedUnit().getExpandedSourceId() + ":ddl-audit"),
                unit.getExpandedUnit().getOriginalUnit().getSourceRef(),
                HiveStatementClassification.semantic("TOK_" + operation + family), "INVALID_DDL", false);
    }
}
