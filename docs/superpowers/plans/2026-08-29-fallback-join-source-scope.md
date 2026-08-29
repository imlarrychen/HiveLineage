# Join 降级来源作用域实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用带查询块作用域的来源节点替换 Join-only 降级路径的全局文本扫描，消除裸表漏检和函数 `FROM` 误判。

**Architecture:** 新增独立的 `HiveFallbackJoinSourceResolver`，先将 SQL 分词为带位置的 token，再递归解析每个 `SELECT`/`WITH` 查询块。解析结果是保留每次 occurrence 的不可变来源节点，并在所属作用域中区分物理表、CTE、子查询和 Session 临时表。`HiveScriptLineageAnalyzer` 只对物理节点执行数据库校验。

**Tech Stack:** Java 8、Apache Hive Parser 4.0.1、JUnit 4.13.2、Maven Surefire。

---

## 文件结构

- Create: `src/main/java/com/panfan/hivelineage/core/FallbackJoinSource.java`
  - package-private 不可变来源节点和来源类型。
- Create: `src/main/java/com/panfan/hivelineage/core/HiveFallbackJoinSourceResolver.java`
  - tokenizer、查询块递归解析、CTE/子查询/临时表分类。
- Create: `src/test/java/com/panfan/hivelineage/core/HiveFallbackJoinSourceResolverTest.java`
  - 单元锁定 occurrence、作用域、tokenizer 和函数 `FROM` 语义。
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java`
  - Join-only 降级路径改用新 resolver，仅校验物理来源。
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveTargetColumnResolver.java`
  - 删除临时引入的 `extractAllSourceObjects` 及其专用扫描状态，保留已有 top-level/explicit alias 能力。
- Modify: `src/test/java/com/panfan/hivelineage/HiveSessionDatabaseResolutionTest.java`
  - 增加作用域同名和 `EXTRACT FROM` 端到端回归，保留已有 fallback 用例。

### Task 1: 建立不可变来源节点与 tokenizer

**Files:**
- Create: `src/main/java/com/panfan/hivelineage/core/FallbackJoinSource.java`
- Create: `src/main/java/com/panfan/hivelineage/core/HiveFallbackJoinSourceResolver.java`
- Create: `src/test/java/com/panfan/hivelineage/core/HiveFallbackJoinSourceResolverTest.java`

- [ ] **Step 1: 编写 token 与节点的失败测试**

```java
package com.panfan.hivelineage.core;

import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class HiveFallbackJoinSourceResolverTest {
    private final HiveFallbackJoinSourceResolver resolver = new HiveFallbackJoinSourceResolver();

    @Test
    public void preservesRepeatedSourceOccurrences() {
        List<FallbackJoinSource> sources = resolver.resolve(
            "select ??? from t join db1.t on 1=1",
            Collections.<String>emptySet()
        );
        assertEquals(2, sources.size());
        assertSource(sources.get(0), "t", FallbackJoinSource.Kind.PHYSICAL);
        assertSource(sources.get(1), "db1.t", FallbackJoinSource.Kind.PHYSICAL);
    }

    @Test
    public void skipsStringsCommentsAndNormalizesQuotedRelations() {
        List<FallbackJoinSource> sources = resolver.resolve(
            "select ??? /* from ghost */ from `db1`.`left_t` a "
                + "join `db2`.`right_t` b on a.note='join phantom'",
            Collections.<String>emptySet()
        );
        assertEquals(2, sources.size());
        assertEquals("db1.left_t", sources.get(0).getQualifiedName());
        assertEquals("db2.right_t", sources.get(1).getQualifiedName());
    }

    private void assertSource(
        FallbackJoinSource source,
        String name,
        FallbackJoinSource.Kind kind
    ) {
        assertEquals(name, source.getQualifiedName());
        assertEquals(kind, source.getKind());
    }
}
```

- [ ] **Step 2: 运行测试并确认类缺失**

Run:

```bash
mvn -q -Dtest=HiveFallbackJoinSourceResolverTest test
```

Expected: FAIL，编译器报告 `FallbackJoinSource` 和 `HiveFallbackJoinSourceResolver` 不存在。

- [ ] **Step 3: 实现节点和 tokenizer 骨架**

`FallbackJoinSource` 使用以下完整契约：

