# Session 数据库与默认库解析实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使 `defaultDatabase` 成为脚本 Session 的初始库，按语句顺序应用 `USE`，并保证元数据查询与物理血缘统一使用 `database.table[.column]` 标识。

**Architecture:** 在 `ScriptAnalysisState` 中保存请求局部的 `currentDatabase`，并将每条语句开始时的库固化到 `StatementAnalysisContext`。新增独立的物理名称解析器和 AST 绑定器，在元数据、别名、投影、Join、视图与临时表展开前统一物理标识；无法确定裸表所属库时显式失败。

**Tech Stack:** Java 8、Apache Hive Parser 4.0.1、JUnit 4.13.2、Maven Surefire。

---

## 文件结构

- Create: `src/main/java/com/panfan/hivelineage/core/HivePhysicalNameResolver.java`
  - 规范化数据库名，补全物理表和物理字段，不处理脚本状态。
- Create: `src/main/java/com/panfan/hivelineage/core/DatabaseResolutionException.java`
  - 表达物理裸表没有可用数据库的可预期解析失败。
- Create: `src/main/java/com/panfan/hivelineage/core/HiveStatementDatabaseBinder.java`
  - 将 AST 中的物理输入、输出、别名值、集合运算字段和 Join 对象绑定到语句级数据库；保留 CTE、子查询和临时表等逻辑名称。
- Create: `src/main/java/com/panfan/hivelineage/core/UseDatabaseResolution.java`
  - 封装合法或无效 `USE` 的解析结果。
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptPreprocessor.java`
  - 识别与验证 `USE`，支持变量替换后的反引号库名。
- Modify: `src/main/java/com/panfan/hivelineage/core/ScriptAnalysisState.java`
  - 保存和更新 `currentDatabase`。
- Modify: `src/main/java/com/panfan/hivelineage/core/StatementAnalysisContext.java`
  - 暴露不可变的 `statementDatabase`。
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java`
  - 按顺序处理 `USE`，绑定 AST 物理标识，将数据库失败转为语句失败结果。
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveProjectionLineageResolver.java`
  - 将投影映射中已解析到物理表的源字段绑定为完整标识。
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveJoinConditionAnalyzer.java`
  - 纯查询 Join 与正式血缘 Join 共用语句级数据库。
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveLineageExpansionService.java`
  - 视图定义以视图所属库为默认库，临时表在物理补库前优先匹配。
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveTargetColumnResolver.java`
  - 元数据入口要求已绑定的完整物理表名，不再二次猜测。
- Create: `src/test/java/com/panfan/hivelineage/core/HivePhysicalNameResolverTest.java`
- Create: `src/test/java/com/panfan/hivelineage/core/HiveScriptPreprocessorUseDatabaseTest.java`
- Create: `src/test/java/com/panfan/hivelineage/core/ScriptAnalysisStateTest.java`
  - 单元验证名称优先级、反引号和无库失败。
- Create: `src/test/java/com/panfan/hivelineage/HiveSessionDatabaseResolutionTest.java`
  - 覆盖默认库、`USE`、多次切换、元数据、Join、失败恢复与请求隔离。
- Modify: `src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java`
  - 将既有裸表基线的期望值改为 `default.table[.column]`。
- Modify: `src/test/java/com/panfan/hivelineage/HiveJoinLineageTest.java`
- Modify: `src/test/java/com/panfan/hivelineage/HiveQualifiedJoinColumnTest.java`
  - 将既有裸物理 Join 对象的期望值改为完整标识；Join 表达式原文保持不变。
- Modify: `src/test/java/com/panfan/hivelineage/HiveViewAndTemporaryLineageTest.java`
  - 增加视图所属库与临时表回归用例。
- Create: `docs/bugfixes/2026-08-28-session-database-resolution.md`
  - 沉淀问题 1 的现象、根因、修复规则、测试索引和完整验证结果。

### Task 1: 锁定名称解析规则

**Files:**
- Create: `src/test/java/com/panfan/hivelineage/core/HivePhysicalNameResolverTest.java`
- Create: `src/main/java/com/panfan/hivelineage/core/HivePhysicalNameResolver.java`
- Create: `src/main/java/com/panfan/hivelineage/core/DatabaseResolutionException.java`

- [ ] **Step 1: 编写名称解析器的失败测试**

```java
package com.panfan.hivelineage.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class HivePhysicalNameResolverTest {
    private final HivePhysicalNameResolver resolver = new HivePhysicalNameResolver();

    @Test
    public void bareTableUsesStatementDatabase() {
        assertEquals("whc.t1", resolver.qualifyTable("t1", "whc"));
    }

    @Test
    public void explicitDatabaseWins() {
        assertEquals("whc_02.t2", resolver.qualifyTable("whc_02.t2", "whc"));
    }

    @Test
    public void identifiersAreNormalized() {
        assertEquals("whc.t1", resolver.qualifyTable("`WHC`.`T1`", "`WHC`"));
    }

    @Test(expected = DatabaseResolutionException.class)
    public void bareTableWithoutDatabaseFails() {
        resolver.qualifyTable("t1", null);
    }
}
```

- [ ] **Step 2: 运行测试并确认因类缺失而失败**

Run:

```bash
mvn -q -Dtest=HivePhysicalNameResolverTest test
```

Expected: FAIL，编译器报告 `HivePhysicalNameResolver` 和 `DatabaseResolutionException` 不存在。

- [ ] **Step 3: 实现最小名称解析器**

```java
package com.panfan.hivelineage.core;

final class DatabaseResolutionException extends RuntimeException {
    private final String tableName;

    DatabaseResolutionException(String tableName) {
        super("Database cannot be resolved for table " + tableName);
        this.tableName = tableName;
    }

    String getTableName() {
        return tableName;
    }
}
```

```java
package com.panfan.hivelineage.core;

import java.util.Locale;

final class HivePhysicalNameResolver {
    String normalizeDatabase(String database) {
        String normalized = normalizeParts(database);
        return normalized == null || normalized.isEmpty() ? null : normalized;
    }

    String qualifyTable(String tableName, String statementDatabase) {
        String normalizedTable = normalizeParts(tableName);
        if (normalizedTable == null || normalizedTable.isEmpty()) {
            throw new DatabaseResolutionException(String.valueOf(tableName));
        }
        String[] parts = normalizedTable.split("\\.");
        if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return normalizedTable;
        }
        if (parts.length != 1) {
            throw new IllegalArgumentException("Expected table or database.table, got " + tableName);
        }
        String database = normalizeDatabase(statementDatabase);
        if (database == null) {
            throw new DatabaseResolutionException(normalizedTable);
        }
        return database + "." + normalizedTable;
    }

    private String normalizeParts(String identifier) {
        if (identifier == null) {
            return null;
        }
        return identifier.trim().replace("`", "").toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 4: 运行定向测试**

