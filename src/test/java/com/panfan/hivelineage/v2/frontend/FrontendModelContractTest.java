package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.SourceSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

public class FrontendModelContractTest {
    @Test
    public void replacementRetainsOriginalAndExpandedCoordinates() {
        SourceRef original = new SourceRef("script", new SourceSpan("script", 0, 18));
        ScriptUnit unit = new ScriptUnit("script", 0, "select ${hivevar:x}", ScriptUnitKind.SQL, original);
        SourceMapEntry replacement = new SourceMapEntry(new SourceSpan("script", 7, 18),
                new SourceSpan("expanded", 7, 8), SourceMappingKind.VARIABLE_REPLACEMENT);
        List<SourceMapEntry> entries = new ArrayList<SourceMapEntry>();
        entries.add(replacement);
        SourceMap sourceMap = new SourceMap("script", "expanded", entries);
        VariableEvidence evidence = new VariableEvidence("hivevar", "x", "1", VariableResolutionKind.RESOLVED, original);
        ExpandedUnit expanded = new ExpandedUnit(unit, "expanded", "select 1", sourceMap, Collections.singletonList(evidence));
        entries.clear();

        Assert.assertSame(unit, expanded.getOriginalUnit());
        Assert.assertEquals("select ${hivevar:x}", unit.getRawText());
        Assert.assertEquals("select 1", expanded.getExpandedText());
        Assert.assertEquals(18, sourceMap.getEntries().get(0).getOriginalSpan().getEnd());
        Assert.assertEquals(8, sourceMap.getEntries().get(0).getExpandedSpan().getEnd());
        Assert.assertEquals(VariableResolutionKind.RESOLVED, expanded.getVariableEvidence().get(0).getResolution());
    }

    @Test
    public void variableNamespacesAreDeeplyImmutableAndExplicit() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("db", "ods");
        Map<String, Map<String, String>> namespaces = new LinkedHashMap<String, Map<String, String>>();
        namespaces.put("hivevar", values);
        VariableContext context = new VariableContext(namespaces);
        values.put("db", "other");
        namespaces.clear();

        Assert.assertEquals("ods", context.getNamespaces().get("hivevar").get("db"));
        try {
            context.getNamespaces().get("hivevar").put("db", "mutated");
            Assert.fail("nested variable values must be immutable");
        } catch (UnsupportedOperationException expected) {
            Assert.assertEquals("ods", context.getNamespaces().get("hivevar").get("db"));
        }
    }

    @Test
    public void unresolvedEvidenceIsDistinctFromAConstantReplacement() {
        VariableEvidence unknown = new VariableEvidence("hivevar", "unknown", null,
                VariableResolutionKind.UNRESOLVED, SourceRef.synthetic("unknown"));
        VariableEvidence fallback = new VariableEvidence("hivevar", "unknown", "0",
                VariableResolutionKind.FALLBACK, SourceRef.synthetic("fallback"));

        Assert.assertNull(unknown.getReplacement());
        Assert.assertEquals("0", fallback.getReplacement());
        Assert.assertNotEquals(unknown.getResolution(), fallback.getResolution());
    }

    @Test(expected = IllegalArgumentException.class)
    public void successCannotOmitExpandedUnit() {
        new ExpansionResult(null, Collections.<Diagnostic>emptyList(), StageOutcome.SUCCESS);
    }

    @Test
    public void failureCanBeRepresentedWithoutFabricatingAnExpandedUnit() {
        ExpansionResult failure = new ExpansionResult(null, Collections.<Diagnostic>emptyList(), StageOutcome.FAILED);

        Assert.assertNull(failure.getUnit());
        Assert.assertEquals(StageOutcome.FAILED, failure.getOutcome());
    }

    @Test(expected = IllegalArgumentException.class)
    public void scriptPositionCannotBeNegative() {
        new ScriptCursor(-1, 0);
    }

    @Test
    public void frontendContractsDoNotRequireAParserOrProvider() throws Exception {
        Assert.assertEquals(ExpansionResult.class,
                VariableResolver.class.getMethod("expand", ScriptUnit.class, VariableContext.class, VariableOptions.class).getReturnType());
        Assert.assertEquals(ScriptReadResult.class, ScriptUnitReader.class.getMethod("readNext",
                String.class, String.class, ScriptCursor.class, ScriptOptions.class).getReturnType());
    }
}
