# 问题 1：Session 数据库与默认库解析设计

## 背景

当 Hive SQL 中的物理表没有显式库名时，现有解析结果保留了裸表名，没有使用
`LineageAnalyzeRequest.defaultDatabase` 生成完整的表、字段唯一标识。同时，脚本中的
`USE database` 目前只会被记为忽略语句，不会改变后续语句的数据库上下文。

本设计修复问题 1，使数据库解析与 Hive Session 中按顺序执行脚本的语义一致。

## 目标

- 请求级 `defaultDatabase` 作为 Session 初始数据库。
- 合法的 `USE` 从它出现的位置起更新当前数据库，只影响后续语句。
- 显式 `database.table` 总是优先，不受当前数据库影响。
- 物理表唯一标识统一为 `database.table`，字段唯一标识统一为
  `database.table.column`。
- SQL 解析、元数据查询、表血缘、字段血缘和 Join 结果使用同一个语句级数据库。
- 无法确定裸表所属数据库时，不生成可能错误的部分血缘。

## 非目标

- 不修改纯 `SELECT` 在未开启 Join 解析时的忽略语义。
- 不通过元数据服务验证 `USE` 的数据库是否存在。
- 不改变已有的元数据缺失、视图展开深度、列数不匹配等错误语义。
- 不重新处理问题 2、3、4，不夹带其他新功能或无关重构。

## 现状与根因

当前 `HiveScriptLineageAnalyzer.analyzeScript` 已按分句顺序遍历脚本，但其 Session 状态只包含
Session 变量、临时表和视图缓存。`USE` 由 `ignoredReason` 识别为 `USE_DATABASE`
后直接跳过，不更新任何状态。

AST 适配器创建的 `DataObjectRef` 保留 SQL 中的原始表名。下游元数据查询通过
`TableIdentifier.parse(tableName, cluster, request.defaultDatabase)` 临时补默认库，但输出对象、
别名映射、Join 对象及临时表键仍可能使用裸表名。这使元数据查询与血缘结果的唯一标识
不一致，也无法表达脚本中的数据库切换。

黑盒复现中，对下列脚本：

```sql
-- defaultDatabase = db1
select * from t1 left join db2.t2 on t1.id = db2.t2.id;
use db3;
insert into t4 select * from db1.t1;
```

现有结果中第一条的输入及 Join 表仍为 `t1`，第三条的输出仍为 `t4`。
`USE db3` 仅生成忽略记录。

## 方案选择

采用“语句级数据库上下文 + 统一名称解析器”。

未采用解析前 SQL 文本改写，因为文本扫描容易将 CTE、子查询别名、函数和字段限定符
误认为物理表。也不采用仅在元数据查询和结果输出阶段补库名，因为解析中间过程仍会混用
裸表名与完整表名，可能破坏别名、Join、视图和临时表匹配。

## Session 数据库状态

`ScriptAnalysisState` 增加请求局部的 `currentDatabase`：

1. 进入 `analyzeScript` 时，使用规范化后的 `request.defaultDatabase` 初始化。
2. 每条普通 SQL 开始解析时，将当时的 `currentDatabase` 固化到
   `StatementAnalysisContext.statementDatabase`。
3. 合法 `USE` 在变量替换和标识符规范化后更新 `currentDatabase`，只影响后续语句。
4. 分析器实例不保存请求间状态，因此复用实例时不会串库。

优先级为：

1. 表名中显式指定的数据库。
2. 该语句开始时的 Session `currentDatabase`。
3. 不再隐式回退到 Hive `default` 库；如果仍无法确定，按失败规则处理。

## `USE` 处理

处理顺序为：

1. 去除语句前置注释。
2. 按现有 Session 变量规则替换 `${name}`、`${hiveconf:name}` 或 `${hivevar:name}`。
3. 识别 `USE` 语句，去除库名外层反引号并做标识符规范化。
4. 校验整条语句只包含一个非空库名。
5. 合法时更新 `currentDatabase`，并按现有规则记为 `USE_DATABASE` 忽略语句。

开启 `includeIgnoredStatements` 时，合法 `USE` 继续返回 `statementType=USE`、
`parseStatus=IGNORED`、`skipReason=USE_DATABASE` 的记录。

`USE` 只负责建立脚本语义上的当前库，不调用表元数据 Provider 检查数据库是否存在。
无效 `USE` 将该语句标记为失败，失败码为 `INVALID_USE_DATABASE`，保持
`currentDatabase` 不变，并继续处理后续语句。

## 统一名称解析

新增一个职责单一的名称解析组件，它接收原始对象名和语句级数据库，返回规范化的
物理标识。核心规则为：

- `table` 解析为 `statementDatabase.table`。
- `database.table` 保留显式数据库。
- 反引号、前后空白和大小写按现有 Hive 标识符规则规范化。
- 最终物理表标识始终为 `database.table`。
- 最终物理字段标识始终为 `database.table.column`。

名称解析作用于：

- AST 输出对象和输入对象；
- 源表别名映射的物理表值；
- 表血缘的目标表和源表；
- 字段血缘的目标字段和源字段；
- Join 直接对象、展开后对象及对应字段；
- 元数据查询、视图展开和相关缓存键。

SQL 中的表别名、CTE 名和子查询别名仅用于语义解析，不直接补数据库。在穿透这些逻辑对象
之后，它们指向的真实物理表和字段必须使用完整标识。

