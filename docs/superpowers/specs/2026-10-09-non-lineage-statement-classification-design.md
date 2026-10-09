# 非血缘 Hive 语句分类设计

## 目标

将已识别但不产生正式表/字段血缘的 Hive 语句从 `UNSUPPORTED_STATEMENT` 失败改为 `IGNORED`。非法的此类 DDL 不阻断血缘任务，但必须以 `INVALID_DDL` 告警保留诊断信息。

## 范围

分类器覆盖以下没有表间或字段间数据流的语句：

- 函数和资源会话语句：`CREATE/DROP [TEMPORARY] FUNCTION`、`ADD JAR|FILE|ARCHIVE`。
- 结构或元数据 DDL：不带 `AS SELECT` 的 `CREATE [EXTERNAL|TEMPORARY] TABLE`、建库和建索引、`DROP`、`TRUNCATE`、`ALTER TABLE`、`MSCK`、`ANALYZE TABLE`。
- 元数据查询和权限语句：`SHOW`、`DESCRIBE`、`GRANT`、`REVOKE`。

下列语句继续进入正式解析路径，不能被本功能忽略：`INSERT ... SELECT`、CTAS、`CREATE/ALTER VIEW AS SELECT`、`MERGE`、`UPDATE`、`DELETE`、`LOAD DATA`、`IMPORT`、`EXPORT`。其中当前不支持的语句仍应按原有失败语义返回，避免遗漏真实的数据移动或变换。

## 行为

1. 在常规 SQL 血缘解析之前执行受控的非血缘语句分类。
2. 合法语句使 `ignoredStatementCount` 加一；只有 `includeIgnoredStatements=true` 时，返回状态为 `IGNORED` 的语句结果，且带精确的 `statementType` 和 `skipReason`。
3. 被分类为 DDL 的语句通过 Hive 解析器做语法健康检查。解析失败时仍返回 `IGNORED`，并附带 `INVALID_DDL` 告警；不增加 `failedStatementCount`。
4. `DROP TABLE` 命中当前脚本已注册的临时表时，移除对应临时表来源和字段映射。只有结构定义的临时表登记为空映射，以避免后续引用被错误识别为物理表。

## 实现边界

分类应使用有限、明确的语句族匹配，并在具有数据流语义的分支之前排除 `AS SELECT`。不以泛化的 DDL 前缀作为忽略条件；这能保护后续新增的含查询 DDL 语法。

## 测试

测试先行，覆盖：

- 用户案例中的函数注册、`DROP`、`TRUNCATE`、合法的表 DDL、临时表 DDL；
- 默认隐藏和 `includeIgnoredStatements` 返回详情两种输出；
- 非法 `ALTER TABLE` 产生 `INVALID_DDL` 告警但不失败；
- `INSERT ... SELECT`、CTAS 与 `MERGE` 不会被分类器忽略；
- `DROP` 已注册临时表后，其上下文映射被移除。
