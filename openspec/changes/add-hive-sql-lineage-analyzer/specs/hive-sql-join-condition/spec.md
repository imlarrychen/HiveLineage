# 能力：Hive SQL Join 条件解析

## 新增需求

### 需求：Join 条件解析是独立 SQL 结构分析能力

Join 条件解析不属于正式表血缘或字段血缘。解析器必须把 Join 条件解析结果与表血缘、字段血缘分开建模。

#### 场景：Join 条件不计入正式血缘

- **假设** 语句为 `select * from ods.t1 left join ods.t2 on t1.id = t2.id`
- **并且** 请求开启 Join 条件解析
- **当** 该语句被解析
- **那么** 解析器必须返回语句级解析结果
- **并且** 该结果的 `formalLineageProduced` 必须为 `false`
- **并且** 该结果不得产生表级血缘
- **并且** 该结果不得产生字段级血缘
- **并且** 该结果必须包含 Join 条件解析结果

#### 场景：Join 条件解析结果可单独持久化

- **假设** 某条 SQL 语句包含 Join 条件
- **当** 解析器返回语句级解析结果
- **那么** Join 条件解析结果必须携带 `workflowId`、`taskId`、`taskVersion`、`statementIndex`、`statementId` 和原始 SQL
- **并且** Join 条件解析结果必须能通过 `statementId` 与语句主记录关联
- **并且** Join 条件解析结果不得要求与表血缘或字段血缘存储在同一张表

### 需求：按需开启 Join 条件解析

解析器必须提供独立开关控制 Join 条件解析。该开关与表血缘、字段血缘解析模式正交。

#### 场景：默认不解析 Join 条件

- **假设** 请求未开启 Join 条件解析
- **并且** 语句为 `insert overwrite table dwd.t select a.id from ods.t1 a join ods.t2 b on a.id = b.id`
- **当** 该语句被解析
- **那么** 解析器可以产生表血缘或字段血缘
- **但是** 该语句级解析结果中不得包含 Join 条件解析结果

#### 场景：开启 Join 条件解析

- **假设** 请求开启 Join 条件解析
- **并且** 语句为 `insert overwrite table dwd.t select a.id from ods.t1 a join ods.t2 b on a.id = b.id`
- **当** 该语句被解析
- **那么** 语句级解析结果中必须包含 Join 条件解析结果
- **并且** 表血缘、字段血缘是否解析仍由原有血缘解析模式控制

#### 场景：只解析 Join 条件

- **假设** 请求只开启 `JOIN_CONDITION` 分析能力
- **并且** 未开启表血缘和字段血缘分析能力
- **并且** 语句为 `select * from ods.t1 a join ods.t2 b on a.id = b.id`
- **当** 该语句被解析
- **那么** 解析器必须返回语句级解析结果
- **并且** 该结果必须包含 Join 条件解析结果
- **并且** 该结果不得包含表级血缘
- **并且** 该结果不得包含字段级血缘
- **并且** 该能力组合必须通过 `JOIN_CONDITION` 这类正向功能名表达

### 需求：语句级结果包含 Join 条件集合

解析结果必须保持一条 SQL 语句对应一个语句级解析结果。一个语句中的多个显式 Join 子句必须作为该语句结果中的子集合返回。

推荐结果字段命名为 `joinConditions`，单条 Join 条件记录命名为 `JoinCondition`。

一个 `JoinCondition` 表示一个完整的显式 `JOIN ... ON ...` 关联关系，而不是单个字段等值条件或单个 `and` 子条件。一条 SQL 语句可以包含多个 `JoinCondition`，这些关联关系可以连续出现，也可以分布在主查询、CTE、子查询、派生表等不同查询块中。

`JoinCondition` 必须包含 `queryBlockPath`，用于标识该 Join 条件所在的查询块。`joinIndex` 是当前语句内全局递增序号，不能只在单个查询块内重新计数。

`JoinCondition` 必须同时区分直接关联对象和追溯后的物理表字段：