Run: `mvn -q -Dtest=HivePhysicalNameResolverTest test`

Expected: 4 tests, 0 failures, 0 errors.

- [ ] **Step 5: 提交名称解析基础**

```bash
git add src/main/java/com/panfan/hivelineage/core/DatabaseResolutionException.java src/main/java/com/panfan/hivelineage/core/HivePhysicalNameResolver.java src/test/java/com/panfan/hivelineage/core/HivePhysicalNameResolverTest.java
git commit -m "feat: add physical table name resolver"
```

### Task 2: 建立 Session 库状态与 `USE` 解析

**Files:**
- Create: `src/main/java/com/panfan/hivelineage/core/UseDatabaseResolution.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptPreprocessor.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/ScriptAnalysisState.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/StatementAnalysisContext.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java`
- Create: `src/test/java/com/panfan/hivelineage/core/HiveScriptPreprocessorUseDatabaseTest.java`
- Create: `src/test/java/com/panfan/hivelineage/core/ScriptAnalysisStateTest.java`

- [ ] **Step 1: 编写 `USE` 语法与 Session 状态的失败测试**

```java
package com.panfan.hivelineage.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HiveScriptPreprocessorUseDatabaseTest {
    private final HiveScriptPreprocessor preprocessor = new HiveScriptPreprocessor();

    @Test
    public void parsesPlainAndBacktickDatabase() {
        assertEquals("db1", preprocessor.resolveUseDatabase("use db1", false).getDatabase());
        assertEquals("whc", preprocessor.resolveUseDatabase("USE `WHC`", false).getDatabase());
    }

    @Test
    public void rejectsInvalidOrUnresolvedUse() {
        assertFalse(preprocessor.resolveUseDatabase("use", false).isValid());
        assertFalse(preprocessor.resolveUseDatabase("use db1 extra", false).isValid());
        assertFalse(preprocessor.resolveUseDatabase("use __fallback__", true).isValid());
        assertTrue(preprocessor.isUseStatementCandidate("use"));
    }
}
```

```java
package com.panfan.hivelineage.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ScriptAnalysisStateTest {
    @Test
    public void currentDatabaseStartsFromRequestAndCanSwitch() {
        ScriptAnalysisState state = new ScriptAnalysisState("`DB1`", new HivePhysicalNameResolver());
        assertEquals("db1", state.getCurrentDatabase());
        state.useDatabase("db3");
        assertEquals("db3", state.getCurrentDatabase());
    }
}
```

- [ ] **Step 2: 运行测试并确认因新类和方法缺失而失败**

Run: `mvn -q -Dtest=HiveScriptPreprocessorUseDatabaseTest,ScriptAnalysisStateTest test`

Expected: FAIL，编译器报告 `UseDatabaseResolution`、`resolveUseDatabase` 和新构造器不存在。

- [ ] **Step 3: 实现 `USE` 解析值对象与预处理方法**

```java
final class UseDatabaseResolution {
    private final boolean valid;
    private final String database;
    private final String failureMessage;

    private UseDatabaseResolution(boolean valid, String database, String failureMessage) {
        this.valid = valid;
        this.database = database;
        this.failureMessage = failureMessage;
    }

    static UseDatabaseResolution valid(String database) {
        return new UseDatabaseResolution(true, database, null);
    }

    static UseDatabaseResolution invalid(String failureMessage) {
        return new UseDatabaseResolution(false, null, failureMessage);
    }

    boolean isValid() { return valid; }
    String getDatabase() { return database; }
    String getFailureMessage() { return failureMessage; }
}
```

`HiveScriptPreprocessor` 增加：

```java
boolean isUseStatementCandidate(String statement) {
    String lower = statement == null ? "" : statement.trim().toLowerCase(Locale.ROOT);
    return "use".equals(lower) || lower.startsWith("use ");
}

UseDatabaseResolution resolveUseDatabase(String statement, boolean hasUnresolvedVariable) {
    if (hasUnresolvedVariable) {
        return UseDatabaseResolution.invalid("USE database contains an unresolved variable");
    }
    Matcher matcher = Pattern.compile("(?is)^use\\s+(`[^`]+`|[a-zA-Z_][\\w]*)\\s*$")
        .matcher(statement == null ? "" : statement.trim());
    if (!matcher.matches()) {
        return UseDatabaseResolution.invalid("Invalid USE database statement");
    }
    String database = matcher.group(1).replace("`", "").trim().toLowerCase(Locale.ROOT);
    return database.isEmpty()
        ? UseDatabaseResolution.invalid("USE database is empty")
        : UseDatabaseResolution.valid(database);
}
```

- [ ] **Step 4: 把当前库加入请求局部状态和语句上下文**

`ScriptAnalysisState` 改为通过构造器初始化库：

```java
private String currentDatabase;

ScriptAnalysisState(String defaultDatabase, HivePhysicalNameResolver nameResolver) {
    this.currentDatabase = nameResolver.normalizeDatabase(defaultDatabase);
}

String getCurrentDatabase() {
    return currentDatabase;
}

void useDatabase(String database) {
    currentDatabase = database;
}
```

`StatementAnalysisContext` 构造器新增 `String statementDatabase`，并增加：

```java
private final String statementDatabase;

String getStatementDatabase() {
    return statementDatabase;
}
```

- [ ] **Step 5: 同步现有构造点并运行定向测试**

`HiveScriptLineageAnalyzer` 将 `new ScriptAnalysisState()` 改为：

```java
private final HivePhysicalNameResolver nameResolver = new HivePhysicalNameResolver();

ScriptAnalysisState scriptState = new ScriptAnalysisState(request.getDefaultDatabase(), nameResolver);
```

创建 `StatementAnalysisContext` 时增加 `scriptState.getCurrentDatabase()` 参数。此任务只让状态可用，
不在未有集成测试前处理 `USE` 分支。

Run:

```bash
mvn -q -Dtest=HiveScriptPreprocessorUseDatabaseTest,ScriptAnalysisStateTest,HiveScriptProcessingTest test
```

Expected: 所选测试全部通过。

- [ ] **Step 6: 提交 Session 库状态基础**

```bash
git add src/main/java/com/panfan/hivelineage/core/UseDatabaseResolution.java src/main/java/com/panfan/hivelineage/core/HiveScriptPreprocessor.java src/main/java/com/panfan/hivelineage/core/ScriptAnalysisState.java src/main/java/com/panfan/hivelineage/core/StatementAnalysisContext.java src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java src/test/java/com/panfan/hivelineage/core/HiveScriptPreprocessorUseDatabaseTest.java src/test/java/com/panfan/hivelineage/core/ScriptAnalysisStateTest.java
git commit -m "test: define session database behavior"
```

### Task 3: 绑定正式表血缘与字段血缘

**Files:**
- Create: `src/main/java/com/panfan/hivelineage/core/HiveStatementDatabaseBinder.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveProjectionLineageResolver.java`
- Create: `src/test/java/com/panfan/hivelineage/HiveSessionDatabaseResolutionTest.java`
- Modify: `src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java`

- [ ] **Step 1: 增加默认库对表、字段和显式库优先级的失败测试**

`HiveSessionDatabaseResolutionTest` 的文件头为：

```java
package com.panfan.hivelineage;

