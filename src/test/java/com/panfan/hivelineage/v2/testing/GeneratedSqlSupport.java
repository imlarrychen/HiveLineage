package com.panfan.hivelineage.v2.testing;

import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.TableLineage;
import com.panfan.hivelineage.v2.api.AnalysisCapabilities;
import com.panfan.hivelineage.v2.api.AnalysisCapability;
import com.panfan.hivelineage.v2.api.AnalysisContract;
import com.panfan.hivelineage.v2.api.DefaultScriptLineageAnalyzer;
import com.panfan.hivelineage.v2.api.DialectSyntaxFrontend;
import com.panfan.hivelineage.v2.api.ScriptAnalysisRequest;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.compat.DefaultLegacyResultProjector;
import com.panfan.hivelineage.v2.compat.DefaultRequestAdapter;
import com.panfan.hivelineage.v2.compat.HiveScriptLineageAdapter;
import com.panfan.hivelineage.v2.dialect.hive.HiveDialectFactory;
import com.panfan.hivelineage.v2.dialect.hive.HiveParseArtifact;
import com.panfan.hivelineage.v2.frontend.DefaultScriptPreprocessor;
import com.panfan.hivelineage.v2.frontend.DefaultVariableResolver;
import com.panfan.hivelineage.v2.frontend.ScriptOptions;
import com.panfan.hivelineage.v2.frontend.ScriptUnit;
import com.panfan.hivelineage.v2.frontend.VariableContext;
import com.panfan.hivelineage.v2.frontend.VariableOptions;
import com.panfan.hivelineage.v2.frontend.VariableResolutionPolicy;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.metadata.ColumnMetadata;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.DefaultV1MetadataAdapterFactory;
import com.panfan.hivelineage.v2.metadata.LegacyMetadataBinding;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.MetadataProvider;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.metadata.ProviderCapabilities;
import com.panfan.hivelineage.v2.metadata.RelationMetadata;
import com.panfan.hivelineage.v2.metadata.RelationSchema;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;
import com.panfan.hivelineage.v2.syntax.SyntaxResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * File-backed fixtures shared by the V2 scenario suites; unknown tables stay unknown.
 */
public final class GeneratedSqlSupport {
    private GeneratedSqlSupport() {
    }

    public static String readSql(String file) {
        String resource = "/hql/generated/" + file;
        try (InputStream input = GeneratedSqlSupport.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new AssertionError("Missing SQL scenario: " + resource);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("Cannot read SQL scenario: " + resource, failure);
        }
    }

    public static HiveMetadataProvider provider() {
        Map<String, List<String>> schemas = new LinkedHashMap<>();
        List<String> users = Arrays.asList("id", "name", "dept_id", "amount", "dt", "tags", "payload");
        List<String> result = Arrays.asList("id", "name", "amount", "dept_name", "tag", "rn", "dt");
        schemas.put("ods.users", users);
        schemas.put("ods.users_archive", users);
        schemas.put("ods.departments", Arrays.asList("dept_id", "dept_name"));
        schemas.put("ods.orders", Arrays.asList("user_id", "order_id", "amount", "status", "dt"));
        schemas.put("ods.events", Arrays.asList("user_id", "kind", "dt"));
        schemas.put("ods.complex_users", Arrays.asList("id", "tags", "attributes", "profile"));
        schemas.put("dwd.result", result);
        schemas.put("dwd.result_archive", result);
        return table -> schemas.containsKey(table.getQualifiedName())
                ? HiveTableMetadata.table(table, schemas.get(table.getQualifiedName())) : null;
    }

    public static LineageAnalyzeRequest request(String file, LineageParseMode mode) {
        return LineageAnalyzeRequest.builder().workflowId("wf_generated_v2").taskId(file.replace(".sql", ""))
                .taskVersion("V1").defaultDatabase("default").rawScript(readSql(file)).metadataProvider(provider())
                .options(LineageAnalyzeOptions.builder().parseMode(mode).includeIgnoredStatements(true)
                        .expandViewLineage(true).parseJoinConditions(true).build()).build();
    }

    public static ScriptAnalysisResult analyze(String file, LineageParseMode mode) {
        return new HiveScriptLineageAdapter().analyzeNative(request(file, mode));
    }