- `directJoinObjects`：当前查询块中 `ON` 表达式直接引用到的对象。若直接对象是物理表，则输出完整物理表名；若直接对象是视图，则输出视图名；若直接对象是 CTE、临时表或派生表，则输出当前查询块中的名称或别名。
- `directJoinColumns`：当前查询块中 `ON` 表达式直接引用的字段，按当前查询块对象或别名表达，例如 `x.id`、`a.id`。
- `joinTables`：从 `directJoinObjects` 或 `directJoinColumns` 尽量追溯后的物理表集合。
- `joinColumns`：从 `directJoinColumns` 尽量追溯后的物理字段集合。

如果视图、CTE、临时表或派生表字段无法唯一追溯到物理字段，解析器必须保留 `directJoinObjects`、`directJoinColumns` 和原始 `joinExpression`，并返回字段追溯不明确的 warning。`joinTables` 和 `joinColumns` 可以只包含已成功追溯的物理对象和字段。

#### 场景：单条 Join 子句

- **假设** SQL 为：

```sql
select *
from ods.t1 a
left join ods.t2 b
  on a.id = b.id
```

- **并且** 请求开启 Join 条件解析
- **当** SQL 被解析
- **那么** 结果中必须只有一个语句级解析结果
- **并且** 该语句级解析结果必须包含一个 `JoinCondition`
- **并且** 该 `JoinCondition.joinIndex` 必须为 `1`
- **并且** 该 `JoinCondition.queryBlockPath` 必须为 `root`
- **并且** 该 `JoinCondition.joinType` 必须为 `2`
- **并且** 该 `JoinCondition.joinTypeName` 必须为 `LEFT_JOIN`
- **并且** 该 `JoinCondition.joinExpression` 必须保留 `a.id = b.id`
- **并且** 该 `JoinCondition.directJoinObjects` 必须包含 `ods.t1` 和 `ods.t2`
- **并且** 该 `JoinCondition.directJoinColumns` 必须包含 `a.id` 和 `b.id`
- **并且** 该 `JoinCondition.joinColumns` 必须包含 `ods.t1.id` 和 `ods.t2.id`
- **并且** 该 `JoinCondition.joinTables` 必须包含 `ods.t1` 和 `ods.t2`

#### 场景：连续 Join 子句

- **假设** SQL 为：

```sql
select *
from ods.a a
join ods.b b
  on a.id = b.a_id
left join ods.c c
  on b.c_id = c.id
join ods.d d
  on a.biz_id = d.biz_id and c.dt = d.dt
```

- **并且** 请求开启 Join 条件解析
- **当** SQL 被解析
- **那么** 结果中必须只有一个语句级解析结果
- **并且** 该语句级解析结果的 `joinConditions` 必须包含三条记录
- **并且** 第一条记录的 `joinIndex = 1`、`queryBlockPath = root`、`joinTypeName = INNER_JOIN`、`joinExpression = a.id = b.a_id`
- **并且** 第二条记录的 `joinIndex = 2`、`queryBlockPath = root`、`joinTypeName = LEFT_JOIN`、`joinExpression = b.c_id = c.id`
- **并且** 第三条记录的 `joinIndex = 3`、`queryBlockPath = root`、`joinTypeName = INNER_JOIN`、`joinExpression = a.biz_id = d.biz_id and c.dt = d.dt`
- **并且** 不要求 Join 条件结果表达完整 Join 树或 Join 左侧累计对象集合

#### 场景：多个 CTE 中存在不连续 Join

- **假设** SQL 为：

```sql
with x as (
  select a.id as id
  from ods.a a
  join ods.b b
    on a.id = b.id
),
y as (
  select c.id as id
  from ods.c c
  join ods.d d
    on c.id = d.id
)
select *
from x
join y
  on x.id = y.id
```

- **并且** 请求开启 Join 条件解析
- **当** SQL 被解析
- **那么** 结果中必须只有一个语句级解析结果
- **并且** 该语句级解析结果的 `joinConditions` 必须包含三条记录
- **并且** 第一条记录的 `joinIndex = 1`、`queryBlockPath = cte.x`、`joinExpression = a.id = b.id`
- **并且** 第二条记录的 `joinIndex = 2`、`queryBlockPath = cte.y`、`joinExpression = c.id = d.id`
- **并且** 第三条记录的 `joinIndex = 3`、`queryBlockPath = root`、`joinExpression = x.id = y.id`
- **并且** 解析器不得因为这些 Join 分布在不同查询块而只返回主查询 Join