import com.panfan.hivelineage.core.*;
import com.panfan.hivelineage.metadata.*;
import com.panfan.hivelineage.request.*;
import com.panfan.hivelineage.result.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.*;
import static org.junit.Assert.*;
```

并先增加以下通用构建方法：

```java
private LineageAnalyzeResult analyze(
    String defaultDatabase,
    String script,
    LineageAnalyzeOptions options,
    HiveMetadataProvider metadataProvider
) {
    return new HiveScriptLineageAnalyzer().analyzeScript(LineageAnalyzeRequest.builder()
        .workflowId("wf_database")
        .taskId("task_database")
        .taskVersion("V1")
        .defaultDatabase(defaultDatabase)
        .rawScript(script)
        .metadataProvider(metadataProvider)
        .metadataCache(new HiveMetadataCache())
        .options(options)
        .build());
}

private LineageAnalyzeRequest request(String defaultDatabase, String script) {
    return LineageAnalyzeRequest.builder()
        .workflowId("wf_database")
        .taskId("task_database")
        .taskVersion("V1")
        .defaultDatabase(defaultDatabase)
        .rawScript(script)
        .options(LineageAnalyzeOptions.builder().build())
        .build();
}
```

```java
@Test
public void defaultDatabaseQualifiesBareTablesAndColumns() {
    StatementLineageResult statement = analyze(
        "whc",
        "insert into target_t(id) select s.id from source_t s;",
        LineageAnalyzeOptions.builder().parseMode(LineageParseMode.TABLE_AND_COLUMN).build(),
        null
    ).getStatements().get(0);

    assertEquals("whc.target_t", statement.getOutputObject().getQualifiedName());
    assertContainsSource(statement, "whc.source_t");
    assertEquals("whc.target_t.id", statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName());
    assertContainsColumn(statement.getColumnLineages().get(0).getSourceColumns(), "whc.source_t.id");
}

@Test
public void explicitDatabaseWinsOverSessionDatabase() {
    StatementLineageResult statement = analyze(
        "db1",
        "insert into db2.target_t(id) select id from db3.source_t;",
        LineageAnalyzeOptions.builder().build(),
        null
    ).getStatements().get(0);

    assertEquals("db2.target_t", statement.getOutputObject().getQualifiedName());
    assertContainsSource(statement, "db3.source_t");
}

@Test
public void fullyQualifiedTablesDoNotRequireDefaultDatabase() {
    StatementLineageResult statement = analyze(
        null,
        "insert into db2.target_t(id) select id from db3.source_t;",
        LineageAnalyzeOptions.builder().build(),
        null
    ).getStatements().get(0);

    assertEquals(Integer.valueOf(0), statement.getParseStatusCode());
    assertEquals("db2.target_t", statement.getOutputObject().getQualifiedName());
    assertContainsSource(statement, "db3.source_t");
}
```

- [ ] **Step 2: 运行新测试并确认裸表场景失败**

Run: `mvn -q -Dtest=HiveSessionDatabaseResolutionTest#defaultDatabaseQualifiesBareTablesAndColumns+explicitDatabaseWinsOverSessionDatabase+fullyQualifiedTablesDoNotRequireDefaultDatabase test`

Expected: FAIL，默认库场景的输入、输出和字段仍缺少 `whc.`。

- [ ] **Step 3: 实现 AST 数据库绑定器**

`HiveStatementDatabaseBinder` 的对外接口和核心处理必须为：

