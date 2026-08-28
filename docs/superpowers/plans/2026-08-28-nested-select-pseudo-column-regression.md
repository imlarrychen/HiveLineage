# Nested SELECT Pseudo-Column Regression Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permanently lock the corrected field, table, and join lineage behavior for the reported nested-query `INSERT INTO` SQL without changing production code.

**Architecture:** Add one focused end-to-end characterization test that exercises the public analyzer with real metadata and the complete reported SQL. Record that issue 3's AST target fix removed the nested `select` pseudo-column, then verify related and full suites to prove no additional production change is required.

**Tech Stack:** Java 8, Apache Hive Parser 4.0.1, JUnit 4, Maven Surefire

---

## File map

- Create `src/test/java/com/panfan/hivelineage/HiveNestedSelectPseudoColumnRegressionTest.java`: exact end-to-end regression for issue 4.
- Create `docs/bugfixes/2026-08-28-nested-select-pseudo-column-regression.md`: symptom, root-cause chain, relationship to issue 3, lineage semantics, and verification evidence.
- Do not modify any file under `src/main/java`.

### Task 1: Add the exact end-to-end characterization test

**Files:**
- Create: `src/test/java/com/panfan/hivelineage/HiveNestedSelectPseudoColumnRegressionTest.java`

- [x] **Step 1: Create the regression test class**

Create the file with this complete content:

```java
package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.LineageWarning;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.WarningCode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class HiveNestedSelectPseudoColumnRegressionTest {
    @Test
    public void nestedSelectDoesNotBecomeSourceColumn() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("whc.a", "id", "field1")
            .addTable("whc.b", "id", "name", "value")
            .addTable("whc.c", "field1", "id", "name", "value")
            .addTable("whc.d", "field1", "id", "name", "value");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_nested_select_regression")
            .taskId("task_nested_select_regression")
            .taskVersion("V1")
            .defaultDatabase("whc")
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert into whc.c(field1) "
                    + "select t.field1 "
                    + "from (select a.field1 from whc.a "
                    + "left join whc.b on a.id = b.id) as t "
                    + "left join whc.d on t.field1 = d.field1"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .parseJoinConditions(true)
                .build())
            .build();

        StatementLineageResult statement = new HiveScriptLineageAnalyzer()
            .analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "nested select parse status");
        TestSupport.assertEquals(1, statement.getColumnLineages().size(), "nested select lineage count");
        assertDirectFieldLineage(statement.getColumnLineages().get(0));
        TestSupport.assertEquals(
            new HashSet<>(Arrays.asList("whc.a", "whc.b", "whc.d")),
            sourceTableNames(statement),
            "nested select table inputs"
        );
        TestSupport.assertEquals(
            new HashSet<>(Arrays.asList(
                "whc.a.field1 = whc.d.field1",
                "whc.a.id = whc.b.id"
            )),
            resolvedJoinPairs(statement.getJoinConditions()),
            "nested select physical join semantics"
        );
        assertNoUnknownColumnWarning(statement.getWarnings());
    }

    private void assertDirectFieldLineage(ColumnLineage lineage) {
        TestSupport.assertEquals("whc.c.field1", lineage.getTargetColumn().getQualifiedName(), "field target");
        TestSupport.assertEquals(1, lineage.getSourceColumns().size(), "field source count");
        TestSupport.assertEquals(
            "whc.a.field1",
            lineage.getSourceColumns().get(0).getQualifiedName(),
            "field source"
        );
        TestSupport.assertEquals(LineageType.DIRECT, lineage.getLineageType(), "field lineage type");
    }

    private Set<String> sourceTableNames(StatementLineageResult statement) {
        TestSupport.assertEquals(1, statement.getTableLineages().size(), "table lineage count");
        Set<String> names = new HashSet<>();
        for (DataObjectRef source : statement.getTableLineages().get(0).getSourceObjects()) {
            names.add(source.getQualifiedName());
        }
        return names;
    }

    private Set<String> resolvedJoinPairs(List<JoinCondition> conditions) {
        Set<String> pairs = new HashSet<>();
        for (JoinCondition condition : conditions) {
            TestSupport.assertEquals(2, condition.getJoinColumns().size(), "resolved join column count");
            List<String> columns = new ArrayList<>();
            columns.add(condition.getJoinColumns().get(0).getQualifiedName());
            columns.add(condition.getJoinColumns().get(1).getQualifiedName());
            Collections.sort(columns);
            pairs.add(columns.get(0) + " = " + columns.get(1));
        }
        return pairs;
    }

    private void assertNoUnknownColumnWarning(List<LineageWarning> warnings) {
        for (LineageWarning warning : warnings) {
            TestSupport.assertTrue(
                warning.getWarningCode() != WarningCode.UNKNOWN_COLUMN_LINEAGE,
                "nested select should not emit unknown column warning"
            );
        }
    }
}
```

