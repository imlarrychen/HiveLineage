# INSERT INTO SELECT DISTINCT Lineage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `INSERT INTO ... SELECT DISTINCT ...` use the complete Hive AST path so `DISTINCT` never becomes a source column.

**Architecture:** Extend the INSERT target lookup in `HiveParserAstAdapter` to accept both Hive AST destination node variants, and preserve qualified-star owners when the formal AST exposes `TOK_ALLCOLREF` with a `TOK_TABNAME` child. Keep fallback parsing, partition handling, and lineage generation unchanged, then prove the complete behavior at AST and analyzer levels.

**Tech Stack:** Java 8, Apache Hive Parser 4.0.1, JUnit 4, Maven Surefire

---

## File map

- Modify `src/main/java/com/panfan/hivelineage/core/HiveParserAstAdapter.java`: resolve table targets from `TOK_INSERT_INTO` as well as `TOK_DESTINATION`.
- Modify `src/main/java/com/panfan/hivelineage/core/HiveAstExpressionExtractor.java`: preserve `a.*`/`db.table.*` owners from qualified `TOK_ALLCOLREF` nodes while retaining bare `*`.
- Modify `src/test/java/com/panfan/hivelineage/HiveParserAstAdapterTest.java`: cover AST target and projection extraction for INSERT INTO variants and preserve INSERT OVERWRITE.
- Create `src/test/java/com/panfan/hivelineage/HiveInsertIntoDistinctLineageTest.java`: cover the reported end-to-end DISTINCT lineage and static-partition scope.
- Create `docs/bugfixes/2026-08-27-insert-into-distinct-lineage.md`: record symptom, root cause, scope, tests, and final verification evidence.

### Task 1: Add failing AST and lineage regressions

**Files:**
- Modify: `src/test/java/com/panfan/hivelineage/HiveParserAstAdapterTest.java`
- Create: `src/test/java/com/panfan/hivelineage/HiveInsertIntoDistinctLineageTest.java`

- [ ] **Step 1: Add the AST-level regression tests**

Append these tests to `HiveParserAstAdapterTest`:

```java
@Test
public void hiveParserAstAdapterParsesInsertIntoDistinctTargetAndProjection() {
    HiveAstParserAdapter adapter = new HiveParserAstAdapter();

    ParsedHiveStatement statement = adapter.parse(
        "insert into whc.c(field1) select distinct field1 from whc.a"
    );

    TestSupport.assertTrue(statement.getOutputObject() != null, "insert into distinct output object");
    TestSupport.assertEquals("whc.c", statement.getOutputObject().getQualifiedName(), "insert into distinct target");
    TestSupport.assertEquals(Arrays.asList("field1"), statement.getTargetColumns(), "insert into distinct target columns");
    TestSupport.assertEquals(Arrays.asList("field1"), statement.getSelectExpressions(), "insert into distinct projections");
}

@Test
public void hiveParserAstAdapterParsesOptionalTableInsertIntoTarget() {
    HiveAstParserAdapter adapter = new HiveParserAstAdapter();

    ParsedHiveStatement statement = adapter.parse(
        "insert into table whc.c(field1) select field1 from whc.a"
    );

    TestSupport.assertTrue(statement.getOutputObject() != null, "insert into table output object");
    TestSupport.assertEquals("whc.c", statement.getOutputObject().getQualifiedName(), "insert into table target");
    TestSupport.assertEquals(Arrays.asList("field1"), statement.getSelectExpressions(), "insert into table projections");
}

@Test
public void hiveParserAstAdapterKeepsInsertOverwriteTarget() {
    HiveAstParserAdapter adapter = new HiveParserAstAdapter();

    ParsedHiveStatement statement = adapter.parse(
        "insert overwrite table whc.c select field1 from whc.a"
    );

    TestSupport.assertTrue(statement.getOutputObject() != null, "insert overwrite output object");
    TestSupport.assertEquals("whc.c", statement.getOutputObject().getQualifiedName(), "insert overwrite target");
}
```

