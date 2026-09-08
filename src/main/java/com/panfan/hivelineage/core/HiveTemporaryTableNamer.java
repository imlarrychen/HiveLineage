package com.panfan.hivelineage.core;

import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import java.util.Locale;

final class HiveTemporaryTableNamer {
    private static final String TEMPORARY_DATABASE_PREFIX = "seele_tmp_db";

    private HiveTemporaryTableNamer() {
    }

    static String temporaryDatabase(LineageAnalyzeRequest request) {
        return TEMPORARY_DATABASE_PREFIX
            + "_" + normalizeIdPart(request.getWorkflowId())
            + "_" + normalizeIdPart(request.getTaskId());
    }

    static String qualify(String tableName, String temporaryDatabase) {
        return temporaryDatabase + "." + simpleName(normalizeLogicalName(tableName));
    }

    static String simpleName(String tableName) {
        String normalized = normalizeLogicalName(tableName);
        int dot = normalized.lastIndexOf('.');
        return dot < 0 ? normalized : normalized.substring(dot + 1);
    }

    static String normalizeLogicalName(String value) {
        return value == null ? "" : value.replace("`", "").toLowerCase(Locale.ROOT);
    }

    private static String normalizeIdPart(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "unknown";
        }
        String normalized = value.trim().replace("`", "").toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder();
        boolean previousUnderscore = false;
        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);
            boolean keep = Character.isLetterOrDigit(character) || character == '_';
            if (keep) {
                builder.append(character);
                previousUnderscore = false;
            } else if (!previousUnderscore) {
                builder.append('_');
                previousUnderscore = true;
            }
        }
        trimTrailingUnderscore(builder);
        return builder.length() == 0 ? "unknown" : builder.toString();
    }

    private static void trimTrailingUnderscore(StringBuilder builder) {
        while (builder.length() > 0 && builder.charAt(builder.length() - 1) == '_') {
            builder.deleteCharAt(builder.length() - 1);
        }
    }
}
