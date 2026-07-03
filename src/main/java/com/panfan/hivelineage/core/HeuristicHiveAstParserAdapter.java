package com.panfan.hivelineage.core;

import com.panfan.hivelineage.result.DataObjectRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HeuristicHiveAstParserAdapter implements HiveAstParserAdapter {
    private static final Pattern INSERT_TARGET = Pattern.compile(
        "(?is)^insert\\s+(?:overwrite\\s+table|into(?:\\s+table)?)\\s+([a-zA-Z_][\\w.]*)\\b.*"
    );
    private static final Pattern CREATE_TABLE_TARGET = Pattern.compile(
        "(?is)^create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-zA-Z_][\\w.]*)\\b.*"
    );
    private static final Pattern CREATE_TEMPORARY_TABLE_TARGET = Pattern.compile(
        "(?is)^create\\s+temporary\\s+table\\s+([a-zA-Z_][\\w.]*)\\b.*"
    );
    private static final Pattern SELECT_KEYWORD = Pattern.compile("(?is)\\bselect\\b");
    private static final Pattern SOURCE_OBJECT = Pattern.compile("(?is)\\b(?:from|join)\\s+([a-zA-Z_][\\w.]*)");
    private static final Pattern LATERAL_VIEW_EXPLODE = Pattern.compile(
        "(?is)\\blateral\\s+view\\s+explode\\s*\\(\\s*([a-zA-Z_][\\w.]*)\\s*\\)\\s+[a-zA-Z_][\\w]*\\s+as\\s+([a-zA-Z_][\\w]*)\\b"
    );

    @Override
    public ParsedHiveStatement parse(String normalizedSql) {
        String lower = normalizedSql.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("insert ") && SELECT_KEYWORD.matcher(lower).find()) {
            Matcher matcher = INSERT_TARGET.matcher(normalizedSql);
            DataObjectRef outputObject = matcher.matches()
                ? new DataObjectRef(normalizeIdentifier(matcher.group(1)))
                : null;
            return new ParsedHiveStatement(
                StatementType.INSERT_SELECT,
                outputObject,
                extractInputObjects(normalizedSql),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(),
                extractColumnAliasSources(normalizedSql),
                Collections.<String, String>emptyMap(),
                normalizedSql
            );
        }
        if (lower.startsWith("create table ") && SELECT_KEYWORD.matcher(lower).find()) {
            Matcher matcher = CREATE_TABLE_TARGET.matcher(normalizedSql);
            DataObjectRef outputObject = matcher.matches()
                ? new DataObjectRef(normalizeIdentifier(matcher.group(1)))
                : null;
            return new ParsedHiveStatement(
                StatementType.CREATE_TABLE_AS_SELECT,
                outputObject,
                extractInputObjects(normalizedSql),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(),
                extractColumnAliasSources(normalizedSql),
                Collections.<String, String>emptyMap(),
                normalizedSql
            );
        }
        if (lower.startsWith("create temporary table ") && SELECT_KEYWORD.matcher(lower).find()) {
            Matcher matcher = CREATE_TEMPORARY_TABLE_TARGET.matcher(normalizedSql);
            DataObjectRef outputObject = matcher.matches()
                ? new DataObjectRef(normalizeIdentifier(matcher.group(1)))
                : null;
            return new ParsedHiveStatement(
                StatementType.CREATE_TEMPORARY_TABLE_AS_SELECT,
                outputObject,
                extractInputObjects(normalizedSql),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(),
                extractColumnAliasSources(normalizedSql),
                Collections.<String, String>emptyMap(),
                normalizedSql
            );
        }
        return new ParsedHiveStatement(StatementType.UNSUPPORTED, null, new ArrayList<DataObjectRef>(), normalizedSql);
    }

    private List<DataObjectRef> extractInputObjects(String normalizedSql) {
        Set<String> sourceNames = new LinkedHashSet<>();
        Matcher matcher = SOURCE_OBJECT.matcher(normalizedSql);
        while (matcher.find()) {
            sourceNames.add(normalizeIdentifier(matcher.group(1)));
        }
        List<DataObjectRef> inputObjects = new ArrayList<>();
        for (String sourceName : sourceNames) {
            inputObjects.add(new DataObjectRef(sourceName));
        }
        return inputObjects;
    }

    private Map<String, String> extractColumnAliasSources(String normalizedSql) {
        Map<String, String> aliasSources = new HashMap<>();
        Matcher matcher = LATERAL_VIEW_EXPLODE.matcher(normalizedSql);
        while (matcher.find()) {
            aliasSources.put(
                normalizeIdentifier(matcher.group(2)),
                normalizeIdentifier(matcher.group(1))
            );
        }
        return aliasSources;
    }

    private String normalizeIdentifier(String identifier) {
        return identifier == null ? null : identifier.trim().toLowerCase(Locale.ROOT);
    }
}
