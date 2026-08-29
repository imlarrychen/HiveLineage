# Join AST 查询块单次遍历设计

## 背景

`HiveAstJoinExtractor` 当前通过两条递归路径处理嵌套查询：

- 遇到 `TOK_QUERY` 时直接提取当前查询块的 Join，再递归子节点。
- 遇到 `TOK_SUBQUERY` 时取它的直接 `TOK_QUERY`，再次提取和递归。

当 SQL 同时包含 FROM 内联子查询和 WHERE EXISTS 时，同一个内联查询节点可能被两条路径遍历，产生重复甚至虚假的 `ParsedJoinCondition`。作用域数据库绑定能防止错误串库，但不能删除已经产生的虚假 Join。

## 目标

Join AST 提取器必须保证每个实际 `TOK_QUERY` 节点在一次 `extract` 调用中只被处理一次。每个真实 Join 只生成一个结果，其 `queryBlockPath`、`joinIndex`、表和字段与所属查询块一致。

本次不改变 Join 类型识别、表达式反解析、Session 数据库规则、元数据边界或视图/临时表语义。

## 设计选择

在每次 `extract` 中创建请求局部的已访问查询节点集合。集合使用 AST 节点对象身份，而不是 SQL 文本、节点内容或 `queryBlockPath`：

- 两个文本相同但真实独立的子查询不能合并。
- 同一 AST 节点从不同递归路径到达时必须跳过第二次处理。
- 集合仅在单次 `extract` 调用内存活，不放入 extractor 实例字段，因此不引入跨请求状态或并发问题。

Java 实现使用基于对象身份的 Set，例如 `Collections.newSetFromMap(new IdentityHashMap<ASTNode, Boolean>())`。

## 遍历契约

### 顶层与 CTE

- `extract` 先处理每个 CTE 的直接 `TOK_QUERY`，路径为 `cte.<name>`。
- 再处理 root 查询，路径为 `root`。
- 每次进入查询块前先进行 identity 登记；已登记则直接返回，不重复分配路径或 `joinIndex`。

### 嵌套查询

- `TOK_SUBQUERY` 是进入其直接 `TOK_QUERY` 的语义边界。
- 通用子节点递归若再到达同一 `TOK_QUERY`，identity 集合会阻止重复提取。
- WHERE 下的查询使用 `<parent>.where.exists[n]`。
- 非 WHERE 子查询使用 `<parent>.subquery[n]`。
- 子查询编号只在首次成功登记查询节点时消耗；重复路径不得使后续真实子查询编号偏移。

### 结果顺序

Join 结果保持稳定的 AST 遍历顺序，`joinIndex` 仅为真实 Join 递增。不在消费端按字符串、路径或 Join 内容去重。

## 下游防御规则

`HiveJoinConditionAnalyzer` 继续按精确 `queryBlockPath` 使用 scoped alias。对非 root 块找不到 scope 时，不回退到整语句全局 alias。

另外，未能展开到物理关系的逻辑 `directJoinObjects` 不得被当作最终 `joinTables`回退输出。可保留 direct 层证据和 `UNKNOWN_COLUMN_LINEAGE` 告警，但最终物理表/字段列表不得包含裸逻辑别名。

## 错误处理

- AST 节点重复到达不是错误，第二次直接跳过。
- identity 去重不捕获或改写 Hive parser 异常。
- 无法展开的逻辑 Join 按已有 warning 机制降级，但不伪造物理表。
- 数据库解析失败仍由既有语句边界转为 `DATABASE_NOT_RESOLVED`。

## 测试设计

新增永久回归至少包含：

1. FROM 内联子查询 + root Join + WHERE EXISTS Join：只输出三个真实 Join。
2. 三个 Join 分别使用 `root.subquery[1]`、`root`、`root.where.exists[2]` 或与 AST 契约完全一致的稳定路径，且表/字段均为正确完整物理标识。
3. 双 EXISTS、嵌套 EXISTS 和双内联子查询不重复。
4. 两个文本相同但 AST 节点不同的子查询都必须保留。
5. `joinIndex` 连续，不因重复路径出现空洞。
6. 未解析逻辑对象仍可出现在 direct 层，但不得出现在最终 `joinTables/joinColumns`。
7. Task 5 已有 CTE、同 simple-name、双/嵌套 EXISTS、临时表、unknown owner 和表达式原文回归全部通过。

## 范围

本次只修改 AST Join 查询块遍历及最终物理 Join 的防御性回退。不重写 Hive AST parser，不修改查询块 alias 抽取设计，不扩展 Task 6 的视图所属库或跨 `USE` 临时表语义。
