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
