# Join AST 查询块单次遍历实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从 AST Join 提取源头消除嵌套查询块重复遍历，保证每个真实 Join 只输出一次，并阻止未解析逻辑别名泄漏到最终物理 `joinTables`。

**Architecture:** `HiveAstJoinExtractor` 在每次 `extract` 调用中维护基于 `ASTNode` 对象身份的已访问集合，所有 CTE、root、`TOK_QUERY` 和 `TOK_SUBQUERY` 入口统一通过单次查询块访问方法。`HiveJoinConditionAnalyzer` 保留 direct 证据和 warning，但最终物理 Join 无法从字段展开时不再回退输出裸逻辑对象。

**Tech Stack:** Java 8、Apache Hive Parser 4.0.1、JUnit 4.13.2、Maven Surefire。

---

## 文件结构

- Modify: `src/main/java/com/panfan/hivelineage/core/HiveAstJoinExtractor.java`
  - 请求局部 identity 访问集合和统一查询块入口。
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveJoinConditionAnalyzer.java`
  - 最终物理 Join 不使用未展开的裸逻辑 direct object 回退。
- Modify: `src/test/java/com/panfan/hivelineage/HiveJoinLineageTest.java`
  - 嵌套查询 + EXISTS 单次遍历与作用域回归。
- Modify: `src/test/java/com/panfan/hivelineage/HiveQualifiedJoinColumnTest.java`
  - 未解析逻辑对象的 direct/final 层分离回归。

### Task 1: 在 AST 提取源头保证查询块单次遍历

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveAstJoinExtractor.java`
- Modify: `src/test/java/com/panfan/hivelineage/HiveJoinLineageTest.java`

- [ ] **Step 1: 编写重复遍历失败测试**

```java
@Test
public void inlineSubqueryAndExistsEachProduceOneScopedJoin() {
    StatementLineageResult statement = analyzeJoinOnly(
        "select * "
            + "from (select i.id from db1.a i join db1.b j on i.id=j.id) s "
            + "join db1.c c on s.id=c.id "
            + "where exists ("
            + "select 1 from db2.d i join db2.e j on i.id=j.id"
            + ")"
    );

    assertEquals(3, statement.getJoinConditions().size());
    assertScopedJoin(statement, "root", "db1.a.id", "db1.c.id");
    assertScopedJoin(statement, "root.subquery[1]", "db1.a.id", "db1.b.id");
    assertScopedJoin(statement, "root.where.exists[2]", "db2.d.id", "db2.e.id");
    assertSequentialJoinIndexes(statement, 1, 2, 3);
}
```

`analyzeJoinOnly` 使用已有 `HiveScriptLineageAnalyzer`，请求开启 `parseJoinConditions=true`和 `TABLE_AND_COLUMN`。`assertScopedJoin` 按 `JoinCondition.getQueryBlockPath()` 查找唯一结果，再用 `assertContainsColumn` 验证两个物理字段；若缺失或同路径多于一条则立即 `fail`。`assertSequentialJoinIndexes` 按结果顺序断言 index 连续。

- [ ] **Step 2: 增加独立但文本相同的子查询回归**

```java
@Test
public void identicalSubqueryTextWithDistinctAstNodesIsPreserved() {
    StatementLineageResult statement = analyzeJoinOnly(
        "select * "
            + "from (select i.id from db1.a i join db1.b j on i.id=j.id) s1 "
            + "join (select i.id from db1.a i join db1.b j on i.id=j.id) s2 "
            + "on s1.id=s2.id"
    );

    assertEquals(3, statement.getJoinConditions().size());
    assertOneJoinAtPath(statement, "root.subquery[1]");
    assertOneJoinAtPath(statement, "root.subquery[2]");
    assertOneJoinAtPath(statement, "root");
}
```

该测试锁定 identity 去重不得按 SQL 文本、Join 表达式或物理表列表合并真实独立节点。

- [ ] **Step 3: 运行新测试并确认 RED**

Run:

```bash
mvn -q -Dtest=HiveJoinLineageTest#inlineSubqueryAndExistsEachProduceOneScopedJoin+identicalSubqueryTextWithDistinctAstNodesIsPreserved test
```

Expected: FAIL。第一个用例当前输出 5 个 Join 或出现重复/虚假路径；失败不得来自测试 helper 自身的编译错误。

- [ ] **Step 4: 实现请求局部 AST identity 集合**

`extract` 创建访问集合：

```java
Set<ASTNode> visitedQueries = Collections.newSetFromMap(
    new IdentityHashMap<ASTNode, Boolean>()
);
```

所有 CTE 和 root 入口传入同一集合。新增统一入口：

```java
private boolean collectQueryOnce(
    ASTNode queryNode,
    String queryBlockPath,
    NestedJoinTraversalContext context,
    Set<ASTNode> visitedQueries
) {
    if (queryNode == null || !visitedQueries.add(queryNode)) {
        return false;
    }
    collectJoinConditionsFromQuery(
        queryNode,
        queryBlockPath,
        context.getCteAliases(),
        context.getJoinIndex(),
        context.getConditions()
    );
    collectNestedSubqueryJoinConditionsFromChildren(
        queryNode,
        queryBlockPath,
        context,
        visitedQueries
    );
    return true;
}
```

实际参数可根据现有 `NestedJoinTraversalContext` 构造器小幅调整，但必须保持这些契约：

