package com.panfan.hivelineage.v2.contracts;

import com.panfan.hivelineage.v2.api.StatementLineageIntegrationTest;
import com.panfan.hivelineage.v2.dialect.hive.HiveDdlSyntaxIntegrationTest;
import com.panfan.hivelineage.v2.session.DdlCatalogEffectsIntegrationTest;
import com.panfan.hivelineage.v2.syntax.DefinitionKindSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionStatementSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxResult;
import com.panfan.hivelineage.v2.testing.GeneratedSqlSupport;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * New modeled DDL families must be exercised by saved SQL, in addition to effect assertions.
 */
public class DdlSyntaxCoverageContractTest {
    @Test
    public void everyModeledDdlStatementFamilyIsRepresentedByFileBackedIntegrationCases() {
        Set<String> resources = new TreeSet<>(HiveDdlSyntaxIntegrationTest.scenarioFiles());
        resources.addAll(DdlCatalogEffectsIntegrationTest.scenarioFiles());
        resources.addAll(StatementLineageIntegrationTest.scenarioFiles());
        Map<DefinitionKindSyntax, Set<String>> represented = new EnumMap<>(DefinitionKindSyntax.class);
        for (String resource : resources) {
            for (SyntaxResult syntax : GeneratedSqlSupport.syntax(resource)) {
                if (syntax.getStatement() instanceof DefinitionStatementSyntax) {
                    DefinitionKindSyntax kind = ((DefinitionStatementSyntax) syntax.getStatement()).getDefinition().getKind();
                    represented.computeIfAbsent(kind, key -> new TreeSet<>()).add(resource);
                }
            }
        }
        assertEquals("Adding a new modeled DDL family requires an integration SQL resource", EnumSet.allOf(DefinitionKindSyntax.class),
                represented.keySet());
        for (Map.Entry<DefinitionKindSyntax, Set<String>> item : represented.entrySet()) {
            assertFalse(item.getKey().name(), item.getValue().isEmpty());
        }
    }
}