- [ ] **Step 2: Add the end-to-end regression test class**

Create `HiveInsertIntoDistinctLineageTest.java` with the reported case and a static-partition scope case:

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
import org.junit.Test;

public class HiveInsertIntoDistinctLineageTest {
    @Test
    public void distinctModifierDoesNotBecomeSourceColumn() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("whc.a", "id", "field1")
            .addTable("whc.c", "field1", "id", "name", "value");

        StatementLineageResult statement = analyze(
            metadata,
            "insert into whc.c(field1) select distinct field1 from whc.a"
        );

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "distinct parse status");
        TestSupport.assertEquals(1, statement.getColumnLineages().size(), "distinct lineage count");
        assertDirect(
            statement.getColumnLineages().get(0),
            "whc.c.field1",
            "whc.a.field1"
        );
    }

    @Test
    public void insertIntoStaticPartitionKeepsConstantLineage() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("whc.a", "id", "field1")
            .addTable("whc.c", "field1", "dt");

        StatementLineageResult statement = analyze(
            metadata,
            "insert into whc.c partition(dt='20260827') "
                + "select distinct field1 from whc.a"
        );

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "partition parse status");
        TestSupport.assertEquals(2, statement.getColumnLineages().size(), "partition lineage count");
        assertDirect(statement.getColumnLineages().get(0), "whc.c.field1", "whc.a.field1");
        ColumnLineage partition = statement.getColumnLineages().get(1);
        TestSupport.assertEquals("whc.c.dt", partition.getTargetColumn().getQualifiedName(), "partition target");
        TestSupport.assertEquals("constant('20260827')", partition.getExpression(), "partition expression");
        TestSupport.assertEquals(LineageType.CONSTANT, partition.getLineageType(), "partition type");
        TestSupport.assertTrue(partition.getSourceColumns().isEmpty(), "partition source columns");
    }

    private StatementLineageResult analyze(TestMetadataProvider metadata, String sql) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_insert_into_distinct")
            .taskId("task_insert_into_distinct")
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
        TestSupport.assertEquals(target, lineage.getTargetColumn().getQualifiedName(), "direct target");
        TestSupport.assertEquals(1, lineage.getSourceColumns().size(), "direct source count");
        TestSupport.assertEquals(source, lineage.getSourceColumns().get(0).getQualifiedName(), "direct source");
        TestSupport.assertEquals(LineageType.DIRECT, lineage.getLineageType(), "direct lineage type");
    }
}
```

- [ ] **Step 3: Run the new tests and verify RED**

Run:

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
  -Dtest=HiveParserAstAdapterTest,HiveInsertIntoDistinctLineageTest test
```

Expected: the new `INSERT INTO` AST assertions fail because `getOutputObject()` is null, and the reported DISTINCT lineage assertion fails because the fallback path includes `whc.a.distinct`.

- [ ] **Step 4: Commit only the failing tests**

```bash
git add src/test/java/com/panfan/hivelineage/HiveParserAstAdapterTest.java \
  src/test/java/com/panfan/hivelineage/HiveInsertIntoDistinctLineageTest.java
git commit -m "test: reproduce insert into distinct lineage bug"
```

### Task 2: Resolve INSERT INTO targets from the Hive AST

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveParserAstAdapter.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveAstExpressionExtractor.java`

- [x] **Step 1: Implement the minimal target-node fallback**

Replace the `INSERT_SELECT` branch in `resolveOutputObject` with:

```java
if (statementType == StatementType.INSERT_SELECT) {
    ASTNode destinationNode = astWalker.first(mainInsert, HiveParser.TOK_DESTINATION);
    if (destinationNode == null) {
        destinationNode = astWalker.first(mainInsert, HiveParser.TOK_INSERT_INTO);
    }
    ASTNode tabNode = astWalker.first(destinationNode, HiveParser.TOK_TAB);
    tableName = astWalker.first(tabNode, HiveParser.TOK_TABNAME);
}
```

Do not change `sqlForHiveParser`, `resolveSelectExpressions`, or the regex fallback. This
production change was committed as `cab12f9` (`fix: resolve insert into targets from ast`).

- [x] **Step 2: Run the initial focused tests and verify GREEN**

Run:

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
  -Dtest=HiveParserAstAdapterTest,HiveInsertIntoDistinctLineageTest test
```

