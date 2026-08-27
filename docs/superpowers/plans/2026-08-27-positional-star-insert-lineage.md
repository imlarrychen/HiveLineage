# Positional Star Insert Lineage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让单来源 `INSERT ... SELECT *` 按来源列和目标列的位置生成字段血缘，并在有效字段数量不一致时返回 `COLUMN_COUNT_MISMATCH`。

**Architecture:** 将单来源裸星号展开限定为“按来源元数据顺序生成来源表达式”，再由独立的星号目标字段解析入口读取完整目标元数据。分析器按索引对齐两组结果并复用现有列数校验，不再计算同名字段交集。

**Tech Stack:** Java 8、Maven、JUnit 4、Hive Parser 4.0.1。

---

### Task 1: 用回归测试锁定位置映射和列数校验

**Files:**
- Create: `src/test/java/com/panfan/hivelineage/HivePositionalStarInsertLineageTest.java`
- Modify: `src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java:650-684`

- [ ] **Step 1: 新增异名同位置字段的失败测试**

创建 `HivePositionalStarInsertLineageTest`，包含公共请求构造方法和以下测试：

```java
package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.StatementLineageResult;
import java.util.List;
import org.junit.Test;

public class HivePositionalStarInsertLineageTest {
    @Test
    public void singleSourceStarMapsDifferentColumnNamesByPosition() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("whc.whc_hive_01", "id", "name", "age")
            .addTable("whc.whc_hive_02", "id", "name", "sex");

        StatementLineageResult statement = analyze(
            metadata,
            "insert overwrite table whc.whc_hive_01 select * from whc.whc_hive_02"
        );

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "positional star parse status");
        TestSupport.assertEquals(3, statement.getColumnLineages().size(), "positional star lineage count");
        assertDirect(statement.getColumnLineages().get(0), "whc.whc_hive_01.id", "whc.whc_hive_02.id");
        assertDirect(statement.getColumnLineages().get(1), "whc.whc_hive_01.name", "whc.whc_hive_02.name");
        assertDirect(statement.getColumnLineages().get(2), "whc.whc_hive_01.age", "whc.whc_hive_02.sex");
    }

    private StatementLineageResult analyze(TestMetadataProvider metadata, String sql) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_positional_star")
            .taskId("task_positional_star")
            .taskVersion("V1")
            .defaultDatabase("whc")
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .rawScript(sql)
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();
        return new HiveScriptLineageAnalyzer().analyzeScript(request).getStatements().get(0);
    }

    private void assertDirect(ColumnLineage lineage, String target, String source) {
        TestSupport.assertEquals(target, lineage.getTargetColumn().getQualifiedName(), "positional target");
        TestSupport.assertEquals(1, lineage.getSourceColumns().size(), "positional source count");
        TestSupport.assertEquals(source, lineage.getSourceColumns().get(0).getQualifiedName(), "positional source");
        TestSupport.assertEquals(LineageType.DIRECT, lineage.getLineageType(), "positional lineage type");
    }
}
```

- [ ] **Step 2: 运行异名位置测试确认 RED**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
  -Dtest=HivePositionalStarInsertLineageTest#singleSourceStarMapsDifferentColumnNamesByPosition test
```

Expected: FAIL；当前只生成 2 条血缘，缺少 `whc.whc_hive_02.sex -> whc.whc_hive_01.age`。

- [ ] **Step 3: 将旧“来源多列取交集”测试改为列数不一致失败测试**

把 `HiveColumnLineageTest.selectStarAlignsToTargetMetadataWhenSourceHasExtraColumns` 重命名为 `selectStarFailsWhenSourceHasExtraColumns`，保留原有元数据和 SQL，将断言替换为：

```java
StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