```java
package com.panfan.hivelineage.core;

final class FallbackJoinSource {
    enum Kind { PHYSICAL, CTE, SUBQUERY, TEMPORARY }

    private final String qualifiedName;
    private final String queryBlockPath;
    private final int startOffset;
    private final Kind kind;

    FallbackJoinSource(String qualifiedName, String queryBlockPath, int startOffset, Kind kind) {
        if (qualifiedName == null || qualifiedName.isEmpty()) {
            throw new IllegalArgumentException("qualifiedName must not be empty");
        }
        if (queryBlockPath == null || queryBlockPath.isEmpty()) {
            throw new IllegalArgumentException("queryBlockPath must not be empty");
        }
        if (startOffset < 0 || kind == null) {
            throw new IllegalArgumentException("source position and kind are required");
        }
        this.qualifiedName = qualifiedName;
        this.queryBlockPath = queryBlockPath;
        this.startOffset = startOffset;
        this.kind = kind;
    }

    String getQualifiedName() { return qualifiedName; }
    String getQueryBlockPath() { return queryBlockPath; }
    int getStartOffset() { return startOffset; }
    Kind getKind() { return kind; }
    boolean isPhysical() { return kind == Kind.PHYSICAL; }
}
```

`HiveFallbackJoinSourceResolver` 内部 token 定义为：

```java
final class HiveFallbackJoinSourceResolver {
    private enum TokenType { WORD, QUOTED_IDENTIFIER, DOT, COMMA, LPAREN, RPAREN }

    private static final class Token {
        private final TokenType type;
        private final String text;
        private final int offset;

        private Token(TokenType type, String text, int offset) {
            this.type = type;
            this.text = text;
            this.offset = offset;
        }
    }

    List<FallbackJoinSource> resolve(String sql, Set<String> temporaryTables) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null");
        }
        List<Token> tokens = tokenize(sql);
        List<FallbackJoinSource> sources = new ArrayList<>();
        parseQueryBlock(tokens, 0, tokens.size(), "q0", Collections.<String>emptySet(),
            normalizeNames(temporaryTables), sources);
        return Collections.unmodifiableList(sources);
    }
}
```

`tokenize` 逐字符处理：单/双引号字符串整体跳过；支持 `\\` 转义和 doubled quote；`--` 跳到换行；`/* */` 跳到结束；反引号内容产生 `QUOTED_IDENTIFIER`；字母、数字和下划线产生小写 `WORD`；点、逗号和括号产生对应 token。不得使用全局 `replace("`", "")`。同一步实现只处理普通 `FROM relation` / `JOIN relation` 的最小 `parseQueryBlock`，使 Step 1 的两个测试转绿；CTE、子查询、逗号和函数作用域留给 Task 2 的新失败测试驱动。

- [ ] **Step 4: 运行单元测试**

Run: `mvn -q -Dtest=HiveFallbackJoinSourceResolverTest test`

Expected: Step 1 的两个测试全部通过，0 failures，0 errors。

- [ ] **Step 5: 提交节点与 tokenizer**

```bash
git add src/main/java/com/panfan/hivelineage/core/FallbackJoinSource.java src/main/java/com/panfan/hivelineage/core/HiveFallbackJoinSourceResolver.java src/test/java/com/panfan/hivelineage/core/HiveFallbackJoinSourceResolverTest.java
git commit -m "refactor: model scoped fallback join sources"
```

### Task 2: 实现查询块来源解析与分类

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveFallbackJoinSourceResolver.java`
- Modify: `src/test/java/com/panfan/hivelineage/core/HiveFallbackJoinSourceResolverTest.java`

- [ ] **Step 1: 增加作用域、逗号和函数 `FROM` 失败测试**

```java
@Test
public void classifiesCteSubqueryAndTemporarySourcesPerScope() {
    List<FallbackJoinSource> sources = resolver.resolve(
        "with c as (select id from bare_cte_base) "
            + "select ??? from c join (select id from db2.inner_t) shadow on 1=1 "
            + "join shadow physical_shadow on 1=1 join tmp_t x on 1=1",
        Collections.singleton("tmp_t")
    );
    assertSource(findKind(sources, "bare_cte_base", FallbackJoinSource.Kind.PHYSICAL), "bare_cte_base", FallbackJoinSource.Kind.PHYSICAL);
    assertSource(findKind(sources, "c", FallbackJoinSource.Kind.CTE), "c", FallbackJoinSource.Kind.CTE);
    assertSource(findKind(sources, "shadow", FallbackJoinSource.Kind.SUBQUERY), "shadow", FallbackJoinSource.Kind.SUBQUERY);
    assertSource(findKind(sources, "shadow", FallbackJoinSource.Kind.PHYSICAL), "shadow", FallbackJoinSource.Kind.PHYSICAL);
    assertSource(findKind(sources, "tmp_t", FallbackJoinSource.Kind.TEMPORARY), "tmp_t", FallbackJoinSource.Kind.TEMPORARY);
}

@Test
public void parsesCommaSourcesWithoutTreatingExpressionCommasAsRelations() {
    List<FallbackJoinSource> sources = resolver.resolve(
        "select ??? from db1.a a, bare_b b join db2.c c "
            + "on coalesce(a.id, b.id)=c.id where concat(a.id, b.id) <> ''",
        Collections.<String>emptySet()
    );
    assertNames(sources, "db1.a", "bare_b", "db2.c");
}