```java
final class HiveStatementDatabaseBinder {
    private final HivePhysicalNameResolver nameResolver;

    HiveStatementDatabaseBinder(HivePhysicalNameResolver nameResolver) {
        this.nameResolver = nameResolver;
    }

    ParsedHiveStatement bind(
        ParsedHiveStatement parsed,
        String statementDatabase,
        Set<String> temporaryTableNames,
        boolean temporaryOutput
    ) {
        Map<String, String> physicalNames = new HashMap<>();
        Map<String, Integer> simpleNameCounts = new HashMap<>();
        for (DataObjectRef input : parsed.getInputObjects()) {
            String simple = simpleName(input.getQualifiedName());
            Integer count = simpleNameCounts.get(simple);
            simpleNameCounts.put(simple, count == null ? 1 : count + 1);
        }
        List<DataObjectRef> inputs = new ArrayList<>();
        for (DataObjectRef input : parsed.getInputObjects()) {
            String raw = input.getQualifiedName();
            String bound = containsIgnoreCase(temporaryTableNames, raw)
                ? raw.toLowerCase(Locale.ROOT)
                : nameResolver.qualifyTable(raw, statementDatabase);
            inputs.add(new DataObjectRef(bound));
            physicalNames.put(raw.toLowerCase(Locale.ROOT), bound);
            String simple = simpleName(raw);
            if (Integer.valueOf(1).equals(simpleNameCounts.get(simple))) {
                physicalNames.put(simple, bound);
            }
        }
        DataObjectRef output = parsed.getOutputObject() == null
            ? null
            : new DataObjectRef(temporaryOutput
                ? parsed.getOutputObject().getQualifiedName().toLowerCase(Locale.ROOT)
                : nameResolver.qualifyTable(parsed.getOutputObject().getQualifiedName(), statementDatabase));
        Map<String, String> aliases = bindAliasTargets(parsed.getSourceAliases(), physicalNames);
        return new ParsedHiveStatement(
            parsed.getStatementType(),
            output,
            inputs,
            parsed.getTargetColumns(),
            parsed.getSelectExpressions(),
            aliases,
            bindColumnMap(parsed.getColumnAliasSources(), aliases, physicalNames),
            parsed.getColumnAliasExpressions(),
            bindNestedColumnMap(parsed.getSetOperationSourceColumnsBySelectIndex(), aliases, physicalNames),
            parsed.getSetOperationExpressionsBySelectIndex(),
            parsed.getStaticPartitionValues(),
            bindJoinConditions(parsed.getJoinConditions(), aliases, physicalNames),
            parsed.getNormalizedSql()
        );
    }

    Map<String, String> bindAliasTargets(Map<String, String> aliases, Map<String, String> physicalNames) {
        Map<String, String> bound = new HashMap<>();
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            String replacement = physicalNames.get(entry.getValue().toLowerCase(Locale.ROOT));
            bound.put(entry.getKey(), replacement == null ? entry.getValue() : replacement);
        }
        return bound;
    }

    Map<String, String> bindExtractedAliasTargets(
        Map<String, String> aliases,
        List<DataObjectRef> boundInputs
    ) {
        Map<String, String> physicalNames = new HashMap<>();
        Map<String, Integer> simpleNameCounts = new HashMap<>();
        for (DataObjectRef input : boundInputs) {
            String qualified = input.getQualifiedName().toLowerCase(Locale.ROOT);
            physicalNames.put(qualified, qualified);
            String simple = simpleName(qualified);
            Integer count = simpleNameCounts.get(simple);
            simpleNameCounts.put(simple, count == null ? 1 : count + 1);
        }
        for (DataObjectRef input : boundInputs) {
            String qualified = input.getQualifiedName().toLowerCase(Locale.ROOT);
            String simple = simpleName(qualified);
            if (Integer.valueOf(1).equals(simpleNameCounts.get(simple))) {
                physicalNames.put(simple, qualified);
            }
        }
        return bindAliasTargets(aliases, physicalNames);
    }

    Map<String, String> bindColumnMap(
        Map<String, String> columns,
        Map<String, String> aliases,
        Map<String, String> physicalNames
    ) {
        Map<String, String> bound = new HashMap<>();
        for (Map.Entry<String, String> entry : columns.entrySet()) {
            bound.put(entry.getKey(), bindColumn(entry.getValue(), aliases, physicalNames));
        }
        return bound;
    }

    Map<Integer, List<String>> bindNestedColumnMap(
        Map<Integer, List<String>> columnsByPosition,
        Map<String, String> aliases,
        Map<String, String> physicalNames
    ) {
        Map<Integer, List<String>> bound = new HashMap<>();
        for (Map.Entry<Integer, List<String>> entry : columnsByPosition.entrySet()) {
            List<String> columns = new ArrayList<>();
            for (String column : entry.getValue()) {
                columns.add(bindColumn(column, aliases, physicalNames));
            }
            bound.put(entry.getKey(), columns);
        }
        return bound;
    }

    List<ParsedJoinCondition> bindJoinConditions(
        List<ParsedJoinCondition> conditions,
        Map<String, String> aliases,
        Map<String, String> physicalNames
    ) {
        List<ParsedJoinCondition> bound = new ArrayList<>();
        for (ParsedJoinCondition condition : conditions) {
            List<String> objects = new ArrayList<>();
            for (String object : condition.getDirectJoinObjects()) {
                String replacement = aliases.get(object.toLowerCase(Locale.ROOT));
                if (replacement == null) {
                    replacement = physicalNames.get(object.toLowerCase(Locale.ROOT));
                }
                objects.add(replacement == null ? object : replacement);
            }
            List<String> columns = new ArrayList<>();
            for (String column : condition.getDirectJoinColumns()) {
                columns.add(bindColumn(column, aliases, physicalNames));
            }
            bound.add(new ParsedJoinCondition(
                condition.getJoinIndex(), condition.getQueryBlockPath(),
                condition.getJoinType(), condition.getJoinTypeName(),
                objects, columns, condition.getJoinExpression()
            ));
        }
        return bound;
    }

    List<ColumnRef> bindColumns(List<ColumnRef> columns, Map<String, String> aliases) {
        List<ColumnRef> bound = new ArrayList<>();
        for (ColumnRef column : columns) {
            bound.add(new ColumnRef(bindColumn(column.getQualifiedName(), aliases, aliases)));
        }
        return bound;
    }

    private String bindColumn(
        String column,
        Map<String, String> aliases,
        Map<String, String> physicalNames
    ) {
        String normalized = column.replace("`", "").toLowerCase(Locale.ROOT);
        String[] parts = normalized.split("\\.");
        if (parts.length == 3 || parts.length == 1) {
            return normalized;
        }
        if (parts.length != 2) {
            return normalized;
        }
        String owner = parts[0];
        String physicalOwner = aliases.get(owner);
        if (physicalOwner == null) {
            physicalOwner = physicalNames.get(owner);
        }
        return physicalOwner != null && physicalOwner.contains(".")
            ? physicalOwner + "." + parts[1]
            : normalized;
    }

    private boolean containsIgnoreCase(Set<String> values, String candidate) {
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String simpleName(String tableName) {
        String normalized = tableName.replace("`", "").toLowerCase(Locale.ROOT);
        int dot = normalized.lastIndexOf('.');
        return dot < 0 ? normalized : normalized.substring(dot + 1);
    }
}
```

上述 `simpleNameCounts` 保证出现冲突的简表名不会映射到任意一张物理表；
冲突键由既有歧义解析和 warning 机制处理。

- [ ] **Step 4: 在主分析路径绑定 AST 和文本提取的物理别名**

`HiveScriptLineageAnalyzer` 复用 Task 2 的 `nameResolver` 字段，并增加：

```java
private final HiveStatementDatabaseBinder databaseBinder = new HiveStatementDatabaseBinder(nameResolver);
```

`parseStatement` 的返回值在进入血缘分析前统一绑定：

```java
private ParsedHiveStatement parseAndBind(StatementAnalysisContext context, String sql) {
    ParsedHiveStatement parsed = parserAdapter.parse(sql);
    boolean temporaryOutput = sql.trim().toLowerCase(Locale.ROOT).startsWith("create temporary table ");
    return databaseBinder.bind(
        parsed,
        context.getStatementDatabase(),
        context.getTemporaryTableSources().keySet(),
        temporaryOutput
    );
}
```

将 `analyzeStatement`、`analyzeSelectItemsToTarget` 和集合运算重新解析路径中的
`parserAdapter.parse(...)` 替换为 `parseAndBind(context, ...)`。`targetTable`使用绑定后的
`parsedStatement.getOutputObject().getQualifiedName()`。`extractSourceAliases`、`extractTopLevelSourceAliases`、
`extractProjectionSourceAliases` 和 `extractExplicitSourceAliases` 的结果都立即调用
`databaseBinder.bindExtractedAliasTargets(map, resolvedParsedStatement.getInputObjects())`；再使用绑定后 AST
`sourceAliases` 覆盖物理表值。这保证位置星号的顶层单源判断比较的两侧都是
完整库表名。

- [ ] **Step 5: 绑定字段投影映射中的物理源字段**

`HiveProjectionLineageResolver` 增加一个显式绑定步骤，不改写表达式原文：

```java
Map<String, Map<String, ColumnLineageProjection>> bindPhysicalProjectionSources(
    Map<String, Map<String, ColumnLineageProjection>> projections,
    Map<String, String> physicalAliases,
    HiveStatementDatabaseBinder binder
) {
    Map<String, Map<String, ColumnLineageProjection>> bound = new HashMap<>();
    for (Map.Entry<String, Map<String, ColumnLineageProjection>> table : projections.entrySet()) {
        Map<String, ColumnLineageProjection> columns = new HashMap<>();
        for (Map.Entry<String, ColumnLineageProjection> column : table.getValue().entrySet()) {
            ColumnLineageProjection value = column.getValue();
            columns.put(column.getKey(), new ColumnLineageProjection(
                binder.bindColumns(value.getSourceColumns(), physicalAliases),
                value.getExpression(),
                value.getLineageType(),
                value.isUnresolved()
            ));
        }
        bound.put(table.getKey(), columns);
    }
    return bound;
}
```

`HiveScriptLineageAnalyzer` 在 `extractStatementProjectionTables` 后立即调用该方法，使 CTE/子查询
穿透后的物理字段也使用完整标识。

- [ ] **Step 6: 更新问题 1 直接影响的既有基线**

`HiveColumnLineageTest#expressionColumnLineageRecordsSourceColumnsAndExpression` 的期望值改为：

