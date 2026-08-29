# 跨 USE 临时表回归记录

## 结论

当前实现已正确支持“创建临时表后切换数据库”的 Session 语义，本次未发现需要修改的
生产缺陷。原计划缺少端到端自动化保护，本次补充永久回归用例。

## 场景

```sql
-- defaultDatabase = db1
create temporary table tmp_t as select id from source_t;
use db2;
insert into target_t(id) select id from tmp_t;
```

预期：

- 目标表为 `db2.target_t`。
- `tmp_t` 仍匹配同一 Session 中已创建的临时表。
- 最终物理表和字段来源为 `db1.source_t` 和 `db1.source_t.id`。
- 不生成 `db2.tmp_t`。

## 现有实现为何正确

- 创建临时表时，输出保留逻辑简名 `tmp_t`，其来源在创建语句当时已绑定为
  `db1.source_t`。
- `registerTemporaryTable` 将临时表简名和列投影保存在请求级 `ScriptAnalysisState`。
- 后续语句即使已执行 `USE db2`，`HiveStatementDatabaseBinder` 仍会在物理表补库前
  不区分大小写地匹配临时表名，保留 `tmp_t`。
- 血缘展开阶段优先使用 Session 临时表索引，穿透到创建时的物理来源。

## 回归测试

- `HiveViewAndTemporaryLineageTest.temporaryTableIsExpandedBeforePermanentTableQualification`

新用例在当前生产代码上首次运行即通过：1 个测试，0 failures，0 errors，0 skipped。

视图、临时表、CTE、嵌套查询与 Session 数据库定向回归：59 个测试，
0 failures，0 errors，0 skipped。

## 关联提交

- `test: lock cross-use temporary table scope`