- 只有 `visitedQueries.add(queryNode)` 成功后才提取 Join、递增 `joinIndex` 和递归子查询。
- `TOK_QUERY` 和 `TOK_SUBQUERY` 分支都调用 `collectQueryOnce`，不再分别直接调用 `collectJoinConditionsFromQuery`。
- 重复节点不消耗 `nextSubqueryIndex()`。应先确认 query 未访问，再分配 `.subquery[n]` 或 `.where.exists[n]`。
- 访问集合不保存在 `HiveAstJoinExtractor` 字段中。

- [ ] **Step 5: 运行 Task 1 定向测试**

Run:

```bash
mvn -q -Dtest=HiveJoinLineageTest#inlineSubqueryAndExistsEachProduceOneScopedJoin+identicalSubqueryTextWithDistinctAstNodesIsPreserved test
```

Expected: 2 tests，0 failures，0 errors。

- [ ] **Step 6: 运行 AST Join 与作用域回归**

Run:

```bash
mvn -q -Dtest=HiveJoinLineageTest,HiveSessionDatabaseResolutionTest,HiveQualifiedProjectionHardeningTest,HiveQualifiedStarScopeTest test
```

Expected: 全部通过，0 failures，0 errors。

- [ ] **Step 7: 提交 AST 单次遍历修复**

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveAstJoinExtractor.java src/test/java/com/panfan/hivelineage/HiveJoinLineageTest.java
git commit -m "fix: visit join query blocks once"
```

### Task 2: 防止逻辑 direct 对象泄漏到最终物理 Join

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveJoinConditionAnalyzer.java`
- Modify: `src/test/java/com/panfan/hivelineage/HiveQualifiedJoinColumnTest.java`

- [ ] **Step 1: 编写最终物理表不回退裸逻辑名的失败测试**

```java
@Test
public void unresolvedLogicalJoinObjectsRemainDirectOnly() {
    StatementLineageResult statement = analyzeJoinOnly(
        "select ??? from (select 1) x join db1.t y on x.id=y.id"
    );
    JoinCondition condition = statement.getJoinConditions().get(0);

    assertContainsSource(condition.getDirectJoinObjects(), "x");
    assertContainsColumn(condition.getDirectJoinColumns(), "x.id");
    assertNotContainsSource(condition.getJoinTables(), "x");
    assertContainsSource(condition.getJoinTables(), "db1.t");
    assertContainsColumn(condition.getJoinColumns(), "db1.t.id");
    assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
}
```

如该 SQL 被 Hive AST 正常解析且 `select 1` 无 `x.id` 投影，预期 direct 保留逻辑对象，最终物理层仅包含已解析的 `db1.t`。

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```bash
mvn -q -Dtest=HiveQualifiedJoinColumnTest#unresolvedLogicalJoinObjectsRemainDirectOnly test
```

Expected: FAIL，当前 `joinTables` 回退包含裸逻辑对象 `x`。

- [ ] **Step 3: 收紧 `joinTables` 回退规则**

将当前的“字段展开结果为空时复制所有 `directObjects`”改为只允许确认的物理对象回退。新增：

```java
private boolean isQualifiedPhysicalObject(DataObjectRef object) {
    if (object == null || object.getQualifiedName() == null) {
        return false;
    }
    String[] parts = object.getQualifiedName().split("\\.", -1);
    return parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty();
}
```

`joinTables` 逻辑：

```java
for (DataObjectRef directObject : directObjects) {
    if (isQualifiedPhysicalObject(directObject)) {
        expansionService.addInputObject(tables, seen, directObject);
    }
}
```

已从 `joinColumns` 提取到的物理表仍保持优先。单段 CTE、子查询和临时表名不进入最终物理表回退。已绑定的 `db.table` 可作为防御性回退。

- [ ] **Step 4: 运行 direct/final 层回归**

Run:

```bash
mvn -q -Dtest=HiveQualifiedJoinColumnTest,HiveJoinLineageTest test
```

Expected: 全部通过，0 failures，0 errors。

- [ ] **Step 5: 提交物理 Join 回退收紧**

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveJoinConditionAnalyzer.java src/test/java/com/panfan/hivelineage/HiveQualifiedJoinColumnTest.java
git commit -m "fix: keep unresolved join objects out of physical tables"
```

### Task 3: 完整回归与 Task 5 门禁

**Files:**
- No source changes expected.

- [ ] **Step 1: 运行 Task 5 全部定向回归**

Run:

```bash
mvn -q -Dtest=HiveJoinLineageTest,HiveSessionDatabaseResolutionTest,HiveQualifiedJoinColumnTest,HiveQualifiedProjectionHardeningTest,HiveQualifiedStarScopeTest,HiveColumnLineageTest,HiveMetadataLineageTest,HiveViewAndTemporaryLineageTest,HiveFallbackJoinSourceResolverTest test
```

Expected: 0 failures，0 errors。

- [ ] **Step 2: 运行完整验证**

Run: `mvn -q clean test`

Expected: 0 failures，0 errors；已知 5 个 JDBC 环境测试可保持 skipped。

- [ ] **Step 3: 检查工作树和变更范围**

Run:

```bash
git diff --check
git status --short
git diff ee6af21..HEAD --stat
```

Expected: `git diff --check` 无输出；工作树干净；变更仅包含本计划文档、`HiveAstJoinExtractor`、`HiveJoinConditionAnalyzer` 和两个 Join 测试文件。

- [ ] **Step 4: 记录验证结果**

若所有命令通过，不创建空提交。在任务报告中记录 fresh Surefire 的 `Tests run`、`Failures`、`Errors`、`Skipped` 和当前 HEAD。
