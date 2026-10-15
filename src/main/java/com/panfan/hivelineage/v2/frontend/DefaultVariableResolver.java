package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DefaultVariableResolver implements VariableResolver {
    private static final Pattern PLACEHOLDER = Pattern.compile("([$@])\\{(?:([A-Za-z][A-Za-z0-9_]*):)?([^{}]+)}");

    @Override
    public ExpansionResult expand(ScriptUnit unit, VariableContext context, VariableOptions options) {
        String expandedId = unit.getSourceId() + ":expanded:" + unit.getStatementIndex();
        StringBuilder text = new StringBuilder();
        List<SourceMapEntry> mappings = new ArrayList<SourceMapEntry>();
        List<VariableEvidence> evidence = new ArrayList<VariableEvidence>();
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        Matcher matcher = PLACEHOLDER.matcher(unit.getRawText());
        int originalPosition = 0;
        boolean unresolved = false;
        while (matcher.find()) {
            if (!appendUnchanged(unit, expandedId, text, mappings, originalPosition, matcher.start(), options)) {
                return expansionLimit(unit, diagnostics);
            }
            boolean template = "@".equals(matcher.group(1));
            String namespace = matcher.group(2) == null ? (template ? "environment" : "unqualified") : matcher.group(2);
            String name = matcher.group(3);
            String value = template && (options.getPolicy() == VariableResolutionPolicy.LEGACY_FALLBACK
                    || options.getPolicy() == VariableResolutionPolicy.SPEC_VARIABLES)
                    ? null : lookup(context, namespace, name);
            VariableResolutionKind kind = value == null ? VariableResolutionKind.UNRESOLVED : VariableResolutionKind.RESOLVED;
            SourceRef reference = originalRef(unit, matcher.start(), matcher.end());
            boolean fallback = fallbackAllowed(template, name, options);
            if (value == null && fallback) {
                value = options.getFallbackValue();
                kind = value == null ? VariableResolutionKind.UNRESOLVED : VariableResolutionKind.FALLBACK;
            }
            if (value == null) {
                unresolved = true;
                diagnostics.add(new Diagnostic(AnalysisStage.FRONTEND, "UNRESOLVED_VARIABLE",
                        "No value is available for " + namespace + ":" + name, reference));
            }
            String replacement = value == null ? matcher.group() : value;
            if (exceedsLimit(text.length(), replacement.length(), options)) {
                return expansionLimit(unit, diagnostics);
            }
            int expandedStart = text.length();
            text.append(replacement);
            mappings.add(new SourceMapEntry(reference.getSpan(), new SourceSpan(expandedId, expandedStart, text.length()),
                    SourceMappingKind.VARIABLE_REPLACEMENT));
            evidence.add(new VariableEvidence(namespace, name, value, kind, reference));
            originalPosition = matcher.end();
        }
        if (!appendUnchanged(unit, expandedId, text, mappings, originalPosition, unit.getRawText().length(), options)) {
            return expansionLimit(unit, diagnostics);
        }
        ExpandedUnit expanded = new ExpandedUnit(unit, expandedId, text.toString(),
                new SourceMap(unit.getSourceId(), expandedId, mappings), evidence);
        StageOutcome outcome = unresolved ? StageOutcome.PARTIAL : StageOutcome.SUCCESS;
        if (unresolved && options.getPolicy() == VariableResolutionPolicy.STRICT) {
            outcome = StageOutcome.FAILED;
        }
        return new ExpansionResult(expanded, diagnostics, outcome);
    }

    private boolean fallbackAllowed(boolean template, String name, VariableOptions options) {
        return options.getPolicy() == VariableResolutionPolicy.LEGACY_FALLBACK
                || options.getPolicy() == VariableResolutionPolicy.SPEC_VARIABLES
                && (template || options.getBuiltinFallbackVariables().contains(name));
    }

    private String lookup(VariableContext context, String namespace, String name) {
        String canonical = namespace.toLowerCase(Locale.ROOT);
        canonical = "env".equals(canonical) ? "environment" : canonical;
        Map<String, String> values = namespace(context, canonical);
        String value = values == null ? null : values.get(name);
        if (value == null && "unqualified".equals(canonical)) {
            value = unqualified(context, name);
        }
        return value;
    }

    private String unqualified(VariableContext context, String name) {
        for (String namespace : new String[]{"", "default", "hiveconf", "hivevar", "runtime"}) {
            Map<String, String> values = namespace(context, namespace);
            if (values != null && values.containsKey(name)) {
                return values.get(name);
            }
        }
        return null;
    }

    private Map<String, String> namespace(VariableContext context, String namespace) {
        Map<String, String> values = context.getNamespaces().get(namespace);
        return values == null ? context.getNamespaces().get(namespace.toUpperCase(Locale.ROOT)) : values;
    }

    private boolean appendUnchanged(ScriptUnit unit, String expandedId, StringBuilder text,
                                    List<SourceMapEntry> mappings, int start, int end, VariableOptions options) {
        if (start == end) {
            return true;
        }
        if (exceedsLimit(text.length(), end - start, options)) {
            return false;
        }
        int expandedStart = text.length();
        text.append(unit.getRawText(), start, end);
        mappings.add(new SourceMapEntry(originalRef(unit, start, end).getSpan(),
                new SourceSpan(expandedId, expandedStart, text.length()), SourceMappingKind.UNCHANGED));
        return true;
    }

    private boolean exceedsLimit(int current, int appended, VariableOptions options) {
        long projected = (long) current + appended;
        return projected > Integer.MAX_VALUE || options.getMaximumExpandedLength() > 0
                && projected > options.getMaximumExpandedLength();
    }

    private ExpansionResult expansionLimit(ScriptUnit unit, List<Diagnostic> diagnostics) {
        diagnostics.add(new Diagnostic(AnalysisStage.FRONTEND, "EXPANSION_SIZE_LIMIT",
                "Expanded unit exceeds the configured character limit", unit.getSourceRef()));
        return new ExpansionResult(null, diagnostics, StageOutcome.FAILED);
    }

    private SourceRef originalRef(ScriptUnit unit, int start, int end) {
        int offset = unit.getSourceRef().getSpan().getStart();
        return new SourceRef(unit.getSourceId(), new SourceSpan(unit.getSourceId(), offset + start, offset + end));
    }
}