    /**
     * Inspect normalized syntax too, so dropped options cannot hide behind identical lineage.
     */
    public static List<SyntaxResult> syntax(String file) {
        HiveDialectFactory factory = new HiveDialectFactory();
        DialectSyntaxFrontend<HiveParseArtifact> frontend = new DialectSyntaxFrontend<>(factory);
        List<SyntaxResult> results = new ArrayList<>();
        for (ScriptUnit unit : new DefaultScriptPreprocessor().preprocess(file, readSql(file),
                new ScriptOptions(";", true, 100000)).getUnits()) {
            results.add(frontend.parse(new DefaultVariableResolver().expand(unit,
                    new VariableContext(Collections.emptyMap()), new VariableOptions(VariableResolutionPolicy.LEGACY_FALLBACK,
                            "__HIVE_LINEAGE_DEFAULT__", 16000000)).getUnit(), factory.getProfile()));
        }
        return results;
    }

    public static ScriptAnalysisResult analyzeWithCatalog(String file, LineageParseMode mode) {
        return analyzeWithCapabilities(file, mode, AnalysisCapability.CATALOG_SIMULATION);
    }

    /**
     * V1 has no field types; this native fixture supplies verified ARRAY/MAP/STRUCT metadata.
     */
    public static ScriptAnalysisResult analyzeWithComplexTypes(String file, LineageParseMode mode) {
        LineageAnalyzeRequest legacy = request(file, mode);
        ScriptAnalysisRequest nativeRequest = new DefaultRequestAdapter().adapt(legacy);
        MetadataProvider delegate = new DefaultV1MetadataAdapterFactory().adapt(new LegacyMetadataBinding(
                Optional.of(legacy.getMetadataProvider()), Optional.of(legacy.getMetadataCache()), nativeRequest.getMetadataContext(), false));
        TypeRef number = new TypeRef(TypeRef.Kind.INTEGER, "bigint", Collections.emptyList(), Collections.emptyList());
        TypeRef string = new TypeRef(TypeRef.Kind.STRING, "string", Collections.emptyList(), Collections.emptyList());
        TypeRef account = new TypeRef(TypeRef.Kind.STRUCT, "struct<score:bigint>", Collections.emptyList(),
                Collections.singletonList(new TypeRef.StructField("score", number)));
        Map<String, TypeRef> types = new LinkedHashMap<>();
        types.put("id", number);
        types.put("tags", new TypeRef(TypeRef.Kind.ARRAY, "array<string>", Collections.singletonList(string), Collections.emptyList()));
        types.put("attributes", new TypeRef(TypeRef.Kind.MAP, "map<string,string>", Arrays.asList(string, string), Collections.emptyList()));
        types.put("profile", new TypeRef(TypeRef.Kind.STRUCT, "struct<account:struct<score:bigint>>", Collections.emptyList(),
                Collections.singletonList(new TypeRef.StructField("account", account))));
        MetadataProvider typed = new MetadataProvider() {
            @Override
            public ProviderCapabilities getCapabilities() {
                return delegate.getCapabilities();
            }

            @Override
            public LookupResult<RelationMetadata> lookupRelation(QualifiedName name, MetadataReadContext context) {
                LookupResult<RelationMetadata> loaded = delegate.lookupRelation(name, context);
                if (!name.getDisplayName().equals("ods.complex_users") || !loaded.getValue().isPresent()) {
                    return loaded;
                }
                RelationMetadata relation = loaded.getValue().get();
                RelationSchema old = relation.getSchema();
                List<ColumnMetadata> columns = new ArrayList<>();
                for (ColumnMetadata column : old.getColumns()) {
                    columns.add(new ColumnMetadata(column.getName(), column.getPosition(), types.get(column.getName()),
                            column.getRole(), column.getNullability()));
                }
                return LookupResult.found(new RelationMetadata(relation.getObject(), new RelationSchema(old.getState(),
                        columns, old.getProvenance(), old.getVersion())), loaded.getDiagnostics());
            }
        };
        WorkflowContext workflow = new WorkflowContext(nativeRequest.getTask().getWorkflowId(), nativeRequest.getMetadataContext(),
                typed, new DefaultMetadataCache());
        return new DefaultScriptLineageAnalyzer().analyzeScript(nativeRequest, workflow);
    }

    public static ScriptAnalysisResult analyzeWithExternalLineage(String file, LineageParseMode mode) {
        return analyzeWithCapabilities(file, mode, AnalysisCapability.EXTERNAL_DATA_LINEAGE);
    }