#### 场景：子查询中存在不连续 Join

- **假设** SQL 为：

```sql
select *
from ods.a a
join ods.b b
  on a.id = b.id
where exists (
  select 1
  from ods.c c
  join ods.d d
    on c.id = d.id
  where c.id = a.id
)
```

- **并且** 请求开启 Join 条件解析
- **当** SQL 被解析
- **那么** 结果中必须只有一个语句级解析结果
- **并且** 该语句级解析结果的 `joinConditions` 必须包含两条记录
- **并且** 第一条记录的 `joinIndex = 1`、`queryBlockPath = root`、`joinExpression = a.id = b.id`
- **并且** 第二条记录的 `joinIndex = 2`、`queryBlockPath = root.where.exists[1]`、`joinExpression = c.id = d.id`
- **并且** 外层 `where exists` 中的 `c.id = a.id` 不得作为显式 Join 条件写入 `joinConditions`

#### 场景：Join 直接对象是前序临时表

- **假设** 同一个 Hive 任务脚本中前序语句为：

```sql
create temporary table tmp_user as
select user_id, dept_id
from ods.user_base;
```

- **并且** 后续语句为：

```sql
select *
from tmp_user t
join dim.dept d
  on t.dept_id = d.dept_id
```

- **并且** 请求开启 Join 条件解析
- **当** 后续语句被解析
- **那么** 后续语句级解析结果必须包含一个 `JoinCondition`
- **并且** 该 `JoinCondition.queryBlockPath` 必须为 `root`
- **并且** 该 `JoinCondition.joinExpression` 必须保留 `t.dept_id = d.dept_id`
- **并且** 该 `JoinCondition.directJoinObjects` 必须包含 `tmp_user` 和 `dim.dept`
- **并且** 该 `JoinCondition.directJoinColumns` 必须包含 `t.dept_id` 和 `d.dept_id`
- **并且** 解析器必须优先从当前 Session 的临时表上下文追溯 `tmp_user.dept_id`
- **并且** 该 `JoinCondition.joinTables` 必须包含 `ods.user_base` 和 `dim.dept`
- **并且** 该 `JoinCondition.joinColumns` 必须包含 `ods.user_base.dept_id` 和 `dim.dept.dept_id`

#### 场景：临时表字段无法唯一追溯时保留直接字段

- **假设** 同一个 Hive 任务脚本中前序语句为：

```sql
create temporary table tmp_user as
select *
from ods.user_base u
join ods.user_ext e
  on u.user_id = e.user_id;
```

- **并且** 后续语句为：

```sql
select *
from tmp_user t
join dim.dept d
  on t.dept_id = d.dept_id
```

- **并且** 请求开启 Join 条件解析
- **当** 后续语句被解析
- **那么** 后续语句 Join 条件的 `joinExpression` 必须保留 `t.dept_id = d.dept_id`
- **并且** 后续语句 Join 条件的 `directJoinObjects` 必须包含 `tmp_user` 和 `dim.dept`
- **并且** 后续语句 Join 条件的 `directJoinColumns` 必须包含 `t.dept_id` 和 `d.dept_id`
- **并且** 如果 `tmp_user.dept_id` 无法在临时表中唯一追溯到一个物理字段，解析器不得臆造 `tmp_user.dept_id` 的物理字段
- **并且** 后续语句 Join 条件的 `joinColumns` 可以只包含已成功追溯的 `dim.dept.dept_id`
- **并且** 解析器必须返回临时表字段追溯不明确的 warning

#### 示例：CTE 中不连续 Join 的解析输出

- **假设** SQL 为：

```sql
with x as (
  select a.id as id
  from ods.a a
  join ods.b b
    on a.id = b.id
),
y as (
  select c.id as id
  from ods.c c
  join ods.d d
    on c.id = d.id
)
select *
from x
join y
  on x.id = y.id
```

- **那么** 推荐输出结构为：