Expected: 9 tests, 0 failures, 0 errors.

After the target lookup switched the reported statement to the formal AST path, the existing
`HiveColumnLineageTest` was run as a second RED check: 31 tests, with 5 qualified-star
failures. The failures showed that `TOK_ALLCOLREF` owner information was being dropped by
the AST expression unparser.

- [x] **Step 3: Preserve qualified-star owners exposed by the AST**

Update `HiveAstExpressionExtractor.unparse` so a `TOK_ALLCOLREF` with a `TOK_TABNAME`
child reconstructs `owner.*` (including `db.table.*`); a bare `TOK_ALLCOLREF` remains `*`.
This is the minimal compatibility fix and was committed as `e7956b0` (`fix: preserve qualified
star owners from ast`).

- [x] **Step 4: Run related parser and field-lineage regressions**

Run:

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
  -Dtest=HiveParserAstAdapterTest,HiveInsertIntoDistinctLineageTest,HiveColumnLineageTest,HivePositionalStarInsertLineageTest,HivePositionalStarScopeTest test
```

Expected: 47 tests, 0 failures, 0 errors.

The focused 9-test suite and related 47-test suite both passed after the two production commits.

- [x] **Step 5: Record the production commits**

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveParserAstAdapter.java
git commit -m "fix: resolve insert into targets from ast"

git add src/main/java/com/panfan/hivelineage/core/HiveAstExpressionExtractor.java
git commit -m "fix: preserve qualified star owners from ast"
```

### Task 3: Document and fully verify the fix

**Files:**
- Create: `docs/bugfixes/2026-08-27-insert-into-distinct-lineage.md`

- [x] **Step 1: Create the bugfix record**

Create the document with these sections and verified facts:

```markdown
# INSERT INTO SELECT DISTINCT 伪字段血缘修复

- 日期：2026-08-27
- 状态：已修复

## 问题现象

`insert into whc.c(field1) select distinct field1 from whc.a` 错误地把
`distinct` 识别为来源字段。

## 根因

Hive AST 使用 `TOK_INSERT_INTO` 表示 INSERT INTO 目标，而目标解析器只读取
`TOK_DESTINATION`，导致语句退回正则路径。

## 修复方案

INSERT 目标解析同时支持 `TOK_DESTINATION` 和 `TOK_INSERT_INTO`；投影继续由
已有的 `TOK_SELECT`/`TOK_SELECTDI` 逻辑提取。

## 回归范围

- DISTINCT 只保留 `whc.a.field1 -> whc.c.field1`。
- 普通 INSERT INTO、可选 TABLE、静态分区和 INSERT OVERWRITE 均有回归覆盖。

## 验证

- 专项测试：9 tests，0 failures，0 errors。
- 相关测试：47 tests，0 failures，0 errors。
- 全量测试：140 tests，0 failures，0 errors，5 skipped。
- 5 个跳过项为需要外部 MySQL JDBC 参数的集成测试。
```

- [x] **Step 2: Run the full test suite**

Run:

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn test
```

Expected: 140 tests, 0 failures, 0 errors, 5 skipped, `BUILD SUCCESS`.

- [x] **Step 3: Check the final diff**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only the three requested documentation files remain uncommitted, while the user's existing `release/` and `补充案例/` directories remain untouched.

- [x] **Step 4: Commit the documentation records**

```bash
git add docs/superpowers/specs/2026-08-27-insert-into-distinct-lineage-design.md \
  docs/superpowers/plans/2026-08-27-insert-into-distinct-lineage.md \
  docs/bugfixes/2026-08-27-insert-into-distinct-lineage.md
git commit -m "docs: record insert into distinct lineage fix"
```
