# 标量子查询字段血缘误解析记录

- 日期：2026-08-31
- 状态：已修复并完成专项回归保护
- 关联提交：待定

## 问题现象

`INSERT INTO ... SELECT` 的投影字段中包含标量子查询时，表血缘可以识别出外层表和子查询表，但字段血缘会把子查询 SQL 关键字或子查询字段错误绑定到外层表，产生伪字段来源。

已观察到的错误字段包括：

- `whc.a.select`
- `whc.a.from`
- `whc.b.select`

## 最小复现

四表最小字段定义如下：

- `whc.a`：`id`、`field1`
- `whc.b`：`id`、`name`、`value`
- `whc.c`：`field1`、`id`、`name`、`value`
- `whc.d`：`field1`、`id`、`name`、`value`

### 复现一：count 标量子查询

```sql
insert into whc.c(field1,id)
select a.field1,(select count(1) from whc.b) as id
from whc.a;
```

预期表血缘：

- `whc.a -> whc.c`
- `whc.b -> whc.c`

预期字段血缘：

- `whc.a.field1 -> whc.c.field1`
- `whc.c.id` 的 `sourceColumns` 应为空，表达式可记录为 `(select count(1) from whc.b)`，来源表依赖为 `whc.b`

修复前实际字段血缘：

- `whc.a.field1 -> whc.c.field1`
- `whc.a.select, whc.a.from -> whc.c.id`

### 复现二：字段标量子查询

```sql
insert into whc.c(field1,id)
select a.field1,(select id from whc.b limit 1) as id
from whc.a;
```

预期表血缘：

- `whc.a -> whc.c`
- `whc.b -> whc.c`

预期字段血缘：

- `whc.a.field1 -> whc.c.field1`
- `whc.b.id -> whc.c.id`

修复前实际字段血缘：

- `whc.a.field1 -> whc.c.field1`
- `whc.a.select, whc.a.id -> whc.c.id`

### 复现三：外层 FROM 与标量子查询来源不同

```sql
insert into whc.c(field1)
select (select a.field1 from whc.a limit 1) as field1
from whc.b;
```

预期表血缘：

- `whc.a -> whc.c`
- `whc.b -> whc.c`

说明：`whc.b` 不提供目标字段值，但决定外层查询插入行数，因此保留为表依赖。

预期字段血缘：

- `whc.a.field1 -> whc.c.field1`

修复前实际字段血缘：

- `whc.b.select, whc.a.field1 -> whc.c.field1`

## 正确性口径

标量子查询作为目标字段表达式时：

- 子查询中出现的物理表应进入表血缘。
- 子查询中真实参与表达式取值的字段应进入目标字段的 `sourceColumns`。
- `count(1)` 只依赖子查询表的数据集合，不依赖任意具体字段，因此不应生成字段到字段血缘边。
- SQL 关键字 `select`、`from`、`limit` 等不应被识别为字段。
- 子查询字段不能因为外层存在默认来源表，就错误绑定到外层表。

## 修复方案

字段表达式提取逻辑识别表达式中的标量子查询边界，先按子查询自身查询块解析内部字段来源，再从外层正则扫描文本中屏蔽该子查询，避免 SQL 关键字或内部字段被外层默认来源表接管。遇到 `count(1)` 这类无字段聚合时，保留表达式和表依赖，但字段来源为空。

同时，主分析流程在 AST 反解析得到截断标量子查询表达式时，回退使用原 SQL 的顶层 select item，保证 `(select id from whc.b limit 1)` 这类表达式可以按完整文本解析。

## 回归案例

- 测试类：`HiveScalarSubqueryLineageTest`
- 测试方法：
  - `insertSelectCountScalarSubqueryKeepsTableDependencyWithoutInventingColumnSource`
  - `insertSelectColumnScalarSubqueryMapsSubqueryColumnToTargetColumn`
  - `insertSelectScalarSubqueryFromOuterQueryMapsInnerColumnToTargetColumn`
- RED 证据：临时验证运行 `mvn -q -Dtest=HiveScalarSubqueryLineageTest test`，3 tests，3 failures，均为字段来源误解析。
- GREEN 证据：`mvn -q -Dtest=HiveScalarSubqueryLineageTest test`，3 tests，0 failures，0 errors。

## 完整验证

- 命令：`mvn -q -Dtest=HiveScalarSubqueryLineageTest,HiveNestedSelectPseudoColumnRegressionTest,HiveColumnLineageTest,HiveParserAstAdapterTest,HiveInsertIntoDistinctLineageTest,HiveJoinLineageTest test`
- 结果：63 tests，0 failures，0 errors，0 skipped
- 条件跳过项：为节省本次修复 token/时间，未运行全量 `mvn clean test`