TestSupport.assertEquals(2, statement.getParseStatusCode(), "source extra star parse status");
TestSupport.assertEquals("COLUMN_COUNT_MISMATCH", statement.getFailureCode(), "source extra star failure code");
TestSupport.assertEquals(0, statement.getColumnLineages().size(), "source extra star lineage count");
```

- [ ] **Step 4: 新增目标多列的失败测试**

在 `HivePositionalStarInsertLineageTest` 中新增：

```java
@Test
public void singleSourceStarFailsWhenTargetHasExtraColumns() {
    TestMetadataProvider metadata = new TestMetadataProvider()
        .addTable("whc.target_more", "id", "name", "age", "extra_col")
        .addTable("whc.source_less", "id", "name", "sex");

    StatementLineageResult statement = analyze(
        metadata,
        "insert overwrite table whc.target_more select * from whc.source_less"
    );

    TestSupport.assertEquals(2, statement.getParseStatusCode(), "target extra star parse status");
    TestSupport.assertEquals("COLUMN_COUNT_MISMATCH", statement.getFailureCode(), "target extra star failure code");
    TestSupport.assertEquals(0, statement.getColumnLineages().size(), "target extra star lineage count");
}
```

- [ ] **Step 5: 运行三项问题测试确认 RED**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
  -Dtest=HivePositionalStarInsertLineageTest,HiveColumnLineageTest#selectStarFailsWhenSourceHasExtraColumns test
```

Expected: 3 个测试均 FAIL，分别表现为遗漏异名位置血缘、来源多列时仍成功、目标多列时仍成功。现有 `HiveColumnLineageTest#selectStarExpandsColumnsFromMetadata` 已覆盖同名同序正常映射，无需增加重复测试。

### Task 2: 分离来源星号展开和目标字段解析

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveTargetColumnResolver.java:117-210`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java:313-335`

- [ ] **Step 1: 让单来源星号只按来源元数据展开**

将 `expandSingleInputStar` 改为不接收或读取目标表元数据，只加载来源元数据并返回带物理表名的来源表达式：

```java
private List<String> expandSingleInputStar(
    ColumnResolutionContext context,
    List<String> selectItems,
    DataObjectRef inputObject
) {
    LineageAnalyzeRequest request = context.getRequest();
    HiveTableMetadata sourceMetadata = loadTableMetadata(
        request,
        inputObject.getQualifiedName(),
        context.getWarnings(),
        context.getStatementIndex()
    );
    if (sourceMetadata == null || sourceMetadata.getColumns().isEmpty()) {
        return selectItems;
    }
    List<String> expanded = new ArrayList<>();
    for (String column : sourceMetadata.getColumns()) {
        expanded.add(inputObject.getQualifiedName() + "." + column);
    }
    return expanded;
}
```

同步调整调用点，删除单来源路径中的目标元数据加载和 `intersectionInTargetOrder` 调用。

- [ ] **Step 2: 增加星号目标字段专用解析入口**

在 `HiveTargetColumnResolver` 增加：

```java
List<String> resolveStarTargetColumns(
    LineageAnalyzeRequest request,
    String targetTable,
    List<String> expandedSourceItems,
    LineageParseMode parseMode,
    List<LineageWarning> warnings,
    int statementIndex
) {
    if (parseMode == LineageParseMode.COLUMN_ONLY || parseMode == LineageParseMode.TABLE_AND_COLUMN) {
        HiveTableMetadata metadata = loadTableMetadata(request, targetTable, warnings, statementIndex);
        if (metadata != null && !metadata.getColumns().isEmpty()) {
            return new ArrayList<>(metadata.getColumns());
        }
    }
    List<String> inferred = new ArrayList<>();
    for (String sourceItem : expandedSourceItems) {
        QualifiedColumnReference reference = QualifiedColumnReference.parse(HiveSqlTextUtils.stripAlias(sourceItem));
        inferred.add(reference == null || reference.isStar()
            ? HiveSqlTextUtils.stripAlias(sourceItem)
            : reference.getColumn());
    }
    return inferred;
}
```

