# Qualified Column And Star Lineage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通用支持别名、表名、库表名限定的字段与星号投影，并让投影血缘和 Join 血缘正确解析三段式物理字段。

**Architecture:** 新增包内限定字段引用值对象，统一从最右侧拆分 owner 与字段/星号。目标字段解析器按来源别名和输入对象解析 `owner.*` 并用元数据展开；投影与 Join 解析复用相同拆分规则，保留物理表名和原始表达式。

**Tech Stack:** Java 8、Maven、JUnit 4、Hive Parser 4.0.1。

---

### Task 1: 建立限定字段引用模型

**Files:**
- Create: `src/main/java/com/panfan/hivelineage/core/QualifiedColumnReference.java`
- Create: `src/test/java/com/panfan/hivelineage/core/QualifiedColumnReferenceTest.java`

- [ ] **Step 1: 写限定引用解析失败测试**

```java
package com.panfan.hivelineage.core;

import org.junit.Assert;
import org.junit.Test;

public class QualifiedColumnReferenceTest {
    @Test
    public void parsesSupportedColumnAndStarFormsFromRightToLeft() {
        assertReference("field1", null, "field1", false);
        assertReference("a.field1", "a", "field1", false);
        assertReference("whc.a.field1", "whc.a", "field1", false);
        assertReference("a.*", "a", "*", true);
        assertReference("whc.a.*", "whc.a", "*", true);
        Assert.assertNull(QualifiedColumnReference.parse("catalog.whc.a.field1"));
        Assert.assertNull(QualifiedColumnReference.parse("sum(a.id)"));
    }

    private void assertReference(String text, String owner, String column, boolean star) {
        QualifiedColumnReference reference = QualifiedColumnReference.parse(text);
        Assert.assertNotNull(reference);
        Assert.assertEquals(owner, reference.getOwner());
        Assert.assertEquals(column, reference.getColumn());
        Assert.assertEquals(star, reference.isStar());
    }
}
```

- [ ] **Step 2: 运行测试确认 RED**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
  -Dtest=com.panfan.hivelineage.core.QualifiedColumnReferenceTest test
```

Expected: test compilation fails because `QualifiedColumnReference` does not exist.

- [ ] **Step 3: 实现最小限定引用模型**

```java
package com.panfan.hivelineage.core;

import java.util.Locale;

final class QualifiedColumnReference {
    private static final String IDENTIFIER = "[a-zA-Z_][\\w]*";
    private static final String COLUMN_PATTERN = IDENTIFIER + "(?:\\." + IDENTIFIER + "){0,2}";
    private static final String STAR_PATTERN = IDENTIFIER + "(?:\\." + IDENTIFIER + "){0,1}\\.\\*";
    private final String owner;
    private final String column;

    private QualifiedColumnReference(String owner, String column) {
        this.owner = owner;
        this.column = column;
    }

    static QualifiedColumnReference parse(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (!(normalized.matches(COLUMN_PATTERN) || normalized.matches(STAR_PATTERN) || "*".equals(normalized))) {
            return null;
        }
        int dotIndex = normalized.lastIndexOf('.');
        return dotIndex < 0
            ? new QualifiedColumnReference(null, normalized)
            : new QualifiedColumnReference(normalized.substring(0, dotIndex), normalized.substring(dotIndex + 1));
    }

    String getOwner() {
        return owner;
    }

    String getColumn() {
        return column;
    }

    boolean isStar() {
        return "*".equals(column);
    }
}
```

- [ ] **Step 4: 运行测试确认 GREEN 并提交**

Run: Task 1 Step 2 的命令。Expected: 1 test, 0 failures, 0 errors.

```bash
git add src/main/java/com/panfan/hivelineage/core/QualifiedColumnReference.java \
  src/test/java/com/panfan/hivelineage/core/QualifiedColumnReferenceTest.java
