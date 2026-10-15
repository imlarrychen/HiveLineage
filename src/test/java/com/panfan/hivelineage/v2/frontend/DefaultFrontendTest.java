package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.SourceSpan;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class DefaultFrontendTest {
    @Test
    public void scriptDelimiterRespectsStringsQuotedIdentifiersAndComments() {
        String script = "-- ;\nselect ';', `a;b`; /* ; */ select 'it\\'s;ok'; -- trailing ;";
        ScriptPreprocessResult result = new DefaultScriptPreprocessor().preprocess("script", script, new ScriptOptions(";", true, 10));
        Assert.assertEquals(StageOutcome.SUCCESS, result.getOutcome());
        Assert.assertEquals(2, result.getUnits().size());
        Assert.assertTrue(result.getUnits().get(0).getRawText().contains("`a;b`"));
        Assert.assertEquals(1, result.getUnits().get(1).getStatementIndex());
        Assert.assertEquals(script.indexOf(" /*"), result.getUnits().get(1).getSourceRef().getSpan().getStart());
    }

    @Test
    public void anUnterminatedUnitHasAFrontendDiagnostic() {
        ScriptPreprocessResult result = new DefaultScriptPreprocessor().preprocess("script", "select 'unterminated",
                new ScriptOptions(";", true, 10));
        Assert.assertEquals(StageOutcome.FAILED, result.getOutcome());
        Assert.assertEquals("UNTERMINATED_SCRIPT_UNIT", result.getDiagnostics().get(0).getCode());
    }

    @Test
    public void eachExpansionUsesTheCurrentExplicitVariables() {
        ScriptUnit unit = unit("select ${hivevar:db}.t, '${day}'", 7);
        Map<String, Map<String, String>> variables = new LinkedHashMap<String, Map<String, String>>();
        variables.put("hivevar", Collections.singletonMap("db", "ods"));
        variables.put("unqualified", Collections.singletonMap("day", "20261012"));
        ExpansionResult result = new DefaultVariableResolver().expand(unit, new VariableContext(variables),
                new VariableOptions(VariableResolutionPolicy.STRICT, null, 1000));
        Assert.assertEquals("select ods.t, '20261012'", result.getUnit().getExpandedText());
        Assert.assertEquals(2, result.getUnit().getVariableEvidence().size());
        SourceMapEntry first = result.getUnit().getSourceMap().getEntries().get(1);
        Assert.assertEquals(14, first.getOriginalSpan().getStart());
        SourceRef mapped = new DefaultSourceMapper().mapToOriginal(result.getUnit().getSourceMap(), first.getExpandedSpan());
        Assert.assertEquals(first.getOriginalSpan(), mapped.getSpan());
    }

    @Test
    public void preservationAndFallbackHaveDifferentEvidenceAndWarnings() {
        ScriptUnit unit = unit("select ${unknown}", 0);
        VariableContext context = new VariableContext(Collections.<String, Map<String, String>>emptyMap());
        ExpansionResult preserved = new DefaultVariableResolver().expand(unit, context,
                new VariableOptions(VariableResolutionPolicy.PRESERVE_AND_WARN, null, 1000));
        ExpansionResult fallback = new DefaultVariableResolver().expand(unit, context,
                new VariableOptions(VariableResolutionPolicy.LEGACY_FALLBACK, "0", 1000));
        Assert.assertEquals("select ${unknown}", preserved.getUnit().getExpandedText());
        Assert.assertEquals(StageOutcome.PARTIAL, preserved.getOutcome());
        Assert.assertEquals("select 0", fallback.getUnit().getExpandedText());
        Assert.assertTrue(fallback.getDiagnostics().isEmpty());
        Assert.assertEquals(VariableResolutionKind.FALLBACK, fallback.getUnit().getVariableEvidence().get(0).getResolution());
    }

    @Test
    public void expansionLimitDoesNotReturnATruncatedSqlUnit() {
        ExpansionResult result = new DefaultVariableResolver().expand(unit("select ${x}", 0),
                new VariableContext(Collections.singletonMap("unqualified", Collections.singletonMap("x", "123456789"))),
                new VariableOptions(VariableResolutionPolicy.STRICT, null, 4));
        Assert.assertEquals(StageOutcome.FAILED, result.getOutcome());
        Assert.assertNull(result.getUnit());
    }

    @Test
    public void expansionStopsAtTheFirstOversizedAppendBeforeInspectingLaterVariables() {
        char[] characters = new char[1024 * 1024];
        java.util.Arrays.fill(characters, 'x');
        VariableContext context = new VariableContext(Collections.singletonMap("unqualified",
                Collections.singletonMap("large", new String(characters))));
        ExpansionResult repeated = new DefaultVariableResolver().expand(unit("${large}${large}${missing}", 0), context,
                new VariableOptions(VariableResolutionPolicy.PRESERVE_AND_WARN, null, 1500000));
        Assert.assertNull(repeated.getUnit());
        Assert.assertEquals(StageOutcome.FAILED, repeated.getOutcome());
        Assert.assertEquals(1, repeated.getDiagnostics().size());
        Assert.assertEquals("EXPANSION_SIZE_LIMIT", repeated.getDiagnostics().get(0).getCode());
        ExpansionResult unchanged = new DefaultVariableResolver().expand(unit("123456${missing}", 0), context,
                new VariableOptions(VariableResolutionPolicy.PRESERVE_AND_WARN, null, 5));
        Assert.assertEquals(1, unchanged.getDiagnostics().size());
        Assert.assertEquals("EXPANSION_SIZE_LIMIT", unchanged.getDiagnostics().get(0).getCode());
    }

    @Test
    public void sessionSetValuesOverrideRuntimeAndLegacyTemplatesRetainFallbackEvidence() {
        Map<String, Map<String, String>> variables = new LinkedHashMap<String, Map<String, String>>();
        variables.put("runtime", Collections.singletonMap("day", "runtime"));
        variables.put("hivevar", Collections.singletonMap("day", "session"));
        variables.put("environment", Collections.singletonMap("run_env", "prod"));
        ScriptUnit input = unit("select '${day}', '${HIVEVAR:day}', '@{run_env}'", 12);
        ExpansionResult legacy = new DefaultVariableResolver().expand(input, new VariableContext(variables),
                new VariableOptions(VariableResolutionPolicy.LEGACY_FALLBACK, "20261012", 1000));
        Assert.assertEquals("select 'session', 'session', '20261012'", legacy.getUnit().getExpandedText());
        Assert.assertEquals(VariableResolutionKind.FALLBACK, legacy.getUnit().getVariableEvidence().get(2).getResolution());
        Assert.assertEquals(input.getRawText().indexOf("@{") + 12,
                legacy.getUnit().getVariableEvidence().get(2).getSourceRef().getSpan().getStart());
        ExpansionResult preserved = new DefaultVariableResolver().expand(input, new VariableContext(variables),
                new VariableOptions(VariableResolutionPolicy.PRESERVE_AND_WARN, null, 1000));
        Assert.assertEquals("select 'session', 'session', 'prod'", preserved.getUnit().getExpandedText());
    }

    @Test
    public void specVariablesFallbackOnlyForBuiltinsAndTemplateVariables() {
        VariableContext context = new VariableContext(Collections.singletonMap("environment",
                Collections.singletonMap("env_name", "real_environment")));
        ScriptUnit input = unit("select '${bizdate}', '${unknown_var}', '@{env_name}', '${day}'", 0);
        ExpansionResult result = new DefaultVariableResolver().expand(input, context,
                new VariableOptions(VariableResolutionPolicy.SPEC_VARIABLES, "20261012", 1000));
        Assert.assertEquals("select '20261012', '${unknown_var}', '20261012', '${day}'", result.getUnit().getExpandedText());
        Assert.assertEquals(StageOutcome.PARTIAL, result.getOutcome());
        Assert.assertEquals(2, result.getDiagnostics().size());
        Assert.assertEquals(VariableResolutionKind.FALLBACK, result.getUnit().getVariableEvidence().get(0).getResolution());
        Assert.assertEquals(VariableResolutionKind.FALLBACK, result.getUnit().getVariableEvidence().get(2).getResolution());
        ExpansionResult configured = new DefaultVariableResolver().expand(input, context,
                new VariableOptions(VariableResolutionPolicy.SPEC_VARIABLES, "20261012", 1000, Collections.singleton("day")));
        Assert.assertEquals("select '${bizdate}', '${unknown_var}', '20261012', '20261012'", configured.getUnit().getExpandedText());
    }

    private ScriptUnit unit(String text, int offset) {
        return new ScriptUnit("script", 0, text, ScriptUnitKind.SQL,
                new SourceRef("script", new SourceSpan("script", offset, offset + text.length())));
    }
}
