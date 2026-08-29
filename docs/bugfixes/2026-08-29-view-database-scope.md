# 视图定义裸表所属库修复记录

## 问题现象

请求默认库为 `session_db`，查询视图 `view_db.v_user`，其定义为
`select id from base_t` 时，视图内部的裸表应当是 `view_db.base_t`，不应使用调用语句的
Session 库。修复前表级视图展开无法得到 `view_db.base_t`。

## 最小复现

```sql
-- defaultDatabase = session_db
insert into target_t(id)
select id from view_db.v_user;

-- view_db.v_user definition
select id from base_t;
```

预期表来源和字段来源分别包含 `view_db.base_t` 和 `view_db.base_t.id`，不包含
`session_db.base_t`。

## 根因

`HiveLineageExpansionService` 在表级 `expandViewInputObjects` 和字段级
`viewColumnMapping` 中只调用 parser 解析视图 SQL，未将解析结果按视图元数据的所属库绑定。

## 修复方案

- `HiveLineageExpansionService` 复用分析器的 `HiveStatementDatabaseBinder`。
- 新增统一的 `parseBoundView`：先解析视图 SQL，再使用
  `viewMetadata.tableIdentifier.databaseName` 绑定裸物理表。
- 表级和字段级视图展开使用同一绑定入口，同时保留临时表逻辑名优先级。

## 影响范围

仅影响元数据视图定义中未带库名的物理表。显式 `db.table`、调用语句的 Session 库、
Join 表达式和视图展开深度语义不变。

## 回归测试

- `HiveViewAndTemporaryLineageTest.unqualifiedTableInViewUsesViewDatabase`

RED：1 个测试、1 failure、0 errors，缺少 `view_db.base_t`。

GREEN：

- 新用例：1 个测试，0 failures，0 errors，0 skipped。
- 视图定向回归：14 个测试，0 failures，0 errors，0 skipped。

## 关联提交

- `fix: bind view definitions to their database`