    public static ScriptAnalysisResult analyzeWithCapabilities(String file, LineageParseMode mode, AnalysisCapability... extra) {
        LineageAnalyzeRequest legacy = request(file, mode);
        ScriptAnalysisRequest base = new DefaultRequestAdapter().adapt(legacy);
        AnalysisContract old = base.getContract();
        EnumSet<AnalysisCapability> capabilities = EnumSet.copyOf(old.getCapabilities().getCapabilities());
        capabilities.addAll(Arrays.asList(extra));
        AnalysisContract contract = new AnalysisContract(new AnalysisCapabilities(capabilities), old.getVariablePolicy(),
                old.getDefinitionPolicy(), old.getStarMappingPolicy(), old.getPublicationPolicy(), old.isIncludeTemporaryLineage(),
                old.isIncludeIgnoredStatements(), old.isExpandViewLineage(), old.getMaxViewExpandDepth(), old.isFailFast(),
                old.getFallbackVariableValue());
        ScriptAnalysisRequest nativeRequest = new ScriptAnalysisRequest(base.getTask(), base.getSource(), base.getDialect(),
                base.getInitialSession(), base.getMetadataContext(), contract, base.getResourceLimits());
        WorkflowContext workflow = new WorkflowContext(base.getTask().getWorkflowId(), base.getMetadataContext(),
                new DefaultV1MetadataAdapterFactory().adapt(new LegacyMetadataBinding(Optional.of(legacy.getMetadataProvider()),
                        Optional.of(legacy.getMetadataCache()), base.getMetadataContext(), false)), new DefaultMetadataCache());
        return new DefaultScriptLineageAnalyzer().analyzeScript(nativeRequest, workflow);
    }

    public static LineageAnalyzeResult project(ScriptAnalysisResult result) {
        return new DefaultLegacyResultProjector().project(result).getResult();
    }

    public static StatementLineageResult statement(ScriptAnalysisResult result, int index) {
        return project(result).getStatements().stream().filter(statement -> statement.getStatementIndex() == index)
                .findFirst().orElseThrow(() -> new AssertionError("Missing statement " + index));
    }

    public static void assertNoFailed(ScriptAnalysisResult result) {
        assertEquals("hive-lineage-v2", result.getEngine().getEngineId());
        String diagnostics = result.getDiagnostics().getDiagnostics().stream()
                .map(diagnostic -> diagnostic.getCode() + ": " + diagnostic.getMessage()).collect(Collectors.joining("; "));
        assertEquals(diagnostics, 0, result.getCounts().getFailed());
        assertEquals(result.getCounts().getTotal(), result.getStatements().size());
        assertEquals("V1", result.getTask().getTaskVersion());
        for (StatementLineageResult statement : project(result).getStatements()) {
            assertEquals("wf_generated_v2", statement.getWorkflowId());
            assertEquals(result.getTask().getTaskId(), statement.getTaskId());
            assertEquals("V1", statement.getTaskVersion());
            assertFalse(statement.getRawSql().trim().isEmpty());
            if (statement.getParseMode() == LineageParseMode.TABLE_ONLY) {
                assertTrue(statement.getColumnLineages().isEmpty());
            }
            if (statement.getParseMode() == LineageParseMode.COLUMN_ONLY) {
                assertTrue(statement.getTableLineages().isEmpty());
            }
        }
    }

    public static void assertInputs(StatementLineageResult statement, String... expected) {
        assertEquals(statement.getRawSql(), names(expected), statement.getInputObjects().stream()
                .map(object -> object.getQualifiedName()).collect(Collectors.toCollection(TreeSet::new)));
    }

    public static void assertColumnSources(StatementLineageResult statement, String target, String... expected) {
        if (statement.getParseMode() == LineageParseMode.TABLE_ONLY) {
            assertTrue(statement.getColumnLineages().isEmpty());
            return;
        }
        ColumnLineage column = statement.getColumnLineages().stream()
                .filter(item -> target.equals(item.getTargetColumn().getQualifiedName())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing column mapping: " + target + " in " + statement.getRawSql()));
        assertEquals(target, names(expected), column.getSourceColumns().stream()
                .map(source -> source.getQualifiedName()).collect(Collectors.toCollection(TreeSet::new)));
    }

    public static void assertTableSources(StatementLineageResult statement, String target, String... expected) {
        if (statement.getParseMode() == LineageParseMode.COLUMN_ONLY) {
            assertTrue(statement.getTableLineages().isEmpty());
            return;
        }
        Set<String> actual = new TreeSet<>();
        for (TableLineage table : statement.getTableLineages()) {
            if (target.equals(table.getTargetObject().getQualifiedName())) {
                table.getSourceObjects().forEach(source -> actual.add(source.getQualifiedName()));
            }
        }
        assertEquals(target, names(expected), actual);
    }

    private static Set<String> names(String... values) {
        return new TreeSet<>(Arrays.asList(values));
    }
}
