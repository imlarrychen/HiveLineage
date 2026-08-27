# INSERT INTO SELECT DISTINCT 伪字段血缘修复

- 日期：2026-08-27
- 状态：已修复
- 生产提交：`cab12f9`、`e7956b0`

## 问题现象

报告 SQL：

```sql
insert into whc.c(field1)
select distinct field1 from whc.a;
```

错误结果把 `distinct` 当成来源字段，生成了伪血缘：

```text
whc.a.distinct -> whc.c.field1
```

正确结果只有一条直接血缘：

```text
whc.a.field1 -> whc.c.field1 DIRECT
```

## 根因

根因链为：

```text
TOK_INSERT_INTO 未被目标解析识别
  -> 无法形成完整 AST 血缘结构
  -> 回退正则解析
  -> 将 distinct 当作投影字段
  -> 生成 whc.a.distinct 伪字段
```

Hive 对 `INSERT OVERWRITE` 使用 `TOK_DESTINATION`，对 `INSERT INTO` 使用
`TOK_INSERT_INTO`；原目标解析只读取前者。切换到正式 AST 路径后，还发现
`TOK_ALLCOLREF` 的 `TOK_TABNAME` owner 会在表达式提取时丢失，导致既有
`a.*`/`db.table.*` 行为回归。

## 修复方案

- 目标解析在 `INSERT_SELECT` 下同时查找 `TOK_DESTINATION` 和
  `TOK_INSERT_INTO`，并从目标节点解析 `TOK_TAB/TOK_TABNAME`。
- `TOK_ALLCOLREF` 带 `TOK_TABNAME` 时恢复 `owner.*`，兼容 `a.*` 和
  `db.table.*`；裸星号没有 owner 时仍保持 `*`。
- 保留已有 `TOK_SELECTDI`/`TOK_SELEXPR` 投影提取、静态分区处理和正则回退行为。

## 影响范围

覆盖目标为表的 `INSERT INTO ... SELECT ...`，包括普通投影、`SELECT DISTINCT`、
可选 `TABLE` 关键字、显式目标字段列表和静态分区；同时回归保护
`INSERT OVERWRITE` 及限定星号。该修复不涉及默认库补全、子查询中的 `SELECT`
误识别、`INSERT VALUES` 或目录写入，也不改变其他问题（问题 1/2/4）的范围。

## 回归案例

- `HiveParserAstAdapterTest`：验证 `INSERT INTO`/`INSERT INTO TABLE` 目标、
  `SELECT DISTINCT` 投影以及 `INSERT OVERWRITE` 目标。
- `HiveInsertIntoDistinctLineageTest`：验证报告 SQL 只产生
  `whc.a.field1 -> whc.c.field1 DIRECT`，以及静态分区常量血缘。
- `HiveColumnLineageTest`、`HivePositionalStarInsertLineageTest`、
  `HivePositionalStarScopeTest`：验证限定星号和既有星号位置行为。

RED 证据：初始专项 9 tests 中有 4 failures；目标解析切换到正式 AST 后，
`HiveColumnLineageTest` 的 31 tests 中有 5 个 qualified-star failures，均指向
`TOK_ALLCOLREF` owner 丢失。修复两个根因后上述回归全部通过。

## 完整验证

- 专项测试命令：

  ```bash
  /Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
    -Dtest=HiveParserAstAdapterTest,HiveInsertIntoDistinctLineageTest test
  ```

- 专项测试结果：9 tests，0 failures，0 errors。
- 相关测试命令：

  ```bash
  /Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
    -Dtest=HiveParserAstAdapterTest,HiveInsertIntoDistinctLineageTest,HiveColumnLineageTest,HivePositionalStarInsertLineageTest,HivePositionalStarScopeTest test
  ```

- 相关测试结果：47 tests，0 failures，0 errors。
- 全量命令：`/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn test`
- 全量结果：140 tests，0 failures，0 errors，5 skipped，`BUILD SUCCESS`。
- 5 个 skipped 为需要外部 MySQL JDBC 参数的条件测试。
- `git diff --check`：通过。