git commit -m "refactor: add qualified column reference model"
```

### Task 2: 展开混合投影中的限定星号

**Files:**
- Modify: `src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveTargetColumnResolver.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java`

- [ ] **Step 1: 写当前补充案例的失败测试**

```java
@Test
public void qualifiedStarInMixedProjectionExpandsFromMetadata() {
    HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
    TestMetadataProvider metadataProvider = new TestMetadataProvider()
        .addTable("whc.a", "id", "field1")
        .addTable("whc.b", "id", "name", "value")
        .addTable("whc.c", "field1", "id", "name", "value");
    LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
        .workflowId("wf_sql_01").taskId("sql_01").taskVersion("V1")
        .defaultDatabase("whc")
        .metadataProvider(metadataProvider).metadataCache(new HiveMetadataCache())
        .rawScript(
            "insert into whc.c select whc.a.field1, whc.b.* "
                + "from whc.a left join whc.b on whc.a.id = whc.b.id;"
        )
        .options(LineageAnalyzeOptions.builder()
            .parseMode(LineageParseMode.TABLE_AND_COLUMN).parseJoinConditions(true).build())
        .build();

    List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();
    TestSupport.assertEquals(4, columns.size(), "qualified mixed star column count");
    assertDirectColumn(columns.get(0), "whc.c.field1", "whc.a.field1");
    assertDirectColumn(columns.get(1), "whc.c.id", "whc.b.id");
    assertDirectColumn(columns.get(2), "whc.c.name", "whc.b.name");
    assertDirectColumn(columns.get(3), "whc.c.value", "whc.b.value");
}
```

同时在测试类增加完整辅助方法：

```java
private void assertDirectColumn(ColumnLineage lineage, String target, String source) {
    TestSupport.assertEquals(target, lineage.getTargetColumn().getQualifiedName(), "direct target column");
    TestSupport.assertEquals(1, lineage.getSourceColumns().size(), "direct source count");
    TestSupport.assertEquals(source, lineage.getSourceColumns().get(0).getQualifiedName(), "direct source column");
    TestSupport.assertEquals(LineageType.DIRECT, lineage.getLineageType(), "direct lineage type");
}
```

- [ ] **Step 2: 运行测试确认 RED**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
  -Dtest=HiveColumnLineageTest#qualifiedStarInMixedProjectionExpandsFromMetadata test
```

Expected: FAIL，实际只有 2 条字段血缘。

- [ ] **Step 3: 扩展星号展开入口**

为 `HiveTargetColumnResolver.expandStarSelectItems` 增加来源别名参数：

```java
List<String> expandStarSelectItems(
    ColumnResolutionContext context,
    String targetTable,
    List<String> selectItems,
    List<DataObjectRef> inputObjects,
    Map<String, String> sourceAliases
)
```

逐项调用 `QualifiedColumnReference.parse(HiveSqlTextUtils.stripAlias(item))`。普通项原样加入；裸 `*` 保留现有单表逻辑；
`owner.*` 按完整物理名、显式别名、唯一简单表名的顺序解析输入表，加载元数据后生成
`physicalTable.column`。未知或歧义 owner 保留原项并添加 `UNKNOWN_COLUMN_LINEAGE` warning。

```java
List<String> expanded = new ArrayList<>();
for (String selectItem : selectItems) {
    QualifiedColumnReference reference = QualifiedColumnReference.parse(HiveSqlTextUtils.stripAlias(selectItem));
    if (reference == null || !reference.isStar()) {
        expanded.add(selectItem);
    } else {
        expanded.addAll(expandStarReference(context, targetTable, reference, selectItems, inputObjects, sourceAliases));
    }
}
return expanded;
```

- [ ] **Step 4: 在分析器中提前构建并复用来源别名**

在星号展开前构建：

```java
Map<String, String> sourceAliases = targetColumnResolver.extractSourceAliases(normalizedStatement);
sourceAliases.putAll(resolvedParsedStatement.getSourceAliases());
Map<String, String> topLevelSourceAliases = targetColumnResolver.extractTopLevelSourceAliases(normalizedStatement);
targetColumnResolver.mergeTopLevelSourceAliases(sourceAliases, topLevelSourceAliases);
```

传给星号展开，并在后续字段解析中复用，删除重复构建。

- [ ] **Step 5: 运行案例测试**

Run: Task 2 Step 2 命令。Expected: 星号展开为 4 项；若仍因三段式字段来源失败，进入 Task 3。