```json
{
  "workflowId": "wf_001",
  "taskId": "task_query_001",
  "taskVersion": "V1",
  "statementIndex": 1,
  "statementId": "wf_001_task_query_001_V1_1",
  "rawSql": "with x as (...) select * from x join y on x.id = y.id",
  "formalLineageProduced": false,
  "tableLineages": [],
  "columnLineages": [],
  "joinConditions": [
    {
      "workflowId": "wf_001",
      "taskId": "task_query_001",
      "taskVersion": "V1",
      "statementIndex": 1,
      "statementId": "wf_001_task_query_001_V1_1",
      "joinIndex": 1,
      "queryBlockPath": "cte.x",
      "joinType": 1,
      "joinTypeName": "INNER_JOIN",
      "joinExpression": "a.id = b.id",
      "directJoinObjects": [
        "ods.a",
        "ods.b"
      ],
      "directJoinColumns": [
        "a.id",
        "b.id"
      ],
      "joinTables": [
        "ods.a",
        "ods.b"
      ],
      "joinColumns": [
        "ods.a.id",
        "ods.b.id"
      ]
    },
    {
      "workflowId": "wf_001",
      "taskId": "task_query_001",
      "taskVersion": "V1",
      "statementIndex": 1,
      "statementId": "wf_001_task_query_001_V1_1",
      "joinIndex": 2,
      "queryBlockPath": "cte.y",
      "joinType": 1,
      "joinTypeName": "INNER_JOIN",
      "joinExpression": "c.id = d.id",
      "directJoinObjects": [
        "ods.c",
        "ods.d"
      ],
      "directJoinColumns": [
        "c.id",
        "d.id"
      ],
      "joinTables": [
        "ods.c",
        "ods.d"
      ],
      "joinColumns": [
        "ods.c.id",
        "ods.d.id"
      ]
    },
    {
      "workflowId": "wf_001",
      "taskId": "task_query_001",
      "taskVersion": "V1",
      "statementIndex": 1,
      "statementId": "wf_001_task_query_001_V1_1",
      "joinIndex": 3,
      "queryBlockPath": "root",
      "joinType": 1,
      "joinTypeName": "INNER_JOIN",
      "joinExpression": "x.id = y.id",
      "directJoinObjects": [
        "x",
        "y"
      ],
      "directJoinColumns": [
        "x.id",
        "y.id"
      ],
      "joinTables": [
        "ods.a",
        "ods.c"
      ],
      "joinColumns": [
        "ods.a.id",
        "ods.c.id"
      ]
    }
  ]
}
```

#### 示例：子查询中不连续 Join 的解析输出

- **假设** SQL 为：

```sql
select *
from ods.a a
join ods.b b
  on a.id = b.id
where exists (
  select 1
  from ods.c c
  join ods.d d
    on c.id = d.id
  where c.id = a.id
)
```

- **那么** 推荐输出结构为：

```json
{
  "workflowId": "wf_001",
  "taskId": "task_query_002",
  "taskVersion": "V1",
  "statementIndex": 1,
  "statementId": "wf_001_task_query_002_V1_1",
  "rawSql": "select * from ods.a a join ods.b b on a.id = b.id where exists (...)",
  "formalLineageProduced": false,
  "tableLineages": [],
  "columnLineages": [],
  "joinConditions": [
    {
      "workflowId": "wf_001",
      "taskId": "task_query_002",
      "taskVersion": "V1",
      "statementIndex": 1,
      "statementId": "wf_001_task_query_002_V1_1",
      "joinIndex": 1,
      "queryBlockPath": "root",
      "joinType": 1,
      "joinTypeName": "INNER_JOIN",
      "joinExpression": "a.id = b.id",
      "directJoinObjects": [
        "ods.a",
        "ods.b"
      ],
      "directJoinColumns": [
        "a.id",
        "b.id"
      ],
      "joinTables": [
        "ods.a",
        "ods.b"
      ],
      "joinColumns": [
        "ods.a.id",
        "ods.b.id"
      ]
    },
    {
      "workflowId": "wf_001",
      "taskId": "task_query_002",
      "taskVersion": "V1",
      "statementIndex": 1,
      "statementId": "wf_001_task_query_002_V1_1",
      "joinIndex": 2,
      "queryBlockPath": "root.where.exists[1]",
      "joinType": 1,
      "joinTypeName": "INNER_JOIN",
      "joinExpression": "c.id = d.id",
      "directJoinObjects": [
        "ods.c",
        "ods.d"
      ],
      "directJoinColumns": [
        "c.id",
        "d.id"
      ],
      "joinTables": [
        "ods.c",
        "ods.d"
      ],
      "joinColumns": [
        "ods.c.id",
        "ods.d.id"
      ]
    }
  ]
}
```

