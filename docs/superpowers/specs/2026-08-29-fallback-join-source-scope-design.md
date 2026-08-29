# Join 降级解析的作用域来源设计

## 背景

当 Hive AST 解析失败、但纯查询开启 Join 条件解析时，分析器会走文本降级路径。该路径仍必须遵守 Session 数据库规则：每个物理裸表必须使用语句数据库补全，无可用数据库时返回 `DATABASE_NOT_RESOLVED`。

现有实现使用全局名称集合区分物理表和逻辑别名，并使用词法扫描器查找 `FROM`/`JOIN`。这会产生两类错误：

- 子查询别名与另一物理表同名时，全局排除会误伤物理表。
- `extract(day from event_ts)` 中的 `FROM` 会被误认为表来源关键字。

## 目标

降级 Join 解析应输出带查询块作用域的来源关系，而不是全局表名或别名 Map。数据库校验只处理确认为物理关系的节点。

本次不改变公开 API、正常 AST 解析路径、Join 条件输出格式或 Session `USE` 语义。

## 核心模型

新增 package-private 不可变来源节点，至少包含：

- 规范化后的关系名。
- 查询块路径或稳定的作用域标识。
- 在 SQL 中的起始位置，用于保留每次 occurrence。
- 来源类型：物理关系、CTE 引用、子查询引用或 Session 临时表。

相同名称的两次出现不能合并。例如：

```sql
from (select id from db1.inner_t) shadow
join shadow physical_shadow on 1=1
```

第一个 `shadow` 是子查询别名，第二个 `shadow` 是当前查询块中的物理关系。

## 查询块解析

降级解析器使用轻量 token 流，而不是正则或全局字符串替换。tokenizer 负责：

- 识别关键字、标识符、点、逗号和括号。
- 结构化处理反引号标识符。
- 跳过单/双引号字符串、转义字符、doubled quote、行注释和块注释。
- 保留 token 的字符位置。

查询块解析器递归处理括号内的 `SELECT`/`WITH`，为每个查询块建立独立的 CTE 和子查询别名集合。只有当 `FROM` 位于查询块的来源子句时才开始读取关系；函数参数内的 `FROM`（如 `EXTRACT`）不会进入来源状态。

来源子句支持：

- `FROM relation [alias]`
- `JOIN relation [alias]`
- `FROM relation1, relation2`
- `FROM (subquery) alias`

`WHERE`、`GROUP BY`、`HAVING`、`ORDER BY`、`CLUSTER BY`、`DISTRIBUTE BY`、`SORT BY`、`LIMIT`、`QUALIFY`、`WINDOW`、`UNION` 等查询块边界结束当前来源子句。

## 逻辑关系分类

分类在关系节点所属查询块内完成：

- 名称命中该查询块可见的 CTE，标记为 CTE 引用。
- `FROM (subquery) alias` 的 alias 只在所属查询块内标记为子查询引用。
- 名称命中当前 `ScriptAnalysisState` 临时表集合，标记为临时表。
- 其他关系是物理关系。即使名称与其他作用域的逻辑别名相同，也不全局排除。

## 数据库校验与失败处理

`HiveScriptLineageAnalyzer` 仅遍历物理关系节点，对每次 occurrence 调用 `HivePhysicalNameResolver.qualifyTable`。

- 裸表 + 有 statement database：校验成功。
- 全限定表：不需要 statement database。
- 裸表 + 无 statement database：抛出 `DatabaseResolutionException`，在语句边界转为 `SELECT` / `DATABASE_NOT_RESOLVED`。
- CTE、子查询引用和临时表不执行物理表补库。

不捕获其他 `RuntimeException`，不改变现有失败恢复策略。

## 测试设计

永久回归用例至少包含：

- `ON 1=1` 和只引用单侧字段时，所有关系均被校验。
- 逗号来源和同 simple-name 的多次 occurrence 不丢失。
- CTE/子查询内物理裸表失败，外层逻辑引用不误判。
- 子查询别名与当前块物理表同名时，只校验物理 occurrence。
- `EXTRACT(day FROM event_ts)` 不生成关系节点。
- 字符串、转义引号、行/块注释中的伪 `FROM/JOIN` 不生成关系节点。
- 反引号全限定表、普通全限定表在无默认库时成功。
- Session 临时表不被物理表校验。
- Task 3 的反引号别名、歧义 owner，以及 Task 4 的 `USE`/请求隔离用例全部回归。

## 范围限制

本次只重构 Join-only 降级路径的来源识别和数据库校验。表值函数、LATERAL VIEW 或其他非普通关系语法不在本次扩展范围；遇到无法分类的来源时，不得猜测为已全限定物理表。
