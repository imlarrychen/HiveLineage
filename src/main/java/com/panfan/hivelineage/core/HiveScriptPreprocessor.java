package com.panfan.hivelineage.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HiveScriptPreprocessor {
    private static final Pattern USE_DATABASE_CANDIDATE = Pattern.compile(
        "(?is)^use(?:$|[^\\p{L}\\p{N}_])"
    );
    private static final Pattern USE_DATABASE = Pattern.compile(
        "(?is)^use\\s+(`[^`\\s.]+`|[a-zA-Z_][\\w]*)\\s*$"
    );
    private static final Pattern HIVE_CONF_VARIABLE = Pattern.compile("\\$\\{[^}]+}");
    private static final Pattern HIVE_VAR_VARIABLE = Pattern.compile("@\\{[^}]+}");
    private final HivePhysicalNameResolver nameResolver = new HivePhysicalNameResolver();

    boolean isSetStatement(String statement) {
        return statement.trim().toLowerCase(Locale.ROOT).startsWith("set ");
    }

    boolean isUseDatabaseCandidate(String statement) {
        String trimmed = statement == null ? "" : statement.trim();
        return USE_DATABASE_CANDIDATE.matcher(trimmed).find();
    }

    UseDatabaseResolution resolveUseDatabase(String statement, boolean hasUnresolvedVariable) {
        if (hasUnresolvedVariable) {
            return UseDatabaseResolution.invalid("USE database contains an unresolved variable");
        }
        Matcher matcher = USE_DATABASE.matcher(statement == null ? "" : statement.trim());
        if (!matcher.matches()) {
            return UseDatabaseResolution.invalid("Invalid USE database statement");
        }
        try {
            return UseDatabaseResolution.valid(nameResolver.normalizeDatabase(matcher.group(1)));
        } catch (IllegalArgumentException ex) {
            return UseDatabaseResolution.invalid("Invalid USE database identifier: " + ex.getMessage());
        }
    }

    void applySetStatement(String statement, Map<String, String> sessionVariables) {
        String assignment = statement.trim().substring(3).trim();
        int equalsIndex = assignment.indexOf('=');
        if (equalsIndex < 1) {
            return;
        }
        String key = assignment.substring(0, equalsIndex).trim();
        String value = stripQuotedValue(assignment.substring(equalsIndex + 1).trim());
        sessionVariables.put(key, value);
        sessionVariables.put("hiveconf:" + key, value);
        sessionVariables.put("hivevar:" + key, value);
    }

    private String stripQuotedValue(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    String resolveVariables(
        String statement,
        Map<String, String> sessionVariables,
        String fallbackVariableValue
    ) {
        String resolved = replaceSessionVariables(statement, sessionVariables);
        resolved = replaceUnresolvedVariables(
            resolved,
            HIVE_CONF_VARIABLE,
            fallbackVariableValue
        );
        return replaceUnresolvedVariables(
            resolved,
            HIVE_VAR_VARIABLE,
            fallbackVariableValue
        );
    }

    boolean hasFallbackVariable(String statement, Map<String, String> sessionVariables) {
        String resolved = replaceSessionVariables(statement, sessionVariables);
        return HIVE_CONF_VARIABLE.matcher(resolved).find() || HIVE_VAR_VARIABLE.matcher(resolved).find();
    }

    private String replaceSessionVariables(String statement, Map<String, String> sessionVariables) {
        String resolved = statement;
        for (Map.Entry<String, String> entry : sessionVariables.entrySet()) {
            resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return resolved;
    }

    private String replaceUnresolvedVariables(
        String statement,
        Pattern variablePattern,
        String fallbackVariableValue
    ) {
        Matcher matcher = variablePattern.matcher(statement);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(fallbackVariableValue));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    List<String> splitStatements(String rawScript) {
        if (rawScript == null || rawScript.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        int position = 0;
        while (position < rawScript.length()) {
            char ch = rawScript.charAt(position);
            char next = position + 1 < rawScript.length() ? rawScript.charAt(position + 1) : '\0';

            if (inLineComment) {
                current.append(ch);
                if (ch == '\n' || ch == '\r') {
                    inLineComment = false;
                }
                position++;
                continue;
            }
            if (inBlockComment) {
                current.append(ch);
                if (ch == '*' && next == '/') {
                    current.append(next);
                    position++;
                    inBlockComment = false;
                }
                position++;
                continue;
            }

            if (!inSingleQuote && !inDoubleQuote && ch == '-' && next == '-') {
                current.append(ch).append(next);
                position += 2;
                inLineComment = true;
                continue;
            }
            if (!inSingleQuote && !inDoubleQuote && ch == '/' && next == '*') {
                current.append(ch).append(next);
                position += 2;
                inBlockComment = true;
                continue;
            }

            if (!inDoubleQuote && ch == '\'') {
                current.append(ch);
                if (inSingleQuote && next == '\'') {
                    current.append(next);
                    position += 2;
                    continue;
                }
                inSingleQuote = !inSingleQuote;
                position++;
                continue;
            }
            if (!inSingleQuote && ch == '"') {
                current.append(ch);
                inDoubleQuote = !inDoubleQuote;
                position++;
                continue;
            }

            if (!inSingleQuote && !inDoubleQuote && ch == ';') {
                addStatement(statements, current);
                current.setLength(0);
                position++;
                continue;
            }
            current.append(ch);
            position++;
        }
        addStatement(statements, current);
        return statements;
    }

    String stripLeadingComments(String statement) {
        String remaining = statement == null ? "" : statement;
        boolean changed = true;
        while (changed) {
            changed = false;
            remaining = remaining.trim();
            if (remaining.startsWith("--")) {
                int lineEnd = indexOfLineEnd(remaining);
                remaining = lineEnd < 0 ? "" : remaining.substring(lineEnd + 1);
                changed = true;
            } else if (remaining.startsWith("/*")) {
                int blockEnd = remaining.indexOf("*/");
                remaining = blockEnd < 0 ? "" : remaining.substring(blockEnd + 2);
                changed = true;
            }
        }
        return remaining;
    }

    private int indexOfLineEnd(String value) {
        int newline = value.indexOf('\n');
        int carriageReturn = value.indexOf('\r');
        if (newline < 0) {
            return carriageReturn;
        }
        if (carriageReturn < 0) {
            return newline;
        }
        return Math.min(newline, carriageReturn);
    }

    private void addStatement(List<String> statements, StringBuilder statement) {
        String trimmed = statement.toString().trim();
        if (!trimmed.isEmpty()) {
            statements.add(trimmed);
        }
    }

}
