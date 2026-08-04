# Reported Java Code Quality Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复质量报告在 10 个 Java 文件中暴露的 82 项问题，同时保持现有解析和 CLI 行为兼容。

**Architecture:** 使用包级不可变上下文对象收敛内部方法参数，用命名谓词拆分复杂条件，用显式游标替代循环体内的索引修改。格式、日志和 import 问题做局部修复，不新增运行时依赖或公共 API。

**Tech Stack:** Java 8、Maven、JUnit 4、Hive Parser 4.0.1、`java.util.logging`。

---

### Task 1: 固化报告与基线

**Files:**
- Create: `docs/superpowers/specs/2026-08-04-java-code-quality-rules.md`
- Create: `docs/superpowers/plans/2026-08-04-fix-reported-code-quality.md`

- [x] **Step 1: 运行完整测试建立行为基线**

Run: `/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn test`

Expected: 83 tests，0 failures，0 errors，5 skipped。

- [x] **Step 2: 运行报告对应的静态检查并确认失败**

```bash
awk 'length($0) > 150 { print FILENAME ":" FNR }' src/main/java/com/panfan/hivelineage/core/*.java \
  src/main/java/com/panfan/hivelineage/cli/*.java
rg -n 'System\.(out|err|exit)|printStackTrace' src/main/java
```

Expected: 输出截图中已报告的超长行和 CLI 禁止调用。

### Task 2: 修复主分析流程和视图展开参数

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveLineageExpansionService.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveViewAndTemporaryLineageTest.java`

- [x] **Step 1: 运行字段、视图和临时表测试，记录绿色基线**

Run: `mvn -Dtest=HiveColumnLineageTest,HiveViewAndTemporaryLineageTest test`

Expected: PASS。

- [x] **Step 2: 引入请求局部上下文并收敛参数**

在 `HiveScriptLineageAnalyzer` 中新增私有不可变 `StatementAnalysisContext`，封装 request、原始/规范 SQL、语句序号、临时表缓存、视图缓存和 warning；将 `analyzeStatement`、`analyzeSelectToTarget`、`analyzeSelectItemsToTarget` 降至最多 6 个参数。

在 `HiveLineageExpansionService` 中新增包级不可变 `ViewExpansionContext`，封装 request、深度、访问中的视图集合、warning、语句序号和视图字段缓存；递归时使用 `nextDepth()` 创建新上下文。

- [x] **Step 3: 消除参数重赋值、复杂条件与超长行**

使用 `expandedSelectItems`、`resolvedTargetColumns` 局部变量替代参数赋值；把语句类型判断拆成 `hasLineageShape` 和 `isSupportedLineageType`；所有调用按 150 字符换行。

- [x] **Step 4: 运行定向测试**

Run: `mvn -Dtest=HiveColumnLineageTest,HiveViewAndTemporaryLineageTest test`

Expected: PASS。

### Task 3: 修复 Join 分析参数、循环和条件

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveJoinConditionAnalyzer.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveAstJoinExtractor.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveJoinLineageTest.java`

- [x] **Step 1: 运行 Join 测试基线**

Run: `mvn -Dtest=HiveJoinLineageTest test`

Expected: PASS。

- [x] **Step 2: 引入 Join 解析上下文**

新增包级不可变 `JoinAnalysisContext`，封装 request、临时表投影、视图投影、CTE 投影、warning 和语句序号；让报告中的 7～10 参数方法降至最多 6 个参数。

- [x] **Step 3: 修复循环变量和复杂条件**

把 `collectTopLevelJoinClauses` 改为显式 `while` 游标；把 Join 表达式终止关键字判断提取为 `isJoinExpressionBoundary`；对 AST 提取器中 7 参数递归方法用 `NestedJoinTraversalContext` 收敛参数。

- [x] **Step 4: 清理 import 和超长 warning 行并运行测试**

Run: `mvn -Dtest=HiveJoinLineageTest test`

Expected: PASS。

### Task 4: 修复投影、文本扫描和目标字段解析

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveProjectionLineageResolver.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptPreprocessor.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveSqlTextUtils.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveTargetColumnResolver.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveParserAstAdapter.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveScriptProcessingTest.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveParserAstAdapterTest.java`

- [x] **Step 1: 运行脚本和 AST 测试基线**

Run: `mvn -Dtest=HiveScriptProcessingTest,HiveParserAstAdapterTest test`

Expected: PASS。

- [x] **Step 2: 将跳跃扫描 for 循环改为 while 游标**

保留原始跳转边界，把循环体内的 `i = ...`、`position = ...` 改为每个分支显式更新游标；使用新的局部位置变量替代参数重赋值。

- [x] **Step 3: 拆分字面量判断并规范内部值对象格式**

将字符串、数值和关键字字面量判断拆成命名谓词；展开 `CteBlock` 和 `CteExtraction` 的构造器、getter，保证花括号和一行一语句规则。

- [x] **Step 4: 收敛星号展开参数并清理 import、超长行**

使用 `ColumnResolutionContext` 封装 request、解析模式、warning 和语句序号；删除截图报告的未使用 import。

- [x] **Step 5: 运行定向测试**

Run: `mvn -Dtest=HiveScriptProcessingTest,HiveParserAstAdapterTest,HiveColumnLineageTest test`

Expected: PASS。

### Task 5: 修复 CLI 禁止调用

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/cli/LineageAnalyzeCli.java`
- Create: `src/test/java/com/panfan/hivelineage/cli/LineageAnalyzeCliTest.java`

- [x] **Step 1: 编写失败测试**

新增测试调用包级 `run(String[], Consumer<String>)`，断言成功时返回 0、失败语句时返回 2，并断言状态摘要通过注入的消息接收器输出。

- [x] **Step 2: 运行测试确认因 run 方法不存在而失败**

Run: `mvn -Dtest=LineageAnalyzeCliTest test`

Expected: FAIL，原因是缺少可测试的 `run` 入口。

- [x] **Step 3: 实现可测试 CLI 入口**

`main` 调用 `run`，消息默认交给 `java.util.logging.Logger`；`run` 返回 0 或 2，不调用 `System.out`、`System.err`、`System.exit` 或 `printStackTrace`。

- [x] **Step 4: 运行 CLI 测试**

Run: `mvn -Dtest=LineageAnalyzeCliTest test`

Expected: PASS。

### Task 6: 全量静态与行为验证

**Files:**
- Verify: all modified files

- [x] **Step 1: 检查超长行和禁止调用**

Run: Task 1 的 `awk` 与 `rg` 命令。

Expected: 无输出。

- [x] **Step 2: 检查 Java 文件行数**

Run: `mvn -Dtest=JavaSourceSizeTest test`

Expected: PASS。

- [x] **Step 3: 运行完整测试**

Run: `/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn test`

Expected: 0 failures，0 errors。

- [x] **Step 4: 检查变更边界**

Run: `git diff --check && git status --short`

Expected: 不包含 `release/` 或无关文件。