- [x] **Step 2: Run the characterization test**

Run:

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
  -Dtest=HiveNestedSelectPseudoColumnRegressionTest test
```

Expected: 1 test, 0 failures, 0 errors. Immediate success is required because issue 3 already corrected the production path; a failure means the current-state diagnosis or test assumptions are wrong and must be investigated before any production edit.

- [x] **Step 3: Check scope and commit the test**

Run:

```bash
git diff --check
git status --short
```

Expected: only `HiveNestedSelectPseudoColumnRegressionTest.java` is new in the isolated worktree.

Commit:

```bash
git add src/test/java/com/panfan/hivelineage/HiveNestedSelectPseudoColumnRegressionTest.java
git commit -m "test: lock nested select lineage regression"
```

已完成提交 `52d9efb`；随后以质量修复提交 `de7dfdd` 补强未解析嵌套 Join 条件断言。

### Task 2: Record and verify issue 4

**Files:**
- Create: `docs/bugfixes/2026-08-28-nested-select-pseudo-column-regression.md`

- [x] **Step 1: Run related regressions**

Run:

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
  -Dtest=HiveNestedSelectPseudoColumnRegressionTest,HiveColumnLineageTest,HiveJoinLineageTest,HiveParserAstAdapterTest,HiveInsertIntoDistinctLineageTest test
```

Expected: 53 tests, 0 failures, 0 errors.

- [x] **Step 2: Run the full suite**

Run:

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn clean test
```

Expected: 141 tests, 0 failures, 0 errors, 5 skipped, `BUILD SUCCESS`.

- [x] **Step 3: Create the bugfix record**

Create `docs/bugfixes/2026-08-28-nested-select-pseudo-column-regression.md` with this content:

```markdown
# 嵌套 SELECT 伪字段血缘回归记录

- 日期：2026-08-28
- 状态：已修复并完成独立回归保护
- 生产修复来源：问题 3 的 `TOK_INSERT_INTO` AST 目标解析修复

## 问题现象

用户提供的派生表 Join SQL 曾错误生成
`whc.a.select -> whc.c.field1`。

## 根因

修复前 `INSERT INTO` 目标未从 `TOK_INSERT_INTO` 解析，语句进入正则回退。
回退正则无法把 `from (` 识别为外层来源，继续匹配内层 `from whc.a`，从而把
`t.field1 from (select a.field1` 当成投影，后续将 `select` 误识别为字段。

## 修复结果

问题 3 已使该 SQL 进入完整 AST 路径。本问题不再修改生产代码，并新增原始 SQL
端到端回归测试。

- 字段血缘仅为 `whc.a.field1 -> whc.c.field1`，类型 `DIRECT`。
- 表级输入为 `whc.a`、`whc.b`、`whc.d`。
- Join 条件按物理字段去重后的语义为
  `whc.a.id = whc.b.id` 和 `whc.a.field1 = whc.d.field1`。
- 不存在 `select` 伪来源字段或未知字段血缘告警。

## 测试沉淀

- 独立测试：`HiveNestedSelectPseudoColumnRegressionTest`。
- 专项测试：1 test，0 failures，0 errors。
- 相关测试：53 tests，0 failures，0 errors。
- 全量测试：141 tests，0 failures，0 errors，5 skipped。
- 5 个跳过项为需要外部 MySQL JDBC 参数的条件集成测试。

## 范围

本记录只覆盖问题 4。问题 1 的默认库补全仍暂缓，等待用户补充场景后单独处理。
```

- [x] **Step 4: Check and commit the record**

Run:

```bash
git diff --check
git status --short
```

Expected: only the bugfix record is uncommitted; no production file is modified.

Commit:

```bash
git add docs/bugfixes/2026-08-28-nested-select-pseudo-column-regression.md
git commit -m "docs: record nested select lineage regression"
```

- [x] **Step 5: Verify final branch scope**

Run:

```bash
git diff --stat master...HEAD
git diff --check master...HEAD
git status --short
```

Expected: the feature branch contains one test file and one bugfix document beyond the already committed design and plan; no production Java file is changed, and the worktree is clean.