```java
assertEquals("default.test_b", statement.getTableLineages().get(0).getSourceObjects().get(0).getQualifiedName());
assertEquals("default.test_a", statement.getTableLineages().get(0).getTargetObject().getQualifiedName());
assertEquals("default.test_a.a1", a1.getTargetColumn().getQualifiedName());
assertEquals("default.test_b.b1", a1.getSourceColumns().get(0).getQualifiedName());
assertEquals("default.test_b.b2", a1.getSourceColumns().get(1).getQualifiedName());
assertEquals("default.test_a.a2", a2.getTargetColumn().getQualifiedName());
assertEquals("default.test_b.b3", a2.getSourceColumns().get(0).getQualifiedName());
```

- [ ] **Step 7: 运行表与字段定向测试**

Run:

```bash
mvn -q -Dtest=HivePhysicalNameResolverTest,HiveSessionDatabaseResolutionTest,HiveColumnLineageTest#expressionColumnLineageRecordsSourceColumnsAndExpression test
```

Expected: 所选测试全部通过。

- [ ] **Step 8: 提交主血缘名称绑定**

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveStatementDatabaseBinder.java src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java src/main/java/com/panfan/hivelineage/core/HiveProjectionLineageResolver.java src/test/java/com/panfan/hivelineage/HiveSessionDatabaseResolutionTest.java src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java
git commit -m "fix: qualify lineage objects with session database"
```

### Task 4: 将 `USE` 接入脚本顺序并实现失败恢复

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java`
- Modify: `src/test/java/com/panfan/hivelineage/HiveSessionDatabaseResolutionTest.java`

- [ ] **Step 1: 增加多次切换、无效 `USE`、无库失败和请求隔离测试**

```java
@Test
public void multipleUseStatementsAffectOnlyFollowingStatements() {
    LineageAnalyzeResult result = analyze(
        "db1",
        "insert into t1(id) select id from s1; use db2; "
            + "insert into t2(id) select id from s2; use db3; "
            + "insert into t3(id) select id from s3;",
        LineageAnalyzeOptions.builder().build(),
        null
    );
    assertEquals("db1.t1", result.getStatements().get(0).getOutputObject().getQualifiedName());
    assertEquals("db2.t2", result.getStatements().get(1).getOutputObject().getQualifiedName());
    assertEquals("db3.t3", result.getStatements().get(2).getOutputObject().getQualifiedName());
}

@Test
public void useSupportsVariableBackticksAndIgnoredRecord() {
    LineageAnalyzeResult result = analyze(
        null,
        "set target_db=whc; use `${target_db}`; insert into t2(id) select id from t1;",
        LineageAnalyzeOptions.builder().includeIgnoredStatements(true).build(),
        null
    );
    assertEquals("USE", result.getStatements().get(1).getStatementType());
    assertEquals(Integer.valueOf(3), result.getStatements().get(1).getParseStatusCode());
    assertEquals("USE_DATABASE", result.getStatements().get(1).getSkipReason());
    assertEquals("whc.t2", result.getStatements().get(2).getOutputObject().getQualifiedName());
    assertContainsSource(result.getStatements().get(2), "whc.t1");
}

@Test
public void bareTableWithoutDatabaseFailsButLaterUseRecovers() {
    LineageAnalyzeResult result = analyze(
        null,
        "insert into t1(id) select id from s1; use db3; insert into t2(id) select id from s2;",
        LineageAnalyzeOptions.builder().build(),
        null
    );
    assertEquals(1, result.getFailedStatementCount());
    assertEquals("DATABASE_NOT_RESOLVED", result.getStatements().get(0).getFailureCode());
    assertEquals("db3.t2", result.getStatements().get(1).getOutputObject().getQualifiedName());
}

@Test
public void invalidUseFailsAndKeepsPreviousDatabase() {
    LineageAnalyzeResult result = analyze(
        "db1",
        "use; insert into t1(id) select id from s1;",
        LineageAnalyzeOptions.builder().build(),
        null
    );
    assertEquals("INVALID_USE_DATABASE", result.getStatements().get(0).getFailureCode());
    assertEquals("db1.t1", result.getStatements().get(1).getOutputObject().getQualifiedName());
}

@Test
public void unresolvedUseVariableFailsAndKeepsPreviousDatabase() {
    LineageAnalyzeResult result = analyze(
        "db1",
        "use `${missing_db}`; insert into t1(id) select id from s1;",
        LineageAnalyzeOptions.builder().build(),
        null
    );
    assertEquals("INVALID_USE_DATABASE", result.getStatements().get(0).getFailureCode());
    assertEquals("db1.t1", result.getStatements().get(1).getOutputObject().getQualifiedName());
}

@Test
public void analyzerDoesNotShareUseDatabaseAcrossRequests() {
    HiveScriptLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
    analyzer.analyzeScript(request(null, "use db3; insert into t1(id) select id from s1;"));
    LineageAnalyzeResult second = analyzer.analyzeScript(request(null, "insert into t2(id) select id from s2;"));
    assertEquals("DATABASE_NOT_RESOLVED", second.getStatements().get(0).getFailureCode());
}

@Test
public void pureSelectJoinWithoutDatabaseFailsWhenJoinParsingIsOn() {
    LineageAnalyzeResult result = analyze(
        null,
        "select * from t1 join db2.t2 on t1.id = db2.t2.id;",
        LineageAnalyzeOptions.builder().parseJoinConditions(true).build(),
        null
    );
    assertEquals(1, result.getFailedStatementCount());
    assertEquals("DATABASE_NOT_RESOLVED", result.getStatements().get(0).getFailureCode());
}
```