@Test
public void extractFunctionFromIsNotARelation() {
    List<FallbackJoinSource> sources = resolver.resolve(
        "select ??? from db1.a a join db2.b b "
            + "on extract(day from event_ts)=b.day",
        Collections.<String>emptySet()
    );
    assertNames(sources, "db1.a", "db2.b");
}
```

`findKind` 按名称和 kind 查找，`assertNames` 按返回顺序比较所有 `qualifiedName`；找不到时直接 `fail`，不返回 null。

- [ ] **Step 2: 运行新测试并确认作用域行为失败**

Run: `mvn -q -Dtest=HiveFallbackJoinSourceResolverTest test`

Expected: FAIL，失败集中在 CTE/子查询分类、逗号来源或 `EXTRACT FROM` 误判。

- [ ] **Step 3: 实现递归查询块解析**

`parseQueryBlock` 按以下确定流程实现：

```java
private void parseQueryBlock(
    List<Token> tokens,
    int start,
    int end,
    String path,
    Set<String> inheritedCtes,
    Set<String> temporaryTables,
    List<FallbackJoinSource> output
) {
    Set<String> visibleCtes = new LinkedHashSet<>(inheritedCtes);
    int bodyStart = parseCteDeclarations(tokens, start, end, path, visibleCtes, temporaryTables, output);
    int depth = 0;
    boolean inFromClause = false;
    boolean expectRelation = false;
    for (int i = bodyStart; i < end; i++) {
        Token token = tokens.get(i);
        if (token.type == TokenType.LPAREN) {
            int close = matchingParen(tokens, i, end);
            if (containsQuery(tokens, i + 1, close)) {
                parseQueryBlock(tokens, i + 1, close, path + ".q" + i,
                    visibleCtes, temporaryTables, output);
            }
            i = close;
            continue;
        }
        if (isWord(token, "from") && depth == 0 && followsQuerySelect(tokens, bodyStart, i)) {
            inFromClause = true;
            expectRelation = true;
            continue;
        }
        if (isWord(token, "join") && inFromClause && depth == 0) {
            expectRelation = true;
            continue;
        }
        if (inFromClause && depth == 0 && isClauseBoundary(token)) {
            inFromClause = false;
            expectRelation = false;
            continue;
        }
        if (inFromClause && depth == 0 && token.type == TokenType.COMMA) {
            expectRelation = true;
            continue;
        }
        if (expectRelation) {
            RelationParse relation = parseRelation(tokens, i, end, path, visibleCtes,
                temporaryTables, output);
            output.add(relation.source);
            i = relation.lastTokenIndex;
            expectRelation = false;
        }
    }
}
```

实现时必须同时遵守以下约束：

- `followsQuerySelect` 只在同一查询块中已出现顶层 `SELECT` 时允许 `FROM`，因此 `EXTRACT` 函数括号内的 `FROM` 不会被当作当前块来源。
- `matchingParen` 用 token 括号深度定位，未闭合时抛 `IllegalArgumentException`，不猜测边界。
- `parseCteDeclarations` 仅识别块起始的 `WITH name AS (...) [, name AS (...)]`，每个 CTE body 递归解析，已宣告 CTE 对后续 CTE 和主查询可见。
- `parseRelation` 遇到 `LPAREN` 时递归解析括号内查询，读取括号后 alias，返回 `SUBQUERY` 节点；不将该 alias 加入全局 CTE 集合。
- 普通关系由 `WORD|QUOTED_IDENTIFIER (DOT WORD|QUOTED_IDENTIFIER)?` 组成，仅允许一段或两段；名称命中 `visibleCtes` 时为 `CTE`，命中 `temporaryTables` 时为 `TEMPORARY`，其余为 `PHYSICAL`。
- `isClauseBoundary` 识别 `where/group/having/order/cluster/distribute/sort/limit/union/qualify/window`。
- occurrence 按 SQL 起始位置顺序输出，不使用 Map 或 simple-name 去重。

- [ ] **Step 4: 运行 resolver 全部单元测试**

Run: `mvn -q -Dtest=HiveFallbackJoinSourceResolverTest test`

Expected: 全部通过，0 failures，0 errors。

- [ ] **Step 5: 提交查询块解析**

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveFallbackJoinSourceResolver.java src/test/java/com/panfan/hivelineage/core/HiveFallbackJoinSourceResolverTest.java
git commit -m "fix: resolve fallback sources by query scope"
```

