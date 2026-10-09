# 非血缘语句分类实施计划

> **面向执行代理：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，逐项执行本计划；步骤使用复选框（`- [ ]`）追踪。

**目标：** 将不产生正式表/字段数据血缘的 Hive 语句识别为忽略结果，同时保留具有数据移动或转换语义的语句原有失败路径。

**架构：** 在 `HiveScriptLineageAnalyzer` 的后备血缘解析之前完成分类。分类器只匹配明确的语句族，并排除 `AS SELECT` 形式，避免误吞带查询的 DDL。被分类的非法 DDL 经 Hive 解析器校验后，作为 `IGNORED` 结果附加 `INVALID_DDL`，且不增加失败数。

**技术栈：** Java 8、Apache Hive `ParseDriver`、JUnit 4、Maven。

---

## 文件结构

- 修改：`src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java` —— 分类非血缘语句、校验分类后的 DDL、构建忽略结果。
- 修改：`src/main/java/com/panfan/hivelineage/result/WarningCode.java` —— 向调用方暴露 `INVALID_DDL`。
- 修改：`src/test/java/com/panfan/hivelineage/HiveScriptProcessingTest.java` —— 非血缘类别、非法 DDL、受保护的数据语句的回归测试。
- 修改：`README.md` —— 记录忽略类别和明确的“不忽略”边界。

### 任务 1：定义回归契约

**文件：**

- 修改：`src/test/java/com/panfan/hivelineage/HiveScriptProcessingTest.java`

- [ ] **步骤 1：为用户提供的非血缘 SQL 编写失败测试**

新增 JUnit 测试，并以 `includeIgnoredStatements(true)` 分析下列脚本：

```java
String script = "create temporary function f as 'pkg.F' using jar 'hdfs:///f.jar';"
    + "drop table tmp.old_t;"
    + "truncate table dwd.snapshot_t;"
    + "drop view dwd.old_v;"
    + "create temporary table tmp.staging(dt string);"
    + "create external table if not exists ods.empty_t(dt date);";
```

断言 `failedStatementCount == 0`、`ignoredStatementCount == 6`；每条返回语句的状态均为 `3`，表/字段血缘列表为空；跳过原因依次为 `CREATE_TEMPORARY_FUNCTION`、`DROP_TABLE`、`TRUNCATE_TABLE`、`DROP_VIEW`、`CREATE_TEMPORARY_TABLE_DDL`、`CREATE_EXTERNAL_TABLE_DDL`。

- [ ] **步骤 2：运行测试并验证 RED**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn -q -Dtest=HiveScriptProcessingTest test
```

预期：失败，因为这些语句当前会进入 `UNSUPPORTED_STATEMENT`。

- [ ] **步骤 3：为非法 DDL 编写失败测试**

以 `includeIgnoredStatements(true)` 分析 `"alter table dwd.t set tblproperties（'comment'='bad';"`。断言没有失败语句；唯一返回结果状态为 `3`，`skipReason == "ALTER_TABLE"`，并恰有一个 `WarningCode.INVALID_DDL` 告警。

- [ ] **步骤 4：运行测试并验证 RED**

再次运行步骤 2 的命令。预期：失败，因为非法 `ALTER TABLE` 当前被报告为不支持的解析失败。

- [ ] **步骤 5：编写具有数据语义的边界失败测试**

以 `includeIgnoredStatements(true)` 分析一条 `INSERT ... SELECT`、一条 CTAS 和一条 `MERGE`。断言前两条成功且产生正式血缘；断言 `MERGE` 不会以状态 `3` 和非血缘跳过原因返回。

- [ ] **步骤 6：运行测试并验证 RED**

再次运行步骤 2 的命令。预期：新增的非血缘断言失败，而数据语句始终在忽略分类之外。

### 任务 2：实现显式分类器

**文件：**

- 修改：`src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java:132-190,545-593`
- 修改：`src/main/java/com/panfan/hivelineage/result/WarningCode.java`

- [ ] **步骤 1：增加告警码**

```java
    UNSUPPORTED_STATEMENT,
    INVALID_DDL
```

- [ ] **步骤 2：增加私有分类值对象和显式匹配器**

值对象包含 `statementType`、`skipReason`、`validateWithHiveParser`。仅对以下类型返回分类：函数/资源注册、无查询的创建 DDL、`DROP TABLE|VIEW|DATABASE|FUNCTION`、`TRUNCATE TABLE`、`ALTER TABLE`、`MSCK`、`ANALYZE TABLE`、`SHOW`、`DESCRIBE`、`GRANT`、`REVOKE`。

使用 `\\bas\\s+(?:with\\b.*)?select\\b` 判断 DDL 是否带查询；CTAS 与带 `AS SELECT` 的视图不得返回该分类。

- [ ] **步骤 3：将已分类语句路由到忽略结果路径**

在变量替换后、调用 `analyzeStatement` 前取得分类结果。若分类非空，仅在 `validateWithHiveParser` 为真时校验；增加 `ignored`；仅在 `includeIgnoredStatements` 为真时追加 `ignoredResult`。保持现有的 `SET`、`USE`、纯查询、`INSERT VALUES`、目录写入行为不变。

- [ ] **步骤 4：添加非法 DDL 诊断且不改变状态**

使用 `new HiveParserAstAdapter().parse(normalized)` 校验。解析器抛出运行时异常时，追加：

```java
new LineageWarning(WarningCode.INVALID_DDL, "Ignored DDL is syntactically invalid", context.getStatementIndex())
```

不校验会话/资源命令、`SHOW`、`DESCRIBE`、`GRANT`、`REVOKE`。

- [ ] **步骤 5：运行聚焦测试并验证 GREEN**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn -q -Dtest=HiveScriptProcessingTest test
```

预期：`HiveScriptProcessingTest` 的全部测试通过。

- [ ] **步骤 6：提交实现与回归测试**

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java src/main/java/com/panfan/hivelineage/result/WarningCode.java src/test/java/com/panfan/hivelineage/HiveScriptProcessingTest.java
git commit -m "feat: ignore non-lineage Hive statements"
```

### 任务 3：补充文档并全量验证

**文件：**

- 修改：`README.md:92-98,132-139`

- [ ] **步骤 1：记录类别与边界**

记录可忽略的会话/资源命令、结构/元数据 DDL、元数据查看语句和权限语句。明确列出 `INSERT ... SELECT`、CTAS、带查询视图、`MERGE`、`UPDATE`、`DELETE`、`LOAD DATA`、`IMPORT`、`EXPORT` 不得忽略。

- [ ] **步骤 2：记录非法 DDL 的诊断语义**

记录：已忽略 DDL 类别中的非法语句仍是 `IGNORED`；请求忽略详情时会暴露 `INVALID_DDL`。

- [ ] **步骤 3：运行完整测试套件**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn test -q
```

预期：退出码 `0`。

- [ ] **步骤 4：检查最终差异**

```bash
git diff --check HEAD~1..HEAD && git status --short
```

预期：没有空白错误，且工作区中只保留已记录的改动。

- [ ] **步骤 5：提交文档**

```bash
git add README.md docs/superpowers/specs/2026-10-09-non-lineage-statement-classification-design.md docs/superpowers/plans/2026-10-09-non-lineage-statement-classification.md
git commit -m "docs: describe ignored Hive statement handling"
```