元数据查询不再根据请求级 `defaultDatabase` 二次推测。它使用该语句已确定的完整表名，
保证查询的 `TableIdentifier` 与血缘输出完全一致。

## 视图与临时表

视图定义 SQL 是元数据对象的一部分，不应受调用方当前 Session 的后续 `USE` 影响。
视图定义中的裸物理表以视图自身所属数据库作为默认库。

临时表保持现有 Session 级注册与展开语义。解析输入对象时先匹配已注册的临时表，
匹配成功后穿透到其真实来源，不将同名临时表误查为元数据中的永久表。展开后的真实物理来源
使用完整库表名。

## 失败与恢复语义

`defaultDatabase` 为 `null`、空白或去除反引号后为空时，Session 没有初始数据库。

对需要生成正式血缘或 Join 结果的语句，只要有物理裸表无法确定数据库，就将整条语句
标记为失败，失败码为 `DATABASE_NOT_RESOLVED`，失败信息列出首个无法解析的裸表名。
该语句不输出部分表血缘、字段血缘或 Join 结果。

普通纯 `SELECT` 在未开启 Join 解析时继续按 `PURE_QUERY` 忽略，不额外执行数据库失败校验。
开启 Join 解析且需要产出 Join 结果时，适用 `DATABASE_NOT_RESOLVED` 规则。

任何语句失败都不终止整个脚本。失败语句之后的合法 `USE` 可以建立新的当前数据库，
使再后续的裸表语句恢复正常解析。

## 数据流程

1. 分割脚本，按原顺序遍历语句。
2. 处理 `SET` 并更新 Session 变量。
3. 对当前语句完成变量替换。
4. 如果是 `USE`，验证并更新 Session 库，然后进入下一条语句。
5. 如果是普通 SQL，固化语句级数据库并建立 `StatementAnalysisContext`。
6. AST 解析表、字段、别名、CTE、子查询和 Join 结构。
7. 统一名称解析器规范化物理对象；无法确定数据库时失败。
8. 使用已规范化的表名执行元数据查询、视图或临时表展开。
9. 生成表血缘、字段血缘和 Join 结果，并保证所有物理标识完整。

## 用户场景的确定结果

对以下请求：

```text
defaultDatabase = db1
```

```sql
select * from t1 left join db2.t2 on t1.id = db2.t2.id;
use db3;
insert into t4 select * from db1.t1;
```

结果必须满足：

- 第 1 条的输入表和 Join 表为 `db1.t1`、`db2.t2`。
- 第 1 条的 Join 字段为 `db1.t1.id`、`db2.t2.id`。
- 第 2 条有效切换当前库为 `db3`，但自身仍为忽略语句。
- 第 3 条的输出表为 `db3.t4`，输入表为 `db1.t1`。
- 第 3 条的目标字段标识以 `db3.t4.` 开头，源字段标识以 `db1.t1.` 开头。
- 元数据 Provider 实际收到的标识与上述库表归属一致。

## 测试设计

先增加失败测试，再修改生产实现。至少覆盖：

1. 仅使用 `defaultDatabase`：裸输入表、裸输出表及字段都使用完整名称。
2. 仅使用 `USE`：后续裸表使用当前库。
3. `defaultDatabase + USE`：切换前使用初始库，切换后使用新库。
4. 多次 `USE`：每次只影响它之后的语句。
5. 显式 `database.table`：不受默认库或 `USE` 影响。
6. 用户提供的完整组合脚本：验证表、字段和 Join 的完整标识。
7. 元数据 Provider 查询记录：验证元数据查询的库表名与血缘输出一致。
8. `SET` 配合带反引号和变量的 `USE` 语句：先替换变量、去除反引号，再切换数据库。
9. 没有初始库且没有有效 `USE`：包含物理裸表的正式血缘语句返回
   `DATABASE_NOT_RESOLVED`；所有物理表均已显式带库名时仍可正常解析。
10. 无效 `USE`：返回 `INVALID_USE_DATABASE`，当前库不变，后续语句继续解析。
11. 纯 `SELECT` 且未开启 Join 解析：仍按 `PURE_QUERY` 忽略。
12. 开启 `includeIgnoredStatements`：合法 `USE` 继续返回忽略记录。
13. 请求隔离：复用同一分析器实例时，不同请求的 Session 库不串联。
14. CTE、子查询、视图和临时表回归：逻辑别名不被误补库，展开后的物理来源使用完整标识。

测试通过标准为：新增定向测试全部通过，现有相关测试无回归，并且完整
`mvn clean test` 通过。

## 缺陷文档

修复完成时新增问题 1 的 `docs/bugfixes` 文档，记录用户场景、现象、根因链路、优先级规则、
实现方法、错误语义、测试类与测试方法、完整验证结果。

## 完成标准

- 用户提供的三类数据库场景全部满足预期。
- 元数据查询和所有对外血缘对象不再包含无法定位的物理裸表名或裸字段表归属。
- 没有可用数据库时显式失败，不生成部分或猜测血缘。
- 合法和无效 `USE` 的状态转换、计数和后续恢复都有自动化测试。
- CTE、子查询、视图、临时表及已修复的问题 2、3、4 无回归。
- 问题 1 的缺陷记录和测试索引已沉淀到仓库。
