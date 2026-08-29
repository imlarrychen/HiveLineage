# Join AST 重复遍历与逻辑对象泄漏修复记录

## 状态

- AST 查询块重复遍历已修复。
- 未解析逻辑对象进入最终物理 `joinTables` 的回退已修复。
- 定向回归已通过；Task 5 完整回归门禁在后续验证任务中统一执行。

## 问题现象

### 查询块被重复遍历

当 `FROM` 内联子查询、根 Join 和 `WHERE EXISTS` 同时存在时，同一个 `TOK_QUERY`
可能分别被 `TOK_SUBQUERY` 和通用子节点递归访问。三个真实 Join 会被输出为五个，
并产生不存在的嵌套路径和裸逻辑别名。

最小输入：

```sql
select *
from (
  select i.id from db1.a i join db1.b j on i.id = j.id
) s
join db1.c c on s.id = c.id
where exists (
  select 1 from db2.d i join db2.e j on i.id = j.id
);
```

修复前实际输出 5 个 Join；预期仅输出 3 个，路径为 `root`、
`root.subquery[1]` 和 `root.where.exists[2]`。

### 逻辑 direct 对象泄漏到物理结果

当 Join 条件中的字段全部无法展开时，`joinColumns` 为空。旧逻辑会将
`directJoinObjects` 全部复制到 `joinTables`，导致子查询别名 `x` 被伪装成物理表。

最小输入：

```sql
select *
from (select 1) x
join db1.t y on x.id = x.other_id;
```

修复前：`directJoinObjects` 和 `joinTables` 都包含 `x`。

修复后：`directJoinObjects` 保留 `x` 作为证据，同时保留
`UNKNOWN_COLUMN_LINEAGE` 告警；`joinColumns` 和 `joinTables` 不伪造物理来源。

## 根因

1. `HiveAstJoinExtractor` 在 `TOK_QUERY` 和 `TOK_SUBQUERY` 两条递归分支中分别直接提取
   Join，缺少一次 `extract` 调用内的 AST 节点身份级访问记录。
2. `HiveJoinConditionAnalyzer.joinTables` 在物理字段展开为空时，无条件接受
   direct 对象，没有区分 `db.table` 与单段逻辑别名。

## 修复方案

- 每次 `HiveAstJoinExtractor.extract` 创建基于 `IdentityHashMap` 的请求局部访问集合。
- CTE、root、`TOK_QUERY` 和 `TOK_SUBQUERY` 全部通过统一的单次查询块入口。
- 只有首次访问节点时才分配子查询路径、提取 Join 并递归；相同文本但不同
  AST 对象仍独立保留。
- `joinTables` 字段展开为空时，只接受非空的二段 `db.table` direct 对象作为
  防御性回退；单段 CTE、子查询或临时表逻辑名不进入最终物理层。

## 影响范围

- 仅影响 Join AST 嵌套查询遍历和最终物理 Join 表的防御性回退。
- Join 表达式原文、Join 类型、Session 数据库绑定、元数据边界不变。
- direct 层仍保留无法展开的逻辑对象和字段，用于诊断。

## 回归测试

- `HiveJoinLineageTest.inlineSubqueryAndExistsEachProduceOneScopedJoin`
- `HiveJoinLineageTest.identicalSubqueryTextWithDistinctAstNodesIsPreserved`
- `HiveQualifiedJoinColumnTest.unresolvedLogicalJoinObjectsRemainDirectOnly`
- `HiveQualifiedJoinColumnTest.logicalDirectObjectIsFilteredWhenAllJoinColumnsAreUnresolved`

RED 证据：

- AST 修复前：2 个新用例均失败，实际 Join 数分别为 5 和 7，预期都为 3。
- 物理回退修复前：1 个用例失败、0 个错误，失败原因为最终 `joinTables`
  仍包含逻辑对象 `x`。

GREEN 证据：

- AST 与作用域定向回归：72 个测试，0 failures，0 errors，0 skipped。
- Join direct/final 定向回归：28 个测试，0 failures，0 errors，0 skipped。

## 关联提交

- `181ec35 fix: visit join query blocks once`
- `fix: keep unresolved join objects out of physical tables`