### Task 3: 正确解析投影中的三段式字段

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveProjectionLineageResolver.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/DirectColumn.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java`

- [ ] **Step 1: 增加无星号的三段式字段失败测试**

```java
@Test
public void databaseTableColumnResolvesAsOneDirectSource() {
    TestMetadataProvider metadataProvider = new TestMetadataProvider()
        .addTable("whc.a", "id", "field1").addTable("whc.c", "field1");
    LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
        .workflowId("wf_qualified").taskId("three_part_column").taskVersion("V1")
        .defaultDatabase("whc")
        .metadataProvider(metadataProvider).metadataCache(new HiveMetadataCache())
        .rawScript("insert into whc.c select whc.a.field1 from whc.a;")
        .options(LineageAnalyzeOptions.builder().parseMode(LineageParseMode.COLUMN_ONLY).build())
        .build();
    ColumnLineage column = new HiveScriptLineageAnalyzer().analyzeScript(request)
        .getStatements().get(0).getColumnLineages().get(0);
    assertDirectColumn(column, "whc.c.field1", "whc.a.field1");
}
```

- [ ] **Step 2: 运行测试确认 RED**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
  -Dtest=HiveColumnLineageTest#databaseTableColumnResolvesAsOneDirectSource test
```

Expected: FAIL，产生额外来源或 `DERIVED`。

- [ ] **Step 3: 从最右侧拆分直接字段**

`isDirectExpression` 改为调用引用模型并排除星号：

```java
boolean isDirectExpression(String expression) {
    QualifiedColumnReference reference = QualifiedColumnReference.parse(expression);
    return reference != null && !reference.isStar();
}
```

`parseDirectColumn` 使用引用模型的 owner 和 column，或把 `DirectColumn` 构造逻辑改为从最后一个点拆分。

- [ ] **Step 4: 复杂表达式优先捕获完整三段式 token**

```java
private static final Pattern COLUMN_REFERENCE = Pattern.compile(
    "\\b([a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*){0,2})\\b"
);
```

`extractSourceColumns` 对完整 token 调用引用模型；owner 通过 `sourceAliases` 映射到物理表。保留关键字、函数、字符串、
列别名和无限定字段元数据归属逻辑。

- [ ] **Step 5: 增加别名和简单表名星号用例**

分别运行：

```sql
insert into whc.c select x.field1, y.* from whc.a x left join whc.b y on x.id = y.id;
insert into whc.c select a.field1, b.* from whc.a left join whc.b on a.id = b.id;
```

两个测试都断言与 Task 2 相同的四条 DIRECT 血缘。

- [ ] **Step 6: 运行字段血缘测试并提交**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn -Dtest=HiveColumnLineageTest test
git add src/main/java/com/panfan/hivelineage/core/HiveTargetColumnResolver.java \
  src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java \
  src/main/java/com/panfan/hivelineage/core/HiveProjectionLineageResolver.java \
  src/main/java/com/panfan/hivelineage/core/DirectColumn.java \
  src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java
git commit -m "fix: expand qualified stars in column lineage"
```

Expected: HiveColumnLineageTest 0 failures, 0 errors.

### Task 4: 正确解析 Join 中的三段式字段

**Files:**
- Modify: `src/test/java/com/panfan/hivelineage/HiveJoinLineageTest.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveAstJoinExtractor.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveJoinConditionAnalyzer.java`

- [ ] **Step 1: 写三段式 Join 失败测试**

```java
@Test
public void databaseTableJoinColumnsResolveToPhysicalTables() {
    LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
        .workflowId("wf_join").taskId("qualified_join").taskVersion("V1")
        .defaultDatabase("whc")
        .rawScript(
            "select whc.a.field1 from whc.a left join whc.b "
                + "on whc.a.id = whc.b.id;"
        )
        .options(LineageAnalyzeOptions.builder().parseJoinConditions(true).build()).build();
    JoinCondition condition = new HiveScriptLineageAnalyzer().analyzeScript(request)
        .getStatements().get(0).getJoinConditions().get(0);
    assertContainsColumn(condition.getDirectJoinColumns(), "whc.a.id");
    assertContainsColumn(condition.getDirectJoinColumns(), "whc.b.id");
    assertContainsSource(condition.getJoinTables(), "whc.a");
    assertContainsSource(condition.getJoinTables(), "whc.b");
    assertContainsColumn(condition.getJoinColumns(), "whc.a.id");
    assertContainsColumn(condition.getJoinColumns(), "whc.b.id");
}
```

- [ ] **Step 2: 运行测试确认 RED**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
  -Dtest=HiveJoinLineageTest#databaseTableJoinColumnsResolveToPhysicalTables test
```