### 需求：Join 条件表达式提取

解析器必须以显式 `JOIN ... ON ...` 子句为解析对象，保留 `ON` 后面的完整条件表达式。

#### 场景：保留完整 ON 条件表达式

- **假设** SQL 为：

```sql
select *
from ods.t1 t1
join ods.t2 t2
  on t1.f1 = t2.f2 and t1.t2 = 10
```

- **并且** 请求开启 Join 条件解析
- **当** SQL 被解析
- **那么** `JoinCondition.joinExpression` 必须为 `t1.f1 = t2.f2 and t1.t2 = 10`
- **并且** 第一版不要求把 `and` 条件拆成多个字段对
- **并且** 第一版不要求判断 `t1.t2 = 10` 是表间关联条件还是 Join 内过滤条件

#### 场景：函数表达式 Join 条件

- **假设** SQL 为：

```sql
select a.log_id, a.biz_trace_id, b.meta_name, b.status
from ods_op_logs a
left join dim_meta_info b
  on split(a.biz_trace_id, '_')[1] = cast(b.meta_id as string)
where a.dt = '20260710'
  and b.dt = '20260710'
```

- **并且** 请求开启 Join 条件解析
- **当** SQL 被解析
- **那么** `JoinCondition.joinTypeName` 必须为 `LEFT_JOIN`
- **并且** `JoinCondition.joinExpression` 必须为 `split(a.biz_trace_id, '_')[1] = cast(b.meta_id as string)`
- **并且** `JoinCondition.directJoinObjects` 必须包含 `default.ods_op_logs` 和 `default.dim_meta_info`
- **并且** `JoinCondition.directJoinColumns` 必须包含 `a.biz_trace_id` 和 `b.meta_id`
- **并且** `JoinCondition.joinColumns` 必须包含 `default.ods_op_logs.biz_trace_id` 和 `default.dim_meta_info.meta_id`
- **并且** `JoinCondition.joinTables` 必须包含 `default.ods_op_logs` 和 `default.dim_meta_info`
- **并且** `where` 条件中的 `a.dt` 和 `b.dt` 不得加入该 Join 条件记录

#### 场景：ON 条件中只有单侧字段

- **假设** SQL 为：

```sql
select *
from ods.a a
join ods.b b
  on b.dt = '20260710'
```

- **并且** 请求开启 Join 条件解析
- **当** SQL 被解析
- **那么** `JoinCondition.joinExpression` 必须为 `b.dt = '20260710'`
- **并且** `JoinCondition.directJoinObjects` 必须包含 `ods.b`
- **并且** `JoinCondition.directJoinColumns` 必须包含 `b.dt`
- **并且** `JoinCondition.joinColumns` 必须包含 `ods.b.dt`
- **并且** `JoinCondition.joinTables` 必须包含 `ods.b`
- **并且** 不得因为 ON 条件只引用单侧字段而丢弃该 Join 条件记录

### 需求：Join 字段和 Join 表去重

`directJoinObjects`、`directJoinColumns`、`joinColumns` 和 `joinTables` 必须按完整名称去重，并保留首次出现顺序。

#### 场景：重复字段只输出一次

- **假设** SQL 为：

```sql
select *
from ods.t1 t1
join ods.t2 t2
  on t1.id = t2.id and t1.id is not null
```

- **并且** 请求开启 Join 条件解析
- **当** SQL 被解析
- **那么** `JoinCondition.directJoinColumns` 中 `t1.id` 只能出现一次
- **并且** `JoinCondition.joinColumns` 中 `ods.t1.id` 只能出现一次
- **并且** `JoinCondition.joinColumns` 中 `ods.t2.id` 只能出现一次
- **并且** `JoinCondition.joinTables` 中 `ods.t1` 和 `ods.t2` 各只能出现一次

### 需求：Join 类型枚举