- [ ] **Step 2: 运行新测试并确认 Session 切换与错误计数失败**

Run: `mvn -q -Dtest=HiveSessionDatabaseResolutionTest test`

Expected: FAIL，`USE` 仍未更新 `currentDatabase`，且无效 `USE` 尚未产生指定失败码。

- [ ] **Step 3: 在 `analyzeScript` 中按顺序接入 Session 库**

将 `new ScriptAnalysisState()` 改为：

```java
ScriptAnalysisState scriptState = new ScriptAnalysisState(request.getDefaultDatabase(), nameResolver);
```

在变量替换后、`ignoredReason` 之前处理 `USE`：

```java
if (scriptPreprocessor.isUseStatementCandidate(normalized)) {
    boolean unresolved = containsWarning(statementWarnings, WarningCode.UNRESOLVED_VARIABLE);
    UseDatabaseResolution use = scriptPreprocessor.resolveUseDatabase(normalized, unresolved);
    StatementAnalysisContext context = new StatementAnalysisContext(
        request, statement, normalized, i, scriptState,
        scriptState.getCurrentDatabase(), statementWarnings
    );
    if (!use.isValid()) {
        results.add(failedResult(context, "USE", "INVALID_USE_DATABASE", use.getFailureMessage()));
        failed++;
    } else {
        scriptState.useDatabase(use.getDatabase());
        ignored++;
        if (request.getOptions().isIncludeIgnoredStatements()) {
            results.add(ignoredResult(context, "USE", "USE_DATABASE"));
        }
    }
    continue;
}
```

`containsWarning` 使用告警枚举判断，不匹配默认替换文本：

```java
private boolean containsWarning(List<LineageWarning> warnings, WarningCode code) {
    for (LineageWarning warning : warnings) {
        if (warning.getWarningCode() == code) {
            return true;
        }
    }
    return false;
}
```

普通语句的 `StatementAnalysisContext` 传入 `scriptState.getCurrentDatabase()`。

- [ ] **Step 4: 在语句边界转换数据库解析异常**

Join-only 分支在调用 `joinConditionOnlyResult` 的位置转换异常：

```java
try {
    results.add(joinAnalyzer.joinConditionOnlyResult(context));
} catch (DatabaseResolutionException ex) {
    results.add(failedResult(context, "SELECT", "DATABASE_NOT_RESOLVED", ex.getMessage()));
    failed++;
}
continue;
```

正式血缘分支在调用 `analyzeStatement` 的位置转换异常：

```java
StatementLineageResult result;
try {
    result = analyzeStatement(context);
} catch (DatabaseResolutionException ex) {
    results.add(failedResult(context, "UNKNOWN", "DATABASE_NOT_RESOLVED", ex.getMessage()));
    failed++;
    continue;
}
```

将现有 `failedResult(context, code, message)` 保留为便捷重载，并新增可指定
`statementType` 的四参数重载，保证无效 `USE` 的 `statementType=USE`。

- [ ] **Step 5: 验证纯 `SELECT` 的既有忽略语义**

在 `HiveSessionDatabaseResolutionTest` 增加：

```java
@Test
public void pureSelectWithoutDatabaseRemainsIgnoredWhenJoinParsingIsOff() {
    LineageAnalyzeResult result = analyze(
        null,
        "select * from t1;",
        LineageAnalyzeOptions.builder().build(),
        null
    );
    assertEquals(1, result.getIgnoredStatementCount());
    assertEquals(0, result.getFailedStatementCount());
    assertTrue(result.getStatements().isEmpty());
}
```

Run: `mvn -q -Dtest=HiveSessionDatabaseResolutionTest test`

Expected: 所有 Session 状态、失败恢复和纯查询测试通过。

- [ ] **Step 6: 提交 Session 执行语义**

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java src/test/java/com/panfan/hivelineage/HiveSessionDatabaseResolutionTest.java
git commit -m "fix: apply use database in script order"
```

### Task 5: 统一 Join 与元数据查询的数据库

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/core/HivePhysicalNameResolver.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveJoinConditionAnalyzer.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveTargetColumnResolver.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveLineageExpansionService.java`
- Modify: `src/test/java/com/panfan/hivelineage/HiveSessionDatabaseResolutionTest.java`
- Modify: `src/test/java/com/panfan/hivelineage/HiveJoinLineageTest.java`

- [ ] **Step 1: 增加用户组合脚本和元数据查询记录的失败测试**

```java
@Test
public void defaultAndUseQualifyUserScenarioJoinAndInsert() {
    RecordingMetadataProvider metadata = new RecordingMetadataProvider()
        .addTable("db1.t1", "id")
        .addTable("db2.t2", "id")
        .addTable("db3.t4", "id");
    LineageAnalyzeResult result = analyze(
        "db1",
        "select * from t1 left join db2.t2 on t1.id = db2.t2.id; "
            + "use db3; insert into t4 select * from db1.t1;",
        LineageAnalyzeOptions.builder()
            .parseMode(LineageParseMode.TABLE_AND_COLUMN)
            .parseJoinConditions(true)
            .build(),
        metadata
    );

    StatementLineageResult select = result.getStatements().get(0);
    JoinCondition join = select.getJoinConditions().get(0);
    assertContainsSource(join.getJoinTables(), "db1.t1");
    assertContainsSource(join.getJoinTables(), "db2.t2");
    assertContainsColumn(join.getJoinColumns(), "db1.t1.id");
    assertContainsColumn(join.getJoinColumns(), "db2.t2.id");

    StatementLineageResult insert = result.getStatements().get(1);
    assertEquals("db3.t4", insert.getOutputObject().getQualifiedName());
    assertContainsSource(insert, "db1.t1");
    assertTrue(metadata.requestedNames().contains("db1.t1"));
    assertTrue(metadata.requestedNames().contains("db3.t4"));
}
```

`HiveSessionDatabaseResolutionTest` 内增加可检查请求参数的 Provider：

```java
private static final class RecordingMetadataProvider implements HiveMetadataProvider {
    private final Map<String, HiveTableMetadata> tables = new HashMap<>();
    private final Set<String> requested = new LinkedHashSet<>();

    RecordingMetadataProvider addTable(String qualifiedName, String... columns) {
        String[] parts = qualifiedName.split("\\.");
        TableIdentifier identifier = TableIdentifier.of("default", parts[0], parts[1]);
        tables.put(qualifiedName, HiveTableMetadata.table(identifier, Arrays.asList(columns)));
        return this;
    }

    @Override
    public HiveTableMetadata getTableMetadata(TableIdentifier tableIdentifier) {
        requested.add(tableIdentifier.getQualifiedName());
        return tables.get(tableIdentifier.getQualifiedName());
    }

    Set<String> requestedNames() {
        return requested;
    }
}
```