### Task 3: 接入 Join-only 数据库校验并删除旧扫描器

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveTargetColumnResolver.java`
- Modify: `src/test/java/com/panfan/hivelineage/HiveSessionDatabaseResolutionTest.java`

- [ ] **Step 1: 增加端到端失败测试**

```java
@Test
public void fallbackJoinDistinguishesScopedAliasFromSameNamedPhysicalTable() {
    assertFallbackJoinDatabaseFailure(
        "select ??? from (select id from db1.inner_t) shadow "
            + "join shadow physical_shadow on 1=1;"
    );
}

@Test
public void fallbackJoinDoesNotTreatExtractFromAsPhysicalSource() {
    assertFallbackQualifiedJoinSucceeds(
        "select ??? from db1.a a join db2.b b "
            + "on extract(day from event_ts)=b.day;"
    );
}
```

- [ ] **Step 2: 运行新端到端测试并确认旧实现失败**

Run:

```bash
mvn -q -Dtest=HiveSessionDatabaseResolutionTest#fallbackJoinDistinguishesScopedAliasFromSameNamedPhysicalTable+fallbackJoinDoesNotTreatExtractFromAsPhysicalSource test
```

Expected: 第一个用例错误成功，第二个用例错误返回 `DATABASE_NOT_RESOLVED`。

- [ ] **Step 3: 在分析器中仅校验物理节点**

`HiveScriptLineageAnalyzer` 新增：

```java
private final HiveFallbackJoinSourceResolver fallbackJoinSourceResolver =
    new HiveFallbackJoinSourceResolver();

private void validateJoinPhysicalObjects(StatementAnalysisContext context) {
    List<FallbackJoinSource> sources = fallbackJoinSourceResolver.resolve(
        context.getNormalizedStatement(),
        context.getTemporaryTableSources().keySet()
    );
    for (FallbackJoinSource source : sources) {
        if (source.isPhysical()) {
            nameResolver.qualifyTable(
                source.getQualifiedName(),
                context.getStatementDatabase()
            );
        }
    }
}
```

Join-only 语句边界仍只捕获 `DatabaseResolutionException`，保留 `SELECT` / `DATABASE_NOT_RESOLVED` 失败结果。

- [ ] **Step 4: 删除旧的全局来源扫描**

从 `HiveTargetColumnResolver` 删除 `extractAllSourceObjects`、为它新增的 `scanSourceOccurrences`、FROM 范围状态和专用 helper。恢复并保留以下已有 API 的行为：

- `extractSourceAliases`
- `extractTopLevelSourceAliases`
- `extractExplicitSourceAliases`
- Task 3 已加入的反引号 alias 读取。

确认新路径中不再有 `replace("`", "")` 式的全局修补。

- [ ] **Step 5: 运行 Session 和 Join 回归**

Run:

```bash
mvn -q -Dtest=HiveFallbackJoinSourceResolverTest,HiveSessionDatabaseResolutionTest,HiveJoinLineageTest,HiveQualifiedStarScopeTest,HiveQualifiedProjectionHardeningTest,HiveViewAndTemporaryLineageTest test
```

Expected: 所有选中测试通过，0 failures，0 errors。

- [ ] **Step 6: 提交 Join-only 接入与旧逻辑清理**

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java src/main/java/com/panfan/hivelineage/core/HiveTargetColumnResolver.java src/test/java/com/panfan/hivelineage/HiveSessionDatabaseResolutionTest.java
git commit -m "fix: validate scoped fallback join sources"
```

### Task 4: 完整验证与问题 1 回归门禁

**Files:**
- Modify only if a directly affected expected value must be updated; otherwise no source changes.

- [ ] **Step 1: 运行问题 1 全部定向测试**

Run:

```bash
mvn -q -Dtest=HivePhysicalNameResolverTest,HiveScriptPreprocessorUseDatabaseTest,ScriptAnalysisStateTest,HiveFallbackJoinSourceResolverTest,HiveSessionDatabaseResolutionTest,HiveColumnLineageTest,HiveScriptProcessingTest,HiveMetadataLineageTest,HiveJoinLineageTest,HiveQualifiedProjectionHardeningTest,HiveQualifiedStarScopeTest,HiveViewAndTemporaryLineageTest test
```

Expected: 0 failures，0 errors。

- [ ] **Step 2: 运行完整测试**

Run: `mvn -q test`

Expected: 0 failures，0 errors；只保留已知 JDBC 集成测试 skipped。

- [ ] **Step 3: 检查变更边界**

Run:

```bash
git diff --check
git status --short
git diff 5cff15e..HEAD --stat
```

Expected: `git diff --check` 无输出；工作树干净；只有本计划列出的生产与测试文件。

- [ ] **Step 4: 提交验证结果**

若 Step 1–3 未需要修改文件，不创建空提交；在任务报告中记录定向和完整测试数量。若只更新了直接受影响的预期值，则使用：

```bash
git add src/test/java/com/panfan/hivelineage/HiveSessionDatabaseResolutionTest.java
git commit -m "test: update scoped fallback join expectations"
```
