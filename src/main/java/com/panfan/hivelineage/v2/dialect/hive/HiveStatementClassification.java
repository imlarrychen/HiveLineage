package com.panfan.hivelineage.v2.dialect.hive;

/**
 * Native vocabulary is translated before crossing the syntax adapter boundary.
 */
final class HiveStatementClassification {
    private HiveStatementClassification() {
    }

    static String semantic(String nativeKind) {
        if ("TOK_CREATEDATABASE".equals(nativeKind)) {
            return "CREATE_DATABASE";
        }
        if ("TOK_DROPDATABASE".equals(nativeKind)) {
            return "DROP_DATABASE";
        }
        if ("TOK_CREATEMACRO".equals(nativeKind)) {
            return "CREATE_TEMPORARY_MACRO";
        }
        if ("TOK_DROPMACRO".equals(nativeKind)) {
            return "DROP_TEMPORARY_MACRO";
        }
        if ("TOK_CREATEINDEX".equals(nativeKind)) {
            return "CREATE_INDEX";
        }
        if ("TOK_DROPINDEX".equals(nativeKind)) {
            return "DROP_INDEX";
        }
        if ("TOK_ALTERTABLE".equals(nativeKind)) {
            return "ALTER_TABLE";
        }
        return nativeKind.startsWith("TOK_") ? nativeKind.substring(4) : nativeKind;
    }

    static boolean dataChanging(String nativeKind) {
        return nativeKind.contains("MATERIALIZED_VIEW") && !nativeKind.startsWith("TOK_DROP_")
                || "TOK_IMPORT".equals(nativeKind) || "TOK_EXPORT".equals(nativeKind);
    }
}