- [ ] **Step 2: 运行用户场景并确认 Join 仍泄漏裸标识**

Run: `mvn -q -Dtest=HiveSessionDatabaseResolutionTest#defaultAndUseQualifyUserScenarioJoinAndInsert test`

Expected: FAIL，Join 输入或 Join 字段仍包含 `t1`、`t1.id`，或 Provider 未收到预期库表名。

- [ ] **Step 3: 让 Join 解析使用已绑定 AST**

`HiveJoinConditionAnalyzer` 构造器增加 `HiveStatementDatabaseBinder`。
`joinConditionOnlyResult` 和文本回退分支中的解析改为：

```java
ParsedHiveStatement parsedStatement = databaseBinder.bind(
    parseStatement(normalizedStatement),
    context.getStatementDatabase(),
    context.getTemporaryTableSources().keySet(),
    false
);
```

`sourceAliasesFromDirectJoin`、`directJoinObjects`、`inputObjectsFromSourceAliases` 使用绑定后的
AST 别名值；`joinExpression` 保持 SQL 原文。只有能穿透到物理表的
`directJoinObjects/directJoinColumns` 才转换为完整标识，CTE、子查询和临时表的逻辑引用
保留到展开阶段；对外 `joinTables/joinColumns` 必须是展开后的完整物理标识。

- [ ] **Step 4: 使元数据入口拒绝再次猜测默认库**

`HivePhysicalNameResolver` 增加严格转换：

```java
TableIdentifier toTableIdentifier(String tableName, String clusterName) {
    String[] parts = tableName == null ? new String[0] : tableName.split("\\.");
    if (parts.length != 2) {
        throw new DatabaseResolutionException(tableName);
    }
    return TableIdentifier.of(clusterName, parts[0], parts[1]);
}
```

`HiveTargetColumnResolver` 的两个加载入口以及 `HiveLineageExpansionService.isKnownView`
将 `TableIdentifier.parse(tableName, request.getClusterName(), request.getDefaultDatabase())`
替换为 `nameResolver.toTableIdentifier(tableName, request.getClusterName())`。两个类的构造器显式接收同一个
`HivePhysicalNameResolver`，保证一旦有裸表泄漏到元数据边界，测试会立即暴露。

- [ ] **Step 5: 更新裸物理 Join 对象的既有期望**

`HiveJoinLineageTest#joinConditionKeepsExpressionAndDirectColumns` 中：

```java
assertContainsSource(condition.getDirectJoinObjects(), "default.ods_op_logs");
assertContainsSource(condition.getDirectJoinObjects(), "default.dim_meta_info");
assertContainsColumn(condition.getDirectJoinColumns(), "default.ods_op_logs.biz_trace_id");
assertContainsColumn(condition.getDirectJoinColumns(), "default.dim_meta_info.meta_id");
assertContainsColumn(condition.getJoinColumns(), "default.ods_op_logs.biz_trace_id");
assertContainsColumn(condition.getJoinColumns(), "default.dim_meta_info.meta_id");
assertEquals(
    "split(a.biz_trace_id, '_')[1] = cast(b.meta_id as string)",
    condition.getJoinExpression()
);
```

SQL 别名 `a.biz_trace_id`、`b.meta_id` 已能唯一穿透到物理表，因此
`directJoinColumns` 也断言完整物理标识。CTE/子查询的 `x`、`y` 等逻辑
`directJoinObjects` 期望不改；对应
`joinTables/joinColumns` 继续断言展开后的物理完整名。

- [ ] **Step 6: 运行 Join 和元数据定向测试**

Run:

```bash
mvn -q -Dtest=HiveSessionDatabaseResolutionTest,HiveJoinLineageTest,HiveQualifiedJoinColumnTest test
```

Expected: 所有定向测试通过，用户场景的 Join 物理标识和 Provider 请求一致。

- [ ] **Step 7: 提交 Join 与元数据边界修复**

```bash
git add src/main/java/com/panfan/hivelineage/core/HivePhysicalNameResolver.java src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java src/main/java/com/panfan/hivelineage/core/HiveJoinConditionAnalyzer.java src/main/java/com/panfan/hivelineage/core/HiveTargetColumnResolver.java src/main/java/com/panfan/hivelineage/core/HiveLineageExpansionService.java src/test/java/com/panfan/hivelineage/HiveSessionDatabaseResolutionTest.java src/test/java/com/panfan/hivelineage/HiveJoinLineageTest.java
git commit -m "fix: bind join and metadata to session database"
```

### Task 6: 锁定 CTE、视图与临时表边界

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveLineageExpansionService.java`
- Modify: `src/test/java/com/panfan/hivelineage/HiveViewAndTemporaryLineageTest.java`
- Modify: `src/test/java/com/panfan/hivelineage/HiveSessionDatabaseResolutionTest.java`

- [ ] **Step 1: 增加视图所属库和临时表优先级的失败测试**

```java
@Test
public void unqualifiedTableInViewUsesViewDatabase() {
    MapMetadataProvider metadata = new MapMetadataProvider()
        .addView("view_db.v_user", "select id from base_t", "id")
        .addTable("view_db.base_t", "id");
    StatementLineageResult statement = new HiveScriptLineageAnalyzer().analyzeScript(
        LineageAnalyzeRequest.builder()
            .workflowId("wf_view_db")
            .taskId("task_view_db")
            .taskVersion("V1")
            .defaultDatabase("session_db")
            .rawScript("insert into target_t(id) select id from view_db.v_user")
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .expandViewLineage(true)
                .build())
            .build()
    ).getStatements().get(0);
    assertContainsSource(statement, "view_db.base_t");
    assertNotContainsSource(statement, "session_db.base_t");
}