该入口只供单来源裸星号、且 SQL 未显式声明目标字段列表时使用。目标元数据存在时返回完整目标字段列表，从而让现有数量校验检测多列或少列。

- [ ] **Step 3: 在分析器中独立解析星号目标字段**

将 `HiveScriptLineageAnalyzer` 的目标字段推断分支改为：

```java
List<String> inferredTargetColumns = targetColumns;
if (targetColumns.isEmpty()) {
    inferredTargetColumns = starSelect
        ? targetColumnResolver.resolveStarTargetColumns(
            request,
            targetTable,
            expandedSelectItems,
            parseMode,
            statementWarnings,
            index
        )
        : targetColumnResolver.resolveTargetColumns(
            request,
            targetTable,
            expandedSelectItems,
            parseMode,
            statementWarnings,
            index
        );
}
```

保留 `alignTargetColumnsForStaticPartitions` 和已有 `resolvedTargetColumns.size() != expandedSelectItems.size()` 校验，不新增部分映射路径。

- [ ] **Step 4: 运行问题测试确认 GREEN**

运行 Task 1 Step 5 的命令。

Expected: 3 个测试全部通过；异名字段按位置生成 3 条 `DIRECT` 血缘，两种数量不一致均返回 `COLUMN_COUNT_MISMATCH` 且字段血缘为空。

- [ ] **Step 5: 运行星号相关回归测试**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
  -Dtest=HivePositionalStarInsertLineageTest,HiveColumnLineageTest,HiveQualifiedStarDdlScenarioTest,HiveQualifiedStarScopeTest test
```

Expected: 相关测试全部通过，限定星号和同名同序裸星号行为不回归。

### Task 3: 同步行为文档并完成验证

**Files:**
- Modify: `README.md:190-200`
- Modify: `openspec/changes/add-hive-sql-lineage-analyzer/design.md:252`
- Create: `docs/bugfixes/2026-08-27-positional-star-insert-lineage.md`

- [ ] **Step 1: 更新公开行为说明**

将 README 和 OpenSpec 中“按目标顺序取同名交集”的描述替换为：单来源 `INSERT ... SELECT *` 在源、目标元数据均可用时，分别按各自元数据顺序展开并按位置映射；有效字段数量不一致时返回 `COLUMN_COUNT_MISMATCH`，不生成部分字段血缘。

- [ ] **Step 2: 运行完整测试**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn test
```

Expected: Maven 退出码 0，0 failures，0 errors；需要外部 MySQL 参数的 JDBC 集成测试保持条件跳过。

- [ ] **Step 3: 检查格式和范围**

```bash
git diff --check
git status --short
git diff -- src/main/java/com/panfan/hivelineage/core/HiveTargetColumnResolver.java \
  src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java \
  src/test/java/com/panfan/hivelineage/HivePositionalStarInsertLineageTest.java \
  src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java \
  README.md openspec/changes/add-hive-sql-lineage-analyzer/design.md
```

Expected: 无空白错误；生产代码只修改星号来源展开和星号目标字段解析；测试只覆盖本问题；用户已有的 `release/` 和 `补充案例/` 不被修改。

- [ ] **Step 4: 写 Bug 修复记录**

创建 `docs/bugfixes/2026-08-27-positional-star-insert-lineage.md`，记录问题现象、最小复现、位置映射根因、采用方案、回归测试方法、RED 输出摘要、GREEN 输出摘要，以及 Task 3 Step 2 的实际测试数量和条件跳过数量。

- [ ] **Step 5: 提交问题 2 的完整修复**

```bash
git add README.md \
  openspec/changes/add-hive-sql-lineage-analyzer/design.md \
  docs/bugfixes/2026-08-27-positional-star-insert-lineage.md \
  src/main/java/com/panfan/hivelineage/core/HiveTargetColumnResolver.java \
  src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java \
  src/test/java/com/panfan/hivelineage/HivePositionalStarInsertLineageTest.java \
  src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java
git commit -m "fix: map single-source star inserts by position"
```