解析器必须用数字枚举表达 Join 类型，同时允许输出可读名称用于人工核验。

#### 场景：Join 类型枚举值

- **当** Join 类型被解析
- **那么** 枚举值必须遵循：

```text
0 UNKNOWN
1 INNER_JOIN
2 LEFT_JOIN
3 RIGHT_JOIN
4 FULL_JOIN
5 LEFT_SEMI_JOIN
6 LEFT_ANTI_JOIN
7 CROSS_JOIN
```

- **并且** `join` 和 `inner join` 都必须归一为 `INNER_JOIN`
- **并且** `left join` 和 `left outer join` 都必须归一为 `LEFT_JOIN`
- **并且** `right join` 和 `right outer join` 都必须归一为 `RIGHT_JOIN`
- **并且** `full join` 和 `full outer join` 都必须归一为 `FULL_JOIN`

### 需求：字段归属解析和元数据依赖

Join 条件解析必须优先通过表别名解析字段归属。只有字段无法通过别名或限定名前缀确认归属时，才允许按需使用字段元数据辅助判断。

#### 场景：带别名字段不依赖字段元数据

- **假设** SQL 为 `select * from ods.t1 a join ods.t2 b on a.id = b.id`
- **并且** 请求只开启 Join 条件解析
- **当** SQL 被解析
- **那么** 解析器必须通过别名 `a` 和 `b` 解析 `id` 的表归属
- **并且** 不得为了该 Join 条件加载 `ods.t1` 或 `ods.t2` 的字段元数据

#### 场景：未限定字段可按需依赖字段元数据

- **假设** SQL 为 `select * from ods.t1 a join ods.t2 b on id = b.id`
- **并且** 请求开启 Join 条件解析
- **当** SQL 被解析
- **那么** 解析器可以按需加载 `ods.t1` 和 `ods.t2` 的字段元数据来判断未限定字段 `id` 的归属
- **并且** 如果字段归属无法唯一确定，解析器必须保留原始表达式
- **并且** 解析器必须返回字段归属不明确的 warning

#### 场景：CTE 字段无法唯一追溯时保留直接字段

- **假设** SQL 为：

```sql
with x as (
  select *
  from ods.a a
  join ods.b b
    on a.id = b.id
),
y as (
  select id
  from ods.c
)
select *
from x
join y
  on x.id = y.id
```

- **并且** 请求开启 Join 条件解析
- **当** SQL 被解析
- **那么** 主查询 Join 条件的 `joinExpression` 必须保留 `x.id = y.id`
- **并且** 主查询 Join 条件的 `directJoinObjects` 必须包含 `x` 和 `y`
- **并且** 主查询 Join 条件的 `directJoinColumns` 必须包含 `x.id` 和 `y.id`
- **并且** 如果 `x.id` 无法在 CTE `x` 中唯一追溯到 `ods.a.id` 或 `ods.b.id`，解析器不得臆造 `x.id` 的物理字段
- **并且** 主查询 Join 条件的 `joinColumns` 可以只包含已成功追溯的 `ods.c.id`
- **并且** 解析器必须返回 CTE 字段追溯不明确的 warning

### 需求：不支持范围

第一版 Join 条件解析只要求覆盖显式 `JOIN ... ON ...`。

显式 `cross join` 没有 `ON` 条件时，第一版不要求输出 `JoinCondition`。枚举中的 `CROSS_JOIN` 仅作为类型预留，或用于后续支持带条件的方言扩展。

#### 场景：第一版不要求识别隐式 Join

- **假设** SQL 为 `select * from ods.t1 a, ods.t2 b where a.id = b.id`
- **并且** 请求开启 Join 条件解析
- **当** SQL 被解析
- **那么** 第一版不要求把 `where a.id = b.id` 解析为 Join 条件
- **并且** 解析器不得把该 where 条件写入 `joinConditions`

#### 场景：第一版不要求拆分 Join 条件字段对

- **假设** SQL 的 ON 条件为 `a.id = b.id and a.biz_id = b.biz_id`
- **当** Join 条件被解析
- **那么** 第一版不要求输出 `a.id <-> b.id` 和 `a.biz_id <-> b.biz_id` 两组字段对
- **并且** 必须保留完整 `joinExpression`