Expected: FAIL，Join table/column 中出现 `whc` 或 `whc.id`。

- [ ] **Step 3: 修正 AST Join owner 拆分**

`HiveAstJoinExtractor.directObjectsForColumns` 和 `isImplicitJoinExpression` 使用：

```java
String owner = HiveSqlTextUtils.tableNameOfColumn(directColumn);
```

不再用第一个点拆分，确保 `whc.a.id` 的 owner 为 `whc.a`。

- [ ] **Step 4: 修正正则兜底 Join 扫描**

```java
private static final Pattern QUALIFIED_COLUMN = Pattern.compile(
    "\\b([a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*){1,2})\\b"
);
```

`extractDirectJoinColumns` 直接使用完整 group，后续使用现有 `tableNameOfColumn` / `columnNameOf`。

- [ ] **Step 5: 运行 Join 测试并提交**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn -Dtest=HiveJoinLineageTest test
git add src/main/java/com/panfan/hivelineage/core/HiveAstJoinExtractor.java \
  src/main/java/com/panfan/hivelineage/core/HiveJoinConditionAnalyzer.java \
  src/test/java/com/panfan/hivelineage/HiveJoinLineageTest.java
git commit -m "fix: resolve database-qualified join columns"
```

Expected: HiveJoinLineageTest 0 failures, 0 errors.

### Task 5: 验证未知 owner 与完整案例

**Files:**
- Modify: `src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java`
- Generate only: `target/lineage-output/sql_01-lineage.md`

- [ ] **Step 1: 写未知限定星号不绑定默认表的测试**

```java
@Test
public void unknownQualifiedStarDoesNotBindToDefaultInput() {
    TestMetadataProvider metadataProvider = new TestMetadataProvider()
        .addTable("whc.a", "id", "field1").addTable("whc.c", "field1");
    LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
        .workflowId("wf_qualified").taskId("unknown_star").taskVersion("V1")
        .defaultDatabase("whc")
        .metadataProvider(metadataProvider).metadataCache(new HiveMetadataCache())
        .rawScript("insert into whc.c select missing.* from whc.a;")
        .options(LineageAnalyzeOptions.builder().parseMode(LineageParseMode.COLUMN_ONLY).build()).build();
    StatementLineageResult statement = new HiveScriptLineageAnalyzer().analyzeScript(request)
        .getStatements().get(0);
    assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
    TestSupport.assertTrue(
        statement.getColumnLineages().isEmpty()
            || statement.getColumnLineages().get(0).getSourceColumns().isEmpty(),
        "unknown star must not bind to whc.a"
    );
}
```

- [ ] **Step 2: 运行测试确认未知 owner 行为**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
  -Dtest=HiveColumnLineageTest#unknownQualifiedStarDoesNotBindToDefaultInput test
```

Expected: PASS；若失败，只调整 warning/空来源处理，不把 unknown owner 绑定默认首表。

- [ ] **Step 3: 运行当前案例并生成 Markdown**

用 DDL 初始化内存元数据，运行已确认的 `sql_01.txt` DML，输出到
`target/lineage-output/sql_01-lineage.md`。确认四条 DIRECT 字段血缘、正确表血缘和两个正确 Join 字段。

- [ ] **Step 4: 提交错误处理测试**

```bash
git add src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java
git commit -m "test: cover unresolved qualified star lineage"
```

### Task 6: 完整验证与评审

**Files:**
- Verify: all modified source and test files

- [ ] **Step 1: 运行完整测试**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn test
```

Expected: 0 failures, 0 errors；外部数据库测试可按既有条件 skipped。

- [ ] **Step 2: 检查源码规模与变更边界**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn -Dtest=JavaSourceSizeTest test
git diff --check
git status --short
```

Expected: JavaSourceSizeTest PASS；无空白错误；`release/`、`补充案例/` 不在提交中。

- [ ] **Step 3: 独立代码评审**

重点核对 owner 最右侧拆分、混合星号顺序、未知/歧义 owner、投影与 Join 物理名一致性、Java 8 兼容性和原有
单表 `SELECT *` 行为。修复所有 Critical/Important 问题后重新执行 Task 6 Step 1 和 Step 2。