@Test
public void temporaryTableIsExpandedBeforePermanentTableQualification() {
    LineageAnalyzeResult result = analyze(
        "db1",
        "create temporary table tmp_t as select id from source_t; "
            + "use db2; insert into target_t(id) select id from tmp_t;",
        LineageAnalyzeOptions.builder().parseMode(LineageParseMode.TABLE_AND_COLUMN).build(),
        null
    );
    StatementLineageResult statement = result.getStatements().get(0);
    assertEquals("db2.target_t", statement.getOutputObject().getQualifiedName());
    assertContainsSource(statement, "db1.source_t");
    assertNotContainsSource(statement, "db2.tmp_t");
}
```

- [ ] **Step 2: 运行新回归测试**

Run: `mvn -q -Dtest=HiveViewAndTemporaryLineageTest,HiveSessionDatabaseResolutionTest test`

Expected: FAIL，视图中的 `base_t` 被当前 Session 库解析，或切库后的 `tmp_t` 被当作永久表。

- [ ] **Step 3: 以视图所属库绑定视图定义 AST**

`HiveLineageExpansionService` 获取视图数据库并绑定：

```java
private ParsedHiveStatement parseBoundView(
    StatementAnalysisContext context,
    HiveTableMetadata viewMetadata
) {
    ParsedHiveStatement parsed = parserAdapter.parse(viewMetadata.getViewOriginalSql());
    String viewDatabase = viewMetadata.getTableIdentifier().getDatabaseName();
    return databaseBinder.bind(
        parsed,
        viewDatabase,
        context.getTemporaryTableSources().keySet(),
        false
    );
}
```

`HiveLineageExpansionService` 构造器增加 `HiveStatementDatabaseBinder databaseBinder`，
`HiveScriptLineageAnalyzer` 传入 Task 3 创建的同一绑定器实例。

`expandViewInputObjects` 和 `viewColumnMapping` 都使用 `parseBoundView`，不使用调用语句的
`statementDatabase` 猜测视图内部裸表。

- [ ] **Step 4: 用 Session 级临时表索引保护跨 `USE` 引用**

`registerTemporaryTable` 继续注册临时表原始简名。`HiveStatementDatabaseBinder.bind` 在补库前使用
不区分大小写的临时表集合匹配输入；匹配成功时保留简名，使
`expandTemporaryInputObjects` 优先穿透到创建临时表时已绑定的物理来源。

- [ ] **Step 5: 运行视图、临时表、CTE 和子查询回归**

Run:

```bash
mvn -q -Dtest=HiveViewAndTemporaryLineageTest,HiveScopedCteProjectionWarningTest,HiveScopedViewOwnerResolutionTest,HiveNestedSelectPseudoColumnRegressionTest,HiveSessionDatabaseResolutionTest test
```

Expected: 所选测试全部通过，逻辑别名不被误补库，物理来源全部完整。

- [ ] **Step 6: 提交视图和临时表边界修复**

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java src/main/java/com/panfan/hivelineage/core/HiveLineageExpansionService.java src/test/java/com/panfan/hivelineage/HiveViewAndTemporaryLineageTest.java src/test/java/com/panfan/hivelineage/HiveSessionDatabaseResolutionTest.java
git commit -m "fix: preserve view and temporary database scope"
```

### Task 7: 回归收敛与缺陷文档

**Files:**
- Modify: `src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java`
- Modify: `src/test/java/com/panfan/hivelineage/HiveJoinLineageTest.java`
- Modify: `src/test/java/com/panfan/hivelineage/HiveQualifiedJoinColumnTest.java`
- Modify: `src/test/java/com/panfan/hivelineage/HiveViewAndTemporaryLineageTest.java`
- Create: `docs/bugfixes/2026-08-28-session-database-resolution.md`
- Modify: `docs/superpowers/plans/2026-08-28-session-database-resolution.md`

- [x] **Step 1: 运行完整测试定位所有受新唯一标识契约影响的既有断言**

Run: `mvn clean test`

Expected: 生产逻辑不再出现功能失败；如有失败，只能是旧测试仍断言裸物理表或两段物理字段名。

- [x] **Step 2: 逐条分类失败，只更新已被设计改变的期望**

使用：

```bash
rg -n '"[A-Za-z_][A-Za-z0-9_]*"|"[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*"' src/test/java/com/panfan/hivelineage
```

更新规则：

- SQL 中的物理裸表 `table` 且请求默认库为 `default` 时，血缘期望改为 `default.table`。
- 对应物理字段 `table.column` 改为 `default.table.column`。
- SQL 表达式原文、函数名、别名、CTE 名、子查询名、临时表逻辑名和非物理
  `directJoinColumns` 不做机械替换。
- 任何无法归因于新契约的失败都视为生产回归，返回相应任务修复，不改期望值掩盖。

- [x] **Step 3: 实现缺陷文档**

`docs/bugfixes/2026-08-28-session-database-resolution.md` 必须包含以下完整章节：

```markdown
# Session 数据库与默认库解析缺陷

## 问题现象
## 用户场景与期望结果
## 根因链路
## 数据库优先级与 Session 状态转换
## 实现方案
## 失败与恢复语义
## 测试索引
## 验证结果
## 范围边界
```

“测试索引”逐项写出 `HiveSessionDatabaseResolutionTest`、
`HivePhysicalNameResolverTest`、`HiveJoinLineageTest`、`HiveViewAndTemporaryLineageTest` 的实际测试方法名；
“验证结果”只记录本次新运行的 Maven 命令和实际通过数。

- [x] **Step 4: 运行问题 1 的全部定向测试**

Run:

```bash
mvn -q -Dtest=HivePhysicalNameResolverTest,HiveSessionDatabaseResolutionTest,HiveColumnLineageTest,HiveJoinLineageTest,HiveQualifiedJoinColumnTest,HiveViewAndTemporaryLineageTest,HiveScopedCteProjectionWarningTest,HiveScopedViewOwnerResolutionTest,HiveNestedSelectPseudoColumnRegressionTest test
```

Expected: 所选测试 0 failures, 0 errors。

- [x] **Step 5: 运行完整验证并回填真实结果**

Run: `mvn clean test`

Expected: 全部测试 0 failures, 0 errors；已有的 5 个环境跳过测试可保持 skipped。

将 Maven 输出中的实际 `Tests run`、`Failures`、`Errors`、`Skipped` 回填到缺陷文档，
不复用历史次数。

- [x] **Step 6: 检查文档和代码差异**

Run:

```bash
git diff --check
rg -n '[T]BD|[T]ODO|[F]IXME|[待]定|[暂]定' docs/bugfixes/2026-08-28-session-database-resolution.md docs/superpowers/plans/2026-08-28-session-database-resolution.md
```

Expected: 两条命令均无输出。

- [x] **Step 7: 提交回归期望与缺陷文档**

```bash
git add src/test/java docs/bugfixes/2026-08-28-session-database-resolution.md docs/superpowers/plans/2026-08-28-session-database-resolution.md
git commit -m "docs: record session database resolution fix"
```

- [x] **Step 8: 提交后再做一次最终验证**

Run:

```bash
git status --short
mvn clean test
```

Expected: `git status --short` 只显示用户原有的 `release/` 和 `补充案例/`；Maven 仍为 0 failures, 0 errors。
