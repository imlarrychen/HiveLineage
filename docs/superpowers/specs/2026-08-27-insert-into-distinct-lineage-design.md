# INSERT INTO SELECT DISTINCT 字段血缘设计

## 背景

对于下面的 SQL：

```sql
insert into whc.c(field1)
select distinct field1 from whc.a;
```

预期只生成 `whc.a.field1 -> whc.c.field1`，当前结果却额外生成
`whc.a.distinct -> whc.c.field1`。

## 根因

Hive Parser 对两类写入语句使用不同的目标节点：

- `INSERT OVERWRITE`：`TOK_DESTINATION`
- `INSERT INTO`：`TOK_INSERT_INTO`

`HiveParserAstAdapter.resolveOutputObject` 当前只从 `TOK_DESTINATION` 读取目标表。
因此 `INSERT INTO` 虽然能正确解析出 `TOK_SELECTDI` 及其中的 `field1`，却无法解析输出表，
不满足完整 AST 血缘结构，随后退回正则解析路径。正则路径把 `select` 后面的
`distinct field1` 整体作为投影表达式，最终将 `distinct` 错当成来源字段。

目标节点修复后，正式 AST 路径还会暴露 `TOK_ALLCOLREF` 的兼容性问题：Hive 将
`a.*` 或 `db.table.*` 表示为带 `TOK_TABNAME` 子节点的限定星号，但原表达式提取器
只返回裸 `*`，丢失 owner。为保持既有 `a.*`/`db.table.*` 行为，带 `TOK_TABNAME`
时需要还原为 `owner.*`；没有 owner 的裸星号仍返回 `*`。这是本次 AST 路径修复所需的
必要兼容修复，不改变其他问题范围。

## 范围

本次从 AST 根因修复所有目标为表的 `INSERT INTO ... SELECT ...`，包括：

- 省略或包含可选 `TABLE` 关键字；
- 显式目标字段列表；
- 普通投影与 `SELECT DISTINCT`；
- 静态分区写入。

本次不处理默认库补全、子查询中的 `SELECT` 误识别、`INSERT VALUES`、目录写入，
也不改变 `DISTINCT` 的血缘类型语义。

## 方案比较

### 方案一：补全 AST 目标节点识别（采用）

同时从 `TOK_DESTINATION` 和 `TOK_INSERT_INTO` 下读取 `TOK_TAB/TOK_TABNAME`。
该方案修复产生回退的根因，保留 AST 已正确提取的投影、分区和别名信息；同时在
`TOK_ALLCOLREF` 带 `TOK_TABNAME` 时恢复 `owner.*`，保留既有限定星号行为，改动集中。

### 方案二：在正则回退路径剥离 DISTINCT

可以消除当前伪字段，但其他 `INSERT INTO` 仍会丢失 AST 信息，而且以后遇到其他
投影修饰符还会出现同类问题，因此不采用。

### 方案三：预处理时改写 INSERT INTO

把 SQL 改写为其他写入形式会改变语句表达，并增加分区和语法兼容风险，因此不采用。

## 设计

### AST 目标表解析

`HiveParserAstAdapter.resolveOutputObject` 在处理 `INSERT_SELECT` 时：

1. 优先读取现有的 `TOK_DESTINATION` 节点，保持 `INSERT OVERWRITE` 行为不变；
2. 未找到时读取 `TOK_INSERT_INTO` 节点；
3. 在选中的目标节点下解析 `TOK_TAB/TOK_TABNAME`，生成目标 `DataObjectRef`；
4. 两种节点均不存在时保持当前无法解析目标的行为，不进行猜测。

### 投影与血缘数据流

目标表解析成功后，`INSERT INTO ... SELECT DISTINCT ...` 继续走完整 AST 路径：

```text
TOK_INSERT_INTO -> whc.c
TOK_SELECTDI/TOK_SELEXPR -> field1
TOK_TABREF -> whc.a
```

已有 `TOK_SELECTDI` 支持只遍历 `TOK_SELEXPR`，因此 `DISTINCT` 不进入投影表达式，
最终只生成 `whc.a.field1 -> whc.c.field1`。

对星号投影，`TOK_ALLCOLREF` 携带 `TOK_TABNAME` 时恢复为 `a.*` 或 `db.table.*`；
无子节点 owner 的裸星号保持为 `*`，从而不改变既有星号展开语义。

### 兼容性与失败处理

- 不改变 `INSERT OVERWRITE`、CTAS、View 的目标解析路径；
- 不修改正则回退逻辑，避免扩大本次修复范围；
- 仅为正式 AST 路径恢复限定星号 owner；裸星号和正则回退行为保持不变；
- 带静态分区的 `TOK_PARTSPEC` 仍由已有逻辑处理；
- AST 确实无法形成完整血缘结构时，继续使用现有回退或失败行为。

## 测试设计

遵循测试先行，至少覆盖：

1. AST 层：`INSERT INTO ... SELECT DISTINCT` 能解析目标表、目标字段和纯净的 `field1` 投影；
2. 端到端：只生成一条 `whc.a.field1 -> whc.c.field1`，来源中不存在 `distinct`；
3. 普通 `INSERT INTO`：确认同样使用完整 AST 路径且血缘正确；
4. 可选 `TABLE` 关键字：确认目标表解析一致；
5. 静态分区：确认目标表及分区字段血缘不回归；
6. `INSERT OVERWRITE`：确认既有 `TOK_DESTINATION` 路径不回归。

专项测试通过后运行相关解析器与字段血缘测试，最后运行完整 Maven 测试集。
