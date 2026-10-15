# Hive SQL Lineage Reference

Hive SQL 数据血缘分析参考

本参考文档说明 Hive SQL 在本项目中的血缘分析语义。各语法条目提供语法形式、独立 SQL 示例、表与字段来源，以及适用条件和限制，可用于查询结果解释、接口接入和 SQL 编写。

| 项目 | 说明 |
| --- | --- |
| 适用引擎 | v2 语义引擎，包括接收 v1 `LineageAnalyzeRequest` 的 `HiveScriptLineageAdapter` 适配入口 |
| SQL 前端 | Hive 4.0.1 |
| 结果口径 | 表血缘、字段血缘、语句状态、来源质量及脚本内目录效果 |
| 文档基准 | 2026-10-15 的项目实现 |

分析器解析脚本并读取调用方提供的元数据，不执行 SQL。语法可解析、目录状态可模拟、数据血缘可完整确认是不同的能力，具体以各条目的“血缘结果”和“限制”为准。

## 目录

| 章节 | 内容 |
| --- | --- |
| [1. 阅读约定](#reference-conventions) | 来源关系、示例元数据、语句状态与质量 |
| [2. 查询结构](#queries) | SELECT、派生表、CTE、JOIN、集合查询、分组、排序、采样与横向视图 |
| [3. 表达式与函数](#expressions) | 字面量、运算符、CASE、CAST、函数、复杂类型、子查询与窗口 |
| [4. 数据写入与查询定义](#writes) | INSERT、分区、多路写入、CTAS、视图、UPDATE、DELETE、MERGE 与 LOAD |
| [5. 数据定义与目录状态](#ddl) | CREATE、ALTER、RENAME、DROP、临时对象与 TRUNCATE |
| [6. 会话与脚本](#commands) | SET、USE、资源与函数声明、元数据命令、变量和错误恢复 |
| [7. 接口与配置](#configuration) | v1 请求适配、解析选项、星号映射和原生能力 |
| [附录 A. 函数索引](#function-directory) | 标量、聚合、窗口及生成器函数 |
| [附录 B. 语法索引](#syntax-index) | 按关键字定位语法条目 |
| [附录 C. 相关资料](#reference-resources) | 接入说明及开发验证资料 |

<a id="reference-conventions"></a>

## 1. 阅读约定

<a id="reading-lineage"></a>

### 1.1 来源关系

关系方向统一为 `来源 -> 目标`。表血缘描述提供数据或参与结果行选择的对象；字段血缘描述目标值所依赖的源字段。筛选依赖与目标值来源分别解释。

**示例**

```sql
INSERT OVERWRITE TABLE dwd.result(id, name)
SELECT u.id, UPPER(u.name)
FROM ods.users u
WHERE u.amount > 0;
```

**血缘结果**

| 层次 | 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- | --- |
| 表 | `ods.users` | `dwd.result` | — | — | 提供写入数据并决定结果行 |
| 字段 | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 直接投影；目标名由 INSERT 列表确定 |
| 字段 | `ods.users.name` | `dwd.result.name` | `DERIVED` | `["upper"]` | UPPER 转换后的值仍来自 name |
| 筛选 | `ods.users.amount` | 结果行集合 | — | — | 参与 WHERE；不自动加入两个投影字段的值来源 |

| 概念 | 解释规则 |
| --- | --- |
| 别名、派生表与 CTE | 在查询作用域内解析名称，字段继续追溯到内层定义和物理来源。 |
| 条件依赖 | WHERE/ON、CASE、窗口定义及 UPDATE/MERGE 条件采用各自的依赖规则，详见对应条目。来源集合中的字段不一定是直接复制值。 |
| 常量与行集 | 常量投影可没有源字段，FROM 表仍决定行数。COUNT(*) 可没有具体字段源，但依赖输入行集。 |
| 中间对象 | 视图和 CTAS 可保留直接对象边界及定义展开后的字段链，直接表来源与展开字段来源可能不同。 |
| 未知来源 | 无法确认列宽、字段归属或函数语义时，结果保留未知部分。未知来源与常量的明确空来源不同。 |
| 多次写入 | 按语句和动作读取贡献。同一目标字段可以有多条记录；MERGE 各动作的来源应分别判断。 |

<a id="example-assumptions"></a>

### 1.2 示例与元数据

每个 `sql` 代码块是独立脚本；块内语句按顺序分析，代码块之间不共享状态。`text` 代码块为语法模板，采用下列记法。

| 记法 | 含义 |
| --- | --- |
| 大写关键字 | SQL 关键字，通常不区分大小写 |
| `<名称>`、小写参数名或中文参数名 | 需要替换的表名、字段名、表达式等内容 |
| `[内容]` | 可选部分 |
| `{选项1 \| 选项2}` | 必选其一；方括号内的竖线表示可选分支 |
| `...` | 省略的查询内容或可重复项目 |

示例假定调用方按下表提供元数据，初始默认库为 `default`。新增表和视图在各示例内声明。

| 已有表 | 列顺序 |
| --- | --- |
| `ods.users`、`ods.users_archive` | `id, name, dept_id, amount, dt, tags, payload` |
| `ods.orders` | `user_id, order_id, amount, status, dt` |
| `ods.departments` | `dept_id, dept_name` |
| `ods.events` | `user_id, kind, dt` |
| `dwd.result`、`dwd.result_archive` | `id, name, amount, dept_name, tag, rn, dt` |
| `ods.complex_users` | `id, tags, attributes, profile`；复杂字段路径还需完整 ARRAY/MAP/STRUCT 类型 |

正文以 `TABLE_AND_COLUMN` 解释结果，包含忽略语句与关联条件，并开启视图展开；临时血缘发布关闭，`failFast=false`。普通 DDL 对后续语句的结构影响要求启用 `CATALOG_SIMULATION`；LOAD 路径来源要求启用 `EXTERNAL_DATA_LINEAGE`。这些能力的接入方式见[接口与配置](#configuration)。

普通列引用依赖列名，星号展开还依赖列顺序。STRUCT 叶字段的精确识别需要完整类型；仅返回列名的 v1 元数据提供器无法补齐这些类型信息。示例中的来源关系以满足这些前提为条件。

<a id="result-status"></a>

### 1.3 语句状态与来源质量

语句状态描述处理结果；来源质量和诊断说明哪些信息已经确认。两者应结合阅读。

| 状态 | 含义 |
| --- | --- |
| `SUCCESS` | 绑定与请求处理完成。标量子查询基数、未知函数等仍可能带来部分质量，应同时读取诊断。 |
| `PARTIAL_SUCCESS` | 已保留可确认的来源，部分结构、字段映射或效果未知。 |
| `IGNORED` | 当前发布口径不生成正式数据写入血缘。纯查询、普通 DDL 和审计命令可能采用此状态；会话或目录效果仍可能影响后续语句。 |
| `FAILED` | 当前语句未完成请求，不提交有效写入或定义。`failFast=false` 时继续处理后续语句。 |

`includeIgnoredStatements=true` 可显示忽略语句的明细。`TABLE_ONLY`、`COLUMN_ONLY` 和 `TABLE_AND_COLUMN` 控制返回的血缘维度；任务属性 `taskVersion("V1")` 不选择分析引擎。

星号采用兼容映射策略时，即使部分字段关系已确认，也可能返回 `CURRENT_API_TARGET_MAPPING` 和部分成功状态，见[星号映射](#configuration-star-mapping)。各语法条目中的局部限制优先于通用状态描述。

<a id="column-lineage-expression-handling"></a>

### 1.4 字段表达式与分类（Column Lineage Expression Handling）

每条字段记录同时描述“哪些字段参与赋值”和“目标值如何形成”。`lineageType` 按值表达式的形态分类，函数名称通过 `functionNames` 展示。字段改名、查询别名和目标列顺序不会单独改变类型。

| `lineageType` | 判定规则 | 示例 |
| --- | --- | --- |
| `DIRECT` | 单个字段的直接传递，包括不改变值的投影与别名 | `u.id AS user_key` |
| `DERIVED` | 运算、函数或 UDF、聚合、窗口、CASE、CAST、集合分支加工等 | `u.amount + 1`、`upper(u.name)`、`count(*)` |
| `CONSTANT` | 字面量赋值、静态分区值，或全部分支均为字面量的集合结果 | `'fixed'`、`NULL`、`PARTITION(dt='20261015')` |

函数或运算的参数全部为常量时，表达式仍是 `DERIVED`，例如 `upper('x')`、`CAST(42 AS BIGINT)` 和 `1 + 2`。它们可以没有源字段；其中函数调用仍须保留 `functionNames`。`COUNT(*)` 依赖输入行集，也属于 `DERIVED`。未知来源的空列表不能解释为常量。

| 字段 | 结果含义 |
| --- | --- |
| `sourceColumns` | 已确认的源字段。常量、COUNT(*) 及部分未知表达式都可能返回空列表，应结合类型与诊断判断。 |
| `sourceObjects` | 表或对象来源；COUNT(*) 等行集依赖可以通过此字段解释。 |
| `lineageType` | `DIRECT`、`DERIVED` 或 `CONSTANT`。 |
| `expression` | 提取的 SQL 表达式或基于已绑定结构生成的可读表达式。字面量使用 `constant(...)`，集合分支可使用 `union_all(...)` 等表示。 |
| `expressionExtracted` | 是否成功提取表达式文本。生成了展示文本并不保证该值为 `true`；文本提取失败不应删除已确认的来源。 |
| `functionNames` | 表达式使用的函数，包括嵌套函数及未知 UDF。窗口定义中的 PARTITION/ORDER 等关键字不作为函数名。 |
| `complexExpression` | 兼容结果中的表达式复杂度标记，供展示加工关系使用。 |

例如 `concat(upper(u.name), CAST(u.id AS STRING))` 应返回 `DERIVED`，同时展示 `concat`、`upper`、`cast`。未知 UDF 保留其函数名称和可确认的参数来源；函数语义不透明时同时保留诊断。集合表达式中的 `union_all`、`union_distinct` 等名称是结构标记，用于说明分支合并。

窗口值所用的分区、排序及窗口帧表达式也属于函数收集范围。例如 `row_number() over(partition by lower(name) order by abs(amount))` 展示 `row_number`、`lower`、`abs`。仅用于外层 ORDER BY 或 HAVING、未参与该目标值的函数不加入它的函数列表。

类型按每条贡献解释。直接读取视图字段时，目标边可以是 `DIRECT`；视图定义中执行的 UPPER 则在定义边上标为 `DERIVED` 并记录函数名。穿过派生表或 CTE 的加工会继续追溯。MERGE 的匹配条件属于条件依赖，不改变字面量赋值或直接复制本身的类型。

映射表的 `lineageType` 和 `functionNames` 以 v2 引擎返回的 v1 兼容 `ColumnLineage` 为准；兼容函数列表保留出现次数，原生 formal 函数列表按名称去重。保留视图边界时，兼容目标边可为 `DIRECT`，原生展开物理值表达式后可为 `DERIVED` 并在目标边显示函数；原生的 `UNKNOWN` 也应与兼容分类分别读取。

本参考文档的字段映射表同时列出 `lineageType` 和 `functionNames`，`—` 表示该行没有相应函数或不属于字段赋值。原生 `ColumnLineageRecord.getDerivation()` 在无法确认时还可返回 `UNKNOWN`；v1 兼容结果以 `DERIVED` 保守表示，并保留质量或诊断。v2 JSON 的 `statements[].columnLineages[]` 同时提供类型、函数名、表达式、提取标记及来源质量。

设计约定见 [Column Lineage Expression Handling](../openspec/changes/add-hive-sql-lineage-analyzer/design.md#column-lineage-expression-handling)。

<a id="query-structures"></a>

<a id="queries"></a>

## 2. 查询结构

查询的表来源包括提供数据及参与记录筛选的物理表；目标字段的来源则沿 SELECT 表达式追溯。表别名、派生表和 CTE 表示查询层次，不增加物理源表。

### 2.1 投影

<a id="query-select"></a>

#### 2.1.1 SELECT、别名、ALL、DISTINCT 与星号

`SELECT` 按投影顺序将查询输出写入显式目标列。`AS` 可以给中间字段改名；改名不会改变其物理来源。`SELECT ALL` 与省略量词的普通 SELECT 具有相同的来源关系；`DISTINCT` 对结果去重，也不会把字段来源换成其他表。

**语法**

```text
INSERT INTO TABLE <目标表> (<目标列1>, <目标列2>, ...)
SELECT [ALL | DISTINCT] <表达式> [AS <输出别名>], ...
FROM <来源表或查询> [<表别名>];

SELECT * ...;          -- 按来源的列顺序展开
SELECT <别名>.* ...;   -- 只展开指定来源
```

下面第一条语句显式使用 ALL；第二条先构造四列中间结果，再展开 `q.*` 并追加三个常量。

**示例**

```sql
INSERT INTO TABLE dwd.result (id)
SELECT ALL id FROM ods.users;

INSERT INTO TABLE dwd.result (id, name, amount, dept_name, tag, rn, dt)
SELECT DISTINCT q.*, 'fixed-tag', 7, '2026-10-14'
FROM (
    SELECT id, name, amount, CAST(dept_id AS STRING) AS dept_name
    FROM ods.users
) q;
```

**血缘结果**

两条语句的表血缘均为 `ods.users -> dwd.result`。

| 语句 | 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- | --- |
| SELECT ALL | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | SELECT ALL 直接投影 |
| DISTINCT q.* | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 星号展开的直接字段 |
| DISTINCT q.* | `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 星号展开的直接字段 |
| DISTINCT q.* | `ods.users.amount` | `dwd.result.amount` | `DIRECT` | — | 星号展开的直接字段 |
| DISTINCT q.* | `ods.users.dept_id` | `dwd.result.dept_name` | `DERIVED` | `["cast"]` | 星号展开后保留 CAST 加工 |
| DISTINCT q.* | 无已确认来源字段 | `dwd.result.tag` | `DERIVED` | — | 语法值为字面量；兼容目标映射仍未完整确认 |
| DISTINCT q.* | 无已确认来源字段 | `dwd.result.rn` | `DERIVED` | — | 语法值为字面量；兼容目标映射仍未完整确认 |
| DISTINCT q.* | 无已确认来源字段 | `dwd.result.dt` | `DERIVED` | — | 语法值为字面量；兼容目标映射仍未完整确认 |

**限制**

星号展开需要已知的来源列顺序。来源 schema 不可用时，分析器会保留未知列宽；不能据此保证每个目标字段都已完成映射。显式目标列也按位置接收输出，不要求名称与来源列相同。

第二条语句在当前兼容映射策略下返回部分成功，并提示目标名称映射不完整；上述字段关系是已确认的来源。三个字面量输出的目标映射仍未知，兼容类型保守记为 `DERIVED`，与普通字面量的 `CONSTANT` 区分。

<a id="query-pure-select"></a>

#### 2.1.2 纯查询

纯 SELECT 可以描述读取哪些表、返回哪些字段，但没有目标表。当前正式血缘发布口径将这类语句记为忽略，不为查询结果虚构一个物理目标。

**语法**

```text
SELECT <字段或表达式>, ...
FROM <来源表> [<别名>]
[WHERE <筛选条件>];
```

**示例**

```sql
SELECT u.id, u.name
FROM ods.users u
WHERE u.amount > 0;
```

**血缘结果**

该查询读取 `ods.users`，返回值分别来自 `ods.users.id`、`ods.users.name`；`amount` 参与筛选。由于没有写入目标，**不发布 `ods.users -> 某个目标表` 的正式写入血缘**。需要持久化目标关系时，可使用 [INSERT SELECT](#write-insert)，或使用 [CTAS](#write-ctas) 创建结果表。

**限制**

纯查询是否显示在返回列表中，还取决于是否包含被忽略语句；这与查询有没有读取来源是两个不同问题。

### 2.2 嵌套与 CTE

<a id="query-derived"></a>

#### 2.2.1 FROM 派生表

派生表用于把过滤、计算和改名拆成多个查询层次。外层引用的是内层输出；分析器沿每一层的字段定义继续追溯，不把 `level_one` 等别名当成物理来源。

**语法**

```text
INSERT OVERWRITE TABLE <目标表> (<目标列>, ...)
SELECT <派生表别名>.<输出列>, ...
FROM (
    SELECT <字段或表达式> AS <输出列>, ...
    FROM <来源>
) <派生表别名>;
```

**示例**

```sql
INSERT OVERWRITE TABLE dwd.result (id, name, amount)
SELECT level_three.user_key, level_three.display_name, level_three.final_amount
FROM (
    SELECT level_two.user_key, level_two.display_name,
           level_two.adjusted_amount + 5 AS final_amount
    FROM (
        SELECT level_one.user_key,
               UPPER(level_one.display_name) AS display_name,
               level_one.base_amount * 2 AS adjusted_amount
        FROM (
            SELECT u.id AS user_key, u.name AS display_name,
                   u.amount AS base_amount
            FROM ods.users u
            WHERE u.amount > 0
        ) level_one
    ) level_two
) level_three;
```

**血缘结果**

表血缘为 `ods.users -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 穿过查询层次后的关系 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | `id -> user_key -> user_key` |
| `ods.users.name` | `dwd.result.name` | `DERIVED` | `["upper"]` | 改名后经 UPPER 加工 |
| `ods.users.amount` | `dwd.result.amount` | `DERIVED` | — | 经过乘法、加法和改名 |

每层别名都在自己的查询范围内解析；相同拼写不会自动使不同层的字段混为同一个来源。建议明确给派生表及加工输出命名，便于阅读和定位字段。

<a id="query-cte"></a>

#### 2.2.2 WITH 与 CTE 列别名

CTE 给一个查询结果命名，适合复用或分阶段加工。后一个 CTE 可以读取前一个 CTE；显式列名列表按位置命名 CTE 的输出，并可覆盖内部 SELECT 的输出名称。

**语法**

```text
WITH <CTE名> [(<输出列名>, ...)] AS (
    SELECT ...
), <另一个CTE名> AS (
    SELECT ... FROM <CTE名>
)
INSERT OVERWRITE TABLE <目标表> (<目标列>, ...)
SELECT ... FROM <另一个CTE名>;
```

**示例**

```sql
WITH named_users (user_key, display_name, gross_amount) AS (
    SELECT u.id, u.name, u.amount * 2
    FROM ods.users u
), renamed_users (final_key, final_name, final_amount) AS (
    SELECT n.user_key, n.display_name, n.gross_amount + 1
    FROM named_users n
)
INSERT OVERWRITE TABLE dwd.result (id, name, amount)
SELECT r.final_key, r.final_name, r.final_amount
FROM renamed_users r;
```

**血缘结果**

表血缘为 `ods.users -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 经两级 CTE 列别名传递 |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 经两级 CTE 列别名传递 |
| `ods.users.amount` | `dwd.result.amount` | `DERIVED` | — | 经乘法、加法及两级改名 |

**限制**

`named_users`、`renamed_users` 只解释查询如何组织，不是新增物理源表。当前不支持递归 CTE：自引用循环会明确失败，不应把它理解成可求值的递归血缘。

<a id="query-scopes"></a>

#### 2.2.3 CTE 作用域与自连接

嵌套查询中的局部 WITH 可以遮蔽外层同名 CTE；离开该局部查询后，外层名称仍指向外层定义。同一个 CTE 也可以使用不同别名连接自身，各次引用分别解析字段。

**语法**

```text
WITH <名称> AS (<外层定义>)
INSERT INTO TABLE <目标表> (<目标列>, ...)
SELECT ...
FROM (
    WITH <同名名称> AS (<局部定义>)
    SELECT ... FROM <同名名称>
) <局部结果别名>
JOIN <名称> <外层引用别名> ON <条件>;

FROM <CTE名> <别名1> JOIN <CTE名> <别名2> ON <条件>
```

**示例**

```sql
WITH scoped_rows AS (
    SELECT o.user_id AS id, o.amount FROM ods.orders o
)
INSERT OVERWRITE TABLE dwd.result (id, name, amount)
SELECT local_rows.id, local_rows.name, outer_rows.amount
FROM (
    WITH scoped_rows AS (
        SELECT a.id, a.name FROM ods.users_archive a
    )
    SELECT s.id, s.name FROM scoped_rows s
) local_rows
JOIN scoped_rows outer_rows ON local_rows.id = outer_rows.id;

WITH user_rows AS (
    SELECT u.id, u.name, u.amount FROM ods.users u
)
INSERT OVERWRITE TABLE dwd.result (id, name, amount)
SELECT first_row.id, second_row.name, first_row.amount + second_row.amount
FROM user_rows first_row
JOIN user_rows second_row ON first_row.id = second_row.id;
```

**血缘结果**

第一条语句的表血缘为 `ods.users_archive, ods.orders -> dwd.result`；第二条为 `ods.users -> dwd.result`。

| 语句 | 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- | --- |
| 局部同名 CTE | `ods.users_archive.id` | `dwd.result.id` | `DIRECT` | — | 局部 scoped_rows 的投影 |
| 局部同名 CTE | `ods.users_archive.name` | `dwd.result.name` | `DIRECT` | — | 局部 scoped_rows 的投影 |
| 局部同名 CTE | `ods.orders.amount` | `dwd.result.amount` | `DIRECT` | — | 外层 scoped_rows 的投影 |
| CTE 自连接 | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | first_row 的投影 |
| CTE 自连接 | `ods.users.name` | `dwd.result.name` | `DIRECT` | — | second_row 的投影 |
| CTE 自连接 | `ods.users.amount` | `dwd.result.amount` | `DERIVED` | — | 两次 CTE 引用的 amount 相加 |

自连接会保留两个读取位置，但最终物理表集合可以去重为一张表；这不意味着只分析了其中一次引用。

### 2.3 连接

<a id="query-inner-left"></a>

#### 2.3.1 INNER JOIN 与 LEFT OUTER JOIN

INNER JOIN 组合满足条件的左右记录。LEFT OUTER JOIN 保留左侧记录，并从右侧补充匹配结果。两者的字段值来源由 SELECT 决定；连接键即使决定了匹配关系，也不会自动成为所有输出字段的值来源。

**语法**

```text
FROM <左来源> [INNER] JOIN <右来源> ON <连接条件>
FROM <左来源> LEFT [OUTER] JOIN <右来源> ON <连接条件>
```

**示例**

```sql
INSERT OVERWRITE TABLE dwd.result (id, name, dept_name)
SELECT q.id, q.name, d.dept_name
FROM (
    SELECT id, name, dept_id FROM ods.users WHERE amount > 0
) q
INNER JOIN ods.departments d ON q.dept_id = d.dept_id;

WITH order_totals AS (
    SELECT user_id, SUM(amount) AS total_amount
    FROM ods.orders WHERE status = 'paid' GROUP BY user_id
)
INSERT INTO TABLE dwd.result (id, amount)
SELECT u.id, o.total_amount
FROM ods.users u
LEFT OUTER JOIN order_totals o ON u.id = o.user_id;
```

**血缘结果**

INNER 语句的表血缘为 `ods.users, ods.departments -> dwd.result`；LEFT OUTER 语句为 `ods.users, ods.orders -> dwd.result`。

| 语句 | 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- | --- |
| INNER | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 左侧派生表投影 |
| INNER | `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 左侧派生表投影 |
| INNER | `ods.departments.dept_name` | `dwd.result.dept_name` | `DIRECT` | — | 右侧部门名称 |
| LEFT OUTER | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 左侧用户字段 |
| LEFT OUTER | `ods.orders.amount` | `dwd.result.amount` | `DERIVED` | `["sum"]` | 右侧 CTE 的 SUM 结果 |

第一条连接条件涉及两侧的 `dept_id`，第二条涉及 `ods.users.id` 和 `ods.orders.user_id`。这些字段属于连接条件的来源；例如 `ods.orders.user_id` 不会因此混入 `dwd.result.amount` 的值来源。LEFT JOIN 可能产生的 NULL 是结果行的行为，不会凭空增加一个物理来源字段。

<a id="query-right-full"></a>

#### 2.3.2 RIGHT OUTER JOIN 与 FULL OUTER JOIN

RIGHT OUTER JOIN 保留右侧记录，FULL OUTER JOIN 保留两侧记录。SELECT 中若用 COALESCE 从两侧选取值，该输出就同时具有两侧字段来源；这与仅从某一侧投影字段不同。

**语法**

```text
FROM <左来源> RIGHT [OUTER] JOIN <右来源> ON <条件>
FROM <左来源> FULL [OUTER] JOIN <右来源> ON <条件>
```

**示例**

```sql
INSERT INTO TABLE dwd.result (id, dept_name)
SELECT COALESCE(q.id, d.dept_id), d.dept_name
FROM (SELECT id, dept_id FROM ods.users) q
RIGHT OUTER JOIN ods.departments d ON q.dept_id = d.dept_id;

WITH current_users AS (SELECT id, name FROM ods.users)
INSERT INTO TABLE dwd.result (id, name)
SELECT COALESCE(u.id, a.id), COALESCE(u.name, a.name)
FROM current_users u
FULL OUTER JOIN ods.users_archive a ON u.id = a.id;
```

**血缘结果**

RIGHT OUTER 语句的表血缘为 `ods.users, ods.departments -> dwd.result`；FULL OUTER 语句为 `ods.users, ods.users_archive -> dwd.result`。

| 语句 | 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- | --- |
| RIGHT OUTER | `ods.users.id, ods.departments.dept_id` | `dwd.result.id` | `DERIVED` | `["coalesce"]` | COALESCE 从两侧取值 |
| RIGHT OUTER | `ods.departments.dept_name` | `dwd.result.dept_name` | `DIRECT` | — | 右侧投影 |
| FULL OUTER | `ods.users.id, ods.users_archive.id` | `dwd.result.id` | `DERIVED` | `["coalesce"]` | COALESCE 从两侧取值 |
| FULL OUTER | `ods.users.name, ods.users_archive.name` | `dwd.result.name` | `DERIVED` | `["coalesce"]` | COALESCE 从两侧取值 |

<a id="query-semi"></a>

#### 2.3.3 LEFT SEMI JOIN

LEFT SEMI JOIN 常用于“左表中存在匹配记录”的筛选。右表是物理读取来源，但该连接的查询输出来自左侧。

**语法**

```text
INSERT INTO TABLE <目标表> (<目标列>, ...)
SELECT <左侧字段>, ...
FROM <左来源> <左别名>
LEFT SEMI JOIN <右来源> <右别名> ON <匹配条件>;
```

**示例**

```sql
INSERT INTO TABLE dwd.result (id, name)
SELECT u.id, u.name
FROM ods.users u
LEFT SEMI JOIN (
    SELECT user_id FROM ods.orders WHERE status = 'paid'
) o ON u.id = o.user_id;
```

**血缘结果**

表血缘为 `ods.users, ods.orders -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 左侧投影 |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 左侧投影 |

连接条件使用 `ods.users.id` 与 `ods.orders.user_id` 判断匹配。

**限制**

右侧 `ods.orders` 只决定用户是否入选。LEFT SEMI JOIN 不向 SELECT 暴露右侧字段；需要输出右侧值时，应使用符合业务语义的普通连接。

<a id="query-cross-comma"></a>

#### 2.3.4 CROSS JOIN 与逗号连接

CROSS JOIN 组合左右来源，不带 ON 匹配条件。逗号 FROM 也可以组合多个来源；若 WHERE 中存在两侧字段条件，分析器还会识别其中的隐式连接关系。

**语法**

```text
FROM <左来源> CROSS JOIN <右来源>
FROM <左来源>, <右来源> WHERE <两侧字段的条件>
```

**示例**

```sql
INSERT INTO TABLE dwd.result (id, dept_name)
SELECT q.id, d.dept_name
FROM (SELECT id FROM ods.users) q
CROSS JOIN ods.departments d;

INSERT INTO TABLE dwd.result (id, rn)
SELECT u.id, o.order_id
FROM (SELECT id FROM ods.users) u,
     (SELECT order_id FROM ods.orders) o
WHERE u.id = o.order_id;
```

**血缘结果**

CROSS 语句的表血缘为 `ods.users, ods.departments -> dwd.result`；逗号连接语句为 `ods.users, ods.orders -> dwd.result`。

| 语句 | 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- | --- |
| CROSS | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 左侧派生表投影 |
| CROSS | `ods.departments.dept_name` | `dwd.result.dept_name` | `DIRECT` | — | 右侧投影 |
| 逗号 + WHERE | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 左侧派生表投影 |
| 逗号 + WHERE | `ods.orders.order_id` | `dwd.result.rn` | `DIRECT` | — | 右侧派生表投影 |

CROSS JOIN 的连接条件集合为空，两侧表来源仍保留。逗号连接的 WHERE 条件用于匹配记录，字段值分别沿各自投影追溯。

<a id="query-on-boundaries"></a>

#### 2.3.5 ON 条件与连接限制

ON 可以包含多个条件，也可以通过子查询判断某条连接记录是否合格。子查询读取的物理表会进入表血缘，即使它没有提供任何投影字段。

**语法**

```text
FROM <左来源> JOIN <右来源>
ON <连接条件>
   AND EXISTS (SELECT ... FROM <第三来源> WHERE <相关条件>)
```

**示例**

```sql
INSERT OVERWRITE TABLE dwd.result (id, name, dept_name)
SELECT u.id, u.name, d.dept_name
FROM ods.users u
JOIN ods.departments d
  ON u.dept_id = d.dept_id
 AND EXISTS (
     SELECT 1
     FROM (
         SELECT e.user_id, e.kind FROM ods.events e
     ) active_events
     WHERE active_events.user_id = u.id
       AND active_events.kind = 'active'
 );
```

**血缘结果**

表血缘为 `ods.users, ods.departments, ods.events -> dwd.result`。`ods.events` 仅参与筛选与条件判断。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 用户字段投影 |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 用户字段投影 |
| `ods.departments.dept_name` | `dwd.result.dept_name` | `DIRECT` | — | 部门字段投影 |

**限制**

此例保留上述来源，同时报告输出结构未知，来源质量为部分确认。

| 连接写法 | 当前边界 |
| --- | --- |
| `LEFT ANTI JOIN` | 当前前端拒绝该写法，语句分析失败。 |
| `JOIN ... USING (cols)` | 可解析，但仅返回部分来源；未完整分析合并列及两侧连接键。 |
| `NATURAL JOIN` | 未实现自然列匹配规则，前端接受行为尚未确认。 |

### 2.4 集合查询

<a id="query-set-operations"></a>

#### 2.4.1 UNION、INTERSECT、EXCEPT 与重复值处理

集合查询按**输出位置**对齐各分支。UNION 合并分支结果；INTERSECT 取共同记录；EXCEPT 按分支差集筛选记录。当前字段血缘保留每个对应位置的分支来源，表明结果对各分支的依赖，包括 EXCEPT 的右分支。

**语法**

```text
<查询1> UNION     [ALL | DISTINCT] <查询2>
<查询1> INTERSECT [ALL | DISTINCT] <查询2>
<查询1> EXCEPT    [ALL | DISTINCT] <查询2>
```

| 量词 | 结果含义 | 来源关系 |
| --- | --- | --- |
| `ALL` | 保留该集合运算对应的重复数语义 | 保留各分支对应位置的来源 |
| `DISTINCT` | 使用去重语义 | 同样保留各分支对应位置的来源 |
| 省略 | 按 DISTINCT 处理 | 同上 |

下面两条完整语句分别展示省略量词的 INTERSECT、EXCEPT；两者均按 DISTINCT 分析。

**示例**

```sql
INSERT INTO dwd.result (id)
SELECT id FROM ods.users
INTERSECT
SELECT id FROM ods.users_archive;

INSERT INTO dwd.result (id)
SELECT id FROM ods.users
EXCEPT
SELECT id FROM ods.users_archive;
```

**血缘结果**

两条语句的表血缘均为 `ods.users, ods.users_archive -> dwd.result`。

| 语句 | 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- | --- |
| INTERSECT | `ods.users.id, ods.users_archive.id` | `dwd.result.id` | `DERIVED` | `["intersect"]` | 集合分支按输出位置对齐 |
| EXCEPT | `ods.users.id, ods.users_archive.id` | `dwd.result.id` | `DERIVED` | `["except"]` | 集合分支按输出位置对齐 |

来源集合相同只表示两分支均参与计算；INTERSECT 和 EXCEPT 的结果语义不同。

ALL 和显式 DISTINCT 也可用于多列派生查询。下面的 EXCEPT ALL 保留两列各自的位置来源：

**示例**

```sql
INSERT INTO TABLE dwd.result (id, name)
SELECT s.id, s.name
FROM (
    SELECT u.id, u.name
    FROM (SELECT id, name FROM ods.users WHERE amount > 0) u
    EXCEPT ALL
    SELECT a.id, a.name
    FROM (SELECT id, name FROM ods.users_archive WHERE amount >= 0) a
) s;
```

**血缘结果**

表血缘为 `ods.users, ods.users_archive -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id, ods.users_archive.id` | `dwd.result.id` | `DERIVED` | `["except_all"]` | 集合分支按输出位置对齐 |
| `ods.users.name, ods.users_archive.name` | `dwd.result.name` | `DERIVED` | `["except_all"]` | 集合分支按输出位置对齐 |

**限制**

分支输出按位置对应，不按列名配对。列数冲突或星号列宽未知时，映射可能不完整。

<a id="query-insert-union-derived"></a>

#### 2.4.2 INSERT OVERWRITE 与 UNION ALL

INSERT OVERWRITE 可以直接接集合查询。首分支、末分支或两边都包含 FROM 子查询时，每个分支分别展开自己的字段，再按位置汇入同一目标列。

**语法**

```text
INSERT OVERWRITE TABLE <目标表> (<目标列1>, <目标列2>, ...)
SELECT <第一分支输出>, ... FROM (<第一分支查询>) <别名1>
UNION ALL
SELECT <第二分支输出>, ... FROM (<第二分支查询>) <别名2>;
```

**示例**

```sql
INSERT OVERWRITE TABLE dwd.result (id, name)
SELECT first_branch.user_key, UPPER(first_branch.display_name)
FROM (
    SELECT u.id AS user_key, u.name AS display_name
    FROM ods.users u
) first_branch
UNION ALL
SELECT last_branch.archive_key, last_branch.latest_name
FROM (
    SELECT inner_branch.id AS archive_key,
           MAX(inner_branch.name) AS latest_name
    FROM (
        SELECT a.id, a.name
        FROM ods.users_archive a
        WHERE a.amount >= 0
    ) inner_branch
    GROUP BY inner_branch.id
) last_branch;
```

**血缘结果**

表血缘为 `ods.users, ods.users_archive -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id, ods.users_archive.id` | `dwd.result.id` | `DERIVED` | `["union_all"]` | 两分支的第 1 个输出 |
| `ods.users.name, ods.users_archive.name` | `dwd.result.name` | `DERIVED` | `["union_all","upper","max"]` | 两分支的第 2 个输出；分别经过 UPPER / MAX |

`user_key` 和 `archive_key` 不需要同名；它们都位于第一列。`first_branch`、`inner_branch`、`last_branch` 都是查询别名，最终物理来源为两张 ods 表。

### 2.5 筛选与分组

<a id="query-where"></a>

#### 2.5.1 WHERE 与相关子查询

WHERE 决定哪些记录进入结果。相关子查询可以引用外层字段；每个引用按其所在范围向外解析。子查询来源进入表血缘，但筛选字段不会自动变成全部输出字段的值来源。

**语法**

```text
INSERT OVERWRITE TABLE <目标表> (<目标列>, ...)
SELECT <投影字段>, ... FROM <来源> <外层别名>
WHERE EXISTS (
    SELECT ... FROM <另一来源> <内层别名>
    WHERE <内层别名>.<字段> = <外层别名>.<字段>
);
```

**示例**

```sql
INSERT OVERWRITE TABLE dwd.result (id, name)
SELECT u.id, u.name
FROM ods.users u
WHERE EXISTS (
    SELECT 1 FROM ods.orders o
    WHERE o.user_id = u.id
      AND EXISTS (
          SELECT 1 FROM ods.events e
          WHERE e.user_id = o.user_id
            AND e.user_id = u.id
            AND e.kind = 'purchase'
      )
);
```

**血缘结果**

表血缘为 `ods.users, ods.orders, ods.events -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 外层用户投影 |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 外层用户投影 |

最内层子查询同时引用直接父层 `o.user_id` 和祖先层 `u.id`。

本例中 `ods.orders` 和 `ods.events` 只参与用户筛选，`dwd.result.name` 的值来自 `ods.users.name`。WHERE 也可以使用 IN、NOT IN、NOT EXISTS；具体规则见[筛选子查询](#expr-filter-subqueries)。

<a id="query-group-having"></a>

#### 2.5.2 GROUP BY 与 HAVING

GROUP BY 指定分组键，聚合表达式提供每组的输出值；HAVING 筛选聚合后的组。某个字段作为分组键或 HAVING 条件，并不意味着它为所有聚合输出提供值。

**语法**

```text
INSERT INTO TABLE <目标表> (<目标列>, ...)
SELECT <分组键>, <聚合表达式>, ...
FROM <来源>
[WHERE <聚合前条件>]
GROUP BY <分组键>, ...
[HAVING <聚合后条件>];
```

**示例**

```sql
INSERT INTO TABLE dwd.result (id, amount, rn)
SELECT q.dept_id, SUM(q.amount), COUNT(DISTINCT q.id)
FROM (
    SELECT id, dept_id, amount FROM ods.users WHERE amount IS NOT NULL
) q
GROUP BY q.dept_id
HAVING SUM(q.amount) > 100;
```

**血缘结果**

表血缘为 `ods.users -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 计算 |
| --- | --- | --- | --- | --- |
| `ods.users.dept_id` | `dwd.result.id` | `DIRECT` | — | 分组键投影 |
| `ods.users.amount` | `dwd.result.amount` | `DERIVED` | `["sum"]` | SUM |
| `ods.users.id` | `dwd.result.rn` | `DERIVED` | `["count"]` | COUNT DISTINCT |

HAVING 也可以包含子查询。下例的 `ods.orders` 用于筛选聚合组，目标金额仍由 `ods.users.amount` 提供。

**示例**

```sql
INSERT OVERWRITE TABLE dwd.result (id, amount)
SELECT u.id, SUM(u.amount) AS total_amount
FROM ods.users u
WHERE u.amount > 0
GROUP BY u.id
HAVING SUM(u.amount) > (
    SELECT AVG(o.amount) FROM ods.orders o WHERE o.status = 'PAID'
);
```

**血缘结果**

表血缘为 `ods.users, ods.orders -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 分组键投影 |
| `ods.users.amount` | `dwd.result.amount` | `DERIVED` | `["sum"]` | SUM 返回值 |

标量子查询的行数仍未确认，结果保留 `SCALAR_CARDINALITY_UNKNOWN`，来源质量为部分确认。

<a id="query-grouping-sets"></a>

#### 2.5.3 ROLLUP、CUBE 与 GROUPING SETS

这三种写法用于生成多个分组层次：ROLLUP 逐层汇总，CUBE 表达分组维度的组合，GROUPING SETS 显式列出要使用的分组集合。它们保留分组字段与聚合参数的来源。

**语法**

```text
GROUP BY <键1>, <键2> WITH ROLLUP
GROUP BY <键1>, <键2> WITH CUBE
GROUP BY <键1>, <键2>
GROUPING SETS ((<键1>, <键2>), (<键1>), (<键2>), ())
```

**示例**

```sql
WITH daily AS (SELECT dept_id, dt, amount FROM ods.users)
INSERT INTO TABLE dwd.result (id, dt, amount)
SELECT dept_id, dt, SUM(amount) FROM daily
GROUP BY dept_id, dt WITH ROLLUP;

INSERT INTO TABLE dwd.result (id, dt, amount)
SELECT q.dept_id, q.dt, SUM(q.amount)
FROM (SELECT dept_id, dt, amount FROM ods.users) q
GROUP BY q.dept_id, q.dt WITH CUBE;

WITH daily AS (SELECT dept_id, dt, amount FROM ods.users)
INSERT INTO TABLE dwd.result (id, dt, amount)
SELECT dept_id, dt, SUM(amount) FROM daily
GROUP BY dept_id, dt
GROUPING SETS ((dept_id, dt), (dept_id), (dt), ());
```

**血缘结果**

三条语句的表血缘均为 `ods.users -> dwd.result`，字段关系均为：

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.dept_id` | `dwd.result.id` | `DIRECT` | — | 分组键 |
| `ods.users.dt` | `dwd.result.dt` | `DIRECT` | — | 分组键 |
| `ods.users.amount` | `dwd.result.amount` | `DERIVED` | `["sum"]` | SUM 参数 |

**限制**

空集合 `()` 表示总汇总组。当前血缘结果描述键和聚合输入的依赖，不单独表达每一种小计行、分组掩码或运行时补 NULL 的完整语义。

### 2.6 排序与采样

<a id="query-order-distribution-limit"></a>

#### 2.6.1 ORDER BY、SORT BY、分发与 LIMIT

ORDER BY 描述结果排序；SORT BY 描述分区内排序，DISTRIBUTE BY 指定分发键，CLUSTER BY 使用聚簇键。它们影响记录的排列或分发，不会把一个未投影排序键自动添加成结果列。LIMIT 限制输出记录数；`LIMIT offset,count` 同时给出偏移和数量。

**语法**

```text
ORDER BY <表达式> [ASC | DESC] [NULLS FIRST | NULLS LAST], ...
SORT BY <表达式> [ASC | DESC] [NULLS FIRST | NULLS LAST], ...
DISTRIBUTE BY <表达式>, ...
CLUSTER BY <表达式>, ...
LIMIT <数量>
LIMIT <偏移>, <数量>
```

**示例**

```sql
INSERT INTO TABLE dwd.result (id, name)
SELECT q.id, q.name
FROM (
    SELECT id, name FROM ods.users
    TABLESAMPLE(BUCKET 1 OUT OF 4 ON id)
) q
ORDER BY q.id DESC NULLS LAST LIMIT 2, 5;

INSERT INTO TABLE dwd.result (id, name)
SELECT id, name FROM ods.users
DISTRIBUTE BY dept_id
SORT BY amount DESC NULLS FIRST
LIMIT 10;

INSERT INTO TABLE dwd.result (id, name)
SELECT id, name FROM ods.users
CLUSTER BY dept_id;
```

**血缘结果**

三条语句的表血缘均为 `ods.users -> dwd.result`。

| 语句 | 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- | --- |
| ORDER BY / LIMIT | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 普通投影；排序和分发不增加值来源 |
| ORDER BY / LIMIT | `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 普通投影；排序和分发不增加值来源 |
| DISTRIBUTE / SORT BY | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 普通投影；排序和分发不增加值来源 |
| DISTRIBUTE / SORT BY | `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 普通投影；排序和分发不增加值来源 |
| CLUSTER BY | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 普通投影；排序和分发不增加值来源 |
| CLUSTER BY | `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 普通投影；排序和分发不增加值来源 |

第二条的 `dept_id`、`amount` 分别参与分发和排序，不进入 `dwd.result.name` 的值来源。

排序键也可以读取 SELECT 未输出的字段；下一例的 amount 仅用于排序，不增加第三个结果列。

**示例**

```sql
INSERT INTO TABLE dwd.result (id, name)
SELECT q.id, q.name
FROM (SELECT id, name, amount FROM ods.users) q
ORDER BY q.amount DESC LIMIT 5;
```

**血缘结果**

表血缘为 `ods.users -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 普通投影；排序和分发不增加值来源 |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 普通投影；排序和分发不增加值来源 |

未输出的 amount 只参与排序。

**限制**

若上述子句中的表达式还包含子查询，其物理来源也会保留，即使子查询计算没有输出到目标列。当前对 DISTRIBUTE BY / CLUSTER BY 主要保留这些表达式依赖，不提供实际分区布局或分发执行的保证。

<a id="query-sampling"></a>

#### 2.6.2 TABLESAMPLE

TABLESAMPLE 从物理来源抽样。无论按桶、百分比、行数还是字节长度描述样本，已投影字段仍来源于该表相同字段；抽样不会产生一个新的物理源表。

**语法**

```text
FROM <来源表> TABLESAMPLE(BUCKET <桶号> OUT OF <桶数> ON <字段或表达式>)
FROM <来源表> TABLESAMPLE(<数值> PERCENT)
FROM <来源表> TABLESAMPLE(<数值> ROWS)
FROM <来源表> TABLESAMPLE(<数值>B)
```

**示例**

```sql
INSERT INTO dwd.result (id)
SELECT id FROM ods.users TABLESAMPLE(BUCKET 1 OUT OF 4 ON id);

INSERT INTO dwd.result (id)
SELECT id FROM ods.users TABLESAMPLE(10 PERCENT);

INSERT INTO dwd.result (id)
SELECT id FROM ods.users TABLESAMPLE(4 ROWS);

INSERT INTO dwd.result (id)
SELECT id FROM ods.users TABLESAMPLE(100B);
```

**血缘结果**

四条语句的表血缘均为 `ods.users -> dwd.result`。

| 语句 | 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- | --- |
| BUCKET | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 抽样表字段投影 |
| PERCENT | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 抽样表字段投影 |
| ROWS | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 抽样表字段投影 |
| BYTE_LENGTH | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 抽样表字段投影 |

**限制**

抽样种类及参数能被保留，但当前不把抽样参数作为独立的正式字段依赖进行完整分析，也不验证样本大小、抽样概率或执行结果。

### 2.7 横向视图

<a id="query-lateral-view"></a>

#### 2.7.1 LATERAL VIEW 与 OUTER

LATERAL VIEW 将函数生成的记录加入当前查询。生成列的血缘回溯到函数参数，而不是把生成器别名当成表。OUTER 用于在生成函数没有输出时保留原记录；生成列仍沿函数参数追溯。

**语法**

```text
FROM <来源> <别名>
LATERAL VIEW [OUTER] <生成函数>(<参数>, ...)
    <生成器别名> AS <生成列1>, <生成列2>, ...
```

下面 posexplode 同时生成位置和元素，两列都回溯 tags：

**示例**

```sql
INSERT INTO TABLE dwd.result (id, rn, tag)
SELECT u.id, p.pos, p.tag
FROM (SELECT id, tags FROM ods.users) u
LATERAL VIEW OUTER posexplode(u.tags) p AS pos, tag;
```

**血缘结果**

表血缘为 `ods.users -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 原始字段投影 |
| `ods.users.tags` | `dwd.result.rn` | `DERIVED` | `["posexplode"]` | posexplode 生成的位置 |
| `ods.users.tags` | `dwd.result.tag` | `DERIVED` | `["posexplode"]` | posexplode 生成的元素 |

生成列还可以穿过 CTE，继续成为下一个生成函数的参数：

**示例**

```sql
WITH tagged AS (
    SELECT u.id, t.tag
    FROM ods.users u
    LATERAL VIEW explode(u.tags) t AS tag
)
INSERT INTO TABLE dwd.result (id, tag)
SELECT q.id, p.part
FROM tagged q
LATERAL VIEW OUTER explode(split(q.tag, ':')) p AS part;
```

**血缘结果**

表血缘仍为 `ods.users -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | CTE 字段投影 |
| `ods.users.tags` | `dwd.result.tag` | `DERIVED` | `["explode","split","explode"]` | 两级生成器与 SPLIT 参数依赖 |

中间的 t.tag、q.tag 和 p.part 沿参数关系继续回溯到 tags；函数列表保留实际出现的生成函数及嵌套调用。

**限制**

explode 和 posexplode 的生成列按上述参数关系追溯。其他生成函数的参数形式、输出类型及逐列精度仍需按具体函数确认；OUTER 的结果只描述来源，不确认运行时补行结果。

<a id="expressions"></a>

## 3. 表达式与函数

表达式的字段来源包括直接取值及参与值选择、计算的字段。普通筛选条件、CASE 条件和窗口键的处理口径不同，各条目分别说明。

### 3.1 字面量与运算

<a id="expr-literals"></a>

#### 3.1.1 字面量与常量

字面量直接提供固定值，可用于标签、批次日期或空值。数字、字符串、布尔值、NULL、DATE 和 TIMESTAMP 不产生来源字段；FROM 表仍决定结果行集。相邻字符串按一个字符串值处理。

**语法**

```text
INSERT INTO 目标表(目标列, ...)
SELECT {数字 | '字符串' | TRUE | FALSE | NULL
       | DATE 'yyyy-MM-dd'
       | TIMESTAMP 'yyyy-MM-dd HH:mm:ss'}, ...
FROM 源表;
```

**示例**

```sql
CREATE TABLE dwd.literal_reference (
    numeric_value BIGINT,
    text_value STRING,
    enabled BOOLEAN,
    disabled BOOLEAN,
    biz_day DATE,
    event_time TIMESTAMP,
    empty_value STRING
);

INSERT INTO dwd.literal_reference (
    numeric_value, text_value, enabled, disabled, biz_day, event_time, empty_value
)
SELECT 42, 'first' 'second', TRUE, FALSE,
       DATE '2026-10-15', TIMESTAMP '2026-10-15 01:02:03', NULL
FROM ods.users;
```

**血缘结果**

表血缘为 `ods.users -> dwd.literal_reference`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| 无来源字段 | `dwd.literal_reference.numeric_value` | `CONSTANT` | — | 数值字面量 42 |
| 无来源字段 | `dwd.literal_reference.text_value` | `CONSTANT` | — | 相邻字符串合并为 firstsecond |
| 无来源字段 | `dwd.literal_reference.enabled` | `CONSTANT` | — | 布尔字面量 TRUE |
| 无来源字段 | `dwd.literal_reference.disabled` | `CONSTANT` | — | 布尔字面量 FALSE |
| 无来源字段 | `dwd.literal_reference.biz_day` | `CONSTANT` | — | DATE 字面量 |
| 无来源字段 | `dwd.literal_reference.event_time` | `CONSTANT` | — | TIMESTAMP 字面量 |
| 无来源字段 | `dwd.literal_reference.empty_value` | `CONSTANT` | — | NULL 字面量 |

七个目标列均有写入映射，来源字段集合为空；相邻字符串的值为 `firstsecond`。

**限制**

同一脚本中新声明的目标 schema 供下一句使用时，需开启 `CATALOG_SIMULATION`；已有表可由元数据提供器直接提供 schema。字面量的来源与类型记录不代替实际写入类型校验。特殊数值和字符串转义形式未确认全部支持。

<a id="expr-operators"></a>

#### 3.1.2 算术与位运算

算术、一元及位运算保留操作数字段的来源。一个表达式引用多个字段时，这些字段共同成为目标字段的来源；常量操作数不增加物理来源字段。

**语法**

```text
算术：a + b、a - b、a * b、a / b、a DIV b、a % b
一元：+a、-a
位运算：a & b、a | b、a ^ b、~a
```

**示例**

```sql
INSERT INTO dwd.result(id, amount, rn)
SELECT x.id + 1,
       -(x.amount * 2 + x.id / 10) % 7,
       (x.id & 3) | (x.id ^ 1)
FROM (SELECT id, amount FROM ods.users) x;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DERIVED` | — | 加常量 |
| `ods.users.amount, ods.users.id` | `dwd.result.amount` | `DERIVED` | — | 乘除、相加、一元负号与取模 |
| `ods.users.id` | `dwd.result.rn` | `DERIVED` | — | 位运算 |

**限制**

分析器追踪操作数依赖，不计算除零、溢出或整除结果类型。二元减法有来源分析规则，但其具体组合尚未全部确认。

<a id="expr-predicates"></a>

#### 3.1.3 比较、逻辑与模式匹配

比较、逻辑和模式匹配表达式可以用于筛选，也可以在 CASE 中选择输出值。CASE 条件引用的字段属于目标值依赖，普通 WHERE 条件则主要决定记录是否入选。

**语法**

```text
比较：=、<=>、<>、!=、<、<=、>、>=
逻辑：AND、OR、NOT
空值：expr IS NULL、expr IS NOT NULL
模式：expr LIKE pattern、expr RLIKE pattern、expr REGEXP pattern
列表：expr [NOT] IN (value, ...)
范围：expr [NOT] BETWEEN lower AND upper
```

**示例**

```sql
INSERT INTO dwd.result(id, rn, tag, name)
SELECT u.id,
       CASE WHEN u.id NOT BETWEEN 1 AND 9 OR u.amount IN (10, 20)
            THEN 1 ELSE 0 END,
       CASE WHEN u.name LIKE 'A%' AND u.name RLIKE '^[A-Z]'
            THEN u.name ELSE '' END,
       CASE WHEN u.name <=> NULL OR u.name REGEXP 'x'
            THEN u.name ELSE 'present' END
FROM ods.users u;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 直接投影 |
| `ods.users.id, ods.users.amount` | `dwd.result.rn` | `DERIVED` | — | CASE 的范围及列表条件 |
| `ods.users.name` | `dwd.result.tag` | `DERIVED` | — | LIKE / RLIKE 条件与值 |
| `ods.users.name` | `dwd.result.name` | `DERIVED` | — | NULL-safe 比较及 REGEXP 条件与值 |

`<=>` 是 NULL-safe 比较；RLIKE 与 REGEXP 均表达模式匹配。`IS NULL`、`IS NOT NULL`、NOT IN 列表及两种不等号也可按操作数追溯。

**限制**

动态模式、ESCAPE、tuple IN 和空列表尚未确认支持。血缘分析不求值 NULL 或成员判断的运行结果。

### 3.2 条件与类型转换

<a id="expr-case-cast"></a>

#### 3.2.1 CASE 与 CAST

CASE 按条件选择值，保留条件和各分支中的字段依赖；简单 CASE 使用操作数匹配 WHEN，搜索 CASE 直接判断 WHEN 条件。CAST 保留输入字段来源，并记录声明的目标类型。

**语法**

```text
CASE WHEN 条件 THEN 值 [WHEN ...] [ELSE 值] END
CASE 操作数 WHEN 匹配值 THEN 值 [WHEN ...] [ELSE 值] END
CAST(表达式 AS 类型)
```

**示例**

```sql
INSERT INTO dwd.result(id, name, amount)
SELECT CAST(u.id AS BIGINT),
       CASE WHEN u.id > 0 AND u.name IS NOT NULL
            THEN u.name ELSE 'anonymous' END,
       CASE o.status WHEN 1 THEN o.amount WHEN 2 THEN -o.amount ELSE 0 END
FROM ods.users u
JOIN ods.orders o ON u.id = o.user_id;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DERIVED` | `["cast"]` | CAST 输入 |
| `ods.users.id, ods.users.name` | `dwd.result.name` | `DERIVED` | — | CASE 条件与分支值 |
| `ods.orders.status, ods.orders.amount` | `dwd.result.amount` | `DERIVED` | — | CASE 操作数与分支值 |

JOIN 键只参与匹配，不自动并入上述所有输出字段。

**限制**

本例在表与字段联合分析时保留 `OUTPUT_SCHEMA_UNKNOWN`，来源质量为部分确认。CAST 可记录 TINYINT、SMALLINT、INT、BIGINT、FLOAT、DOUBLE、DECIMAL(p,s)、BOOLEAN、STRING、CHAR(n)、VARCHAR(n)、BINARY、DATE、TIMESTAMP；搜索 CASE 可以省略 ELSE。运行时转换、长度及精度不在来源确认范围内，复杂类型 CAST 的前端语法尚未确认。

### 3.3 函数

<a id="expr-scalar-functions"></a>

#### 3.3.1 标量函数与嵌套调用

标量函数根据参数产生单个值。嵌套调用逐层保留参数字段依赖，派生表中的改名不改变最终物理来源。

**语法**

```text
函数名(参数, ...)
外层函数(内层函数(字段), CAST(字段 AS 类型), ...)
```

**示例**

```sql
INSERT INTO dwd.result(id, name, amount)
SELECT CAST(u.id AS BIGINT),
       concat(upper(u.name), CAST(u.id AS STRING)),
       coalesce(u.amount, 0)
FROM (SELECT id, name, amount FROM ods.users) u;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DERIVED` | `["cast"]` | CAST |
| `ods.users.name, ods.users.id` | `dwd.result.name` | `DERIVED` | `["concat","upper","cast"]` | UPPER、CAST 后参与 CONCAT |
| `ods.users.amount` | `dwd.result.amount` | `DERIVED` | `["coalesce"]` | COALESCE；常量兜底不增加源字段 |

**限制**

内置函数按其声明的类别和参数依赖规则分析；函数名称可识别不等于所有参数形式、返回类型和执行行为均已确认。名称列表见[函数索引](#function-directory)。

<a id="expr-aggregate-functions"></a>

#### 3.3.2 聚合函数与 COUNT(*)

聚合函数按组计算输出值。SUM、AVG、MIN、MAX 等沿参数字段追溯；COUNT(*) 依赖输入行集，通常没有可列举的单个来源字段。

**语法**

```text
聚合函数([DISTINCT] 表达式)
COUNT(*)
SELECT 分组字段, 聚合函数(...) FROM 源表 GROUP BY 分组字段;
```

**示例**

```sql
INSERT INTO dwd.result(id, amount, rn)
SELECT o.user_id, SUM(DISTINCT o.amount), COUNT(*)
FROM ods.orders o
GROUP BY o.user_id;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.orders.user_id` | `dwd.result.id` | `DIRECT` | — | 分组字段投影 |
| `ods.orders.amount` | `dwd.result.amount` | `DERIVED` | `["sum"]` | SUM DISTINCT |
| `ods.orders` 的行集 | `dwd.result.rn` | `DERIVED` | `["count"]` | COUNT(*)；来源字段集合为空，结果并非常量 |

**限制**

HAVING 主要筛选聚合结果，不自动成为每个聚合值的来源。多参数 COUNT DISTINCT 尚未确认全部支持；函数 FILTER 子句未实现完整归一化。血缘分析不执行去重或聚合计算。

<a id="expr-unknown-udf"></a>

#### 3.3.3 自定义与未知函数

未声明规则的自定义函数仍保留参数中可确认的字段依赖，同时标记函数行为未知。已知参数是可确认的来源，不是完整依赖集合的保证。

**语法**

```text
未知函数(已知字段, ...)
```

**示例**

```sql
INSERT INTO dwd.result(id, amount)
SELECT u.id, business_score(u.amount, u.id)
FROM ods.users u;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 直接投影 |
| `ods.users.amount, ods.users.id` | `dwd.result.amount` | `DERIVED` | `["business_score"]` | business_score 的已知参数 |

**限制**

结果保留 `OPAQUE_FUNCTION`，来源质量为部分确认。CREATE FUNCTION 只作审计，不会自动补充函数参数及返回规则；带库名、零参数或生成多列的未知函数也需保留未知部分。

### 3.4 复杂类型

<a id="expr-complex-paths"></a>

#### 3.4.1 ARRAY、MAP 与 STRUCT 访问

ARRAY 下标和 MAP key 访问保留容器字段来源；STRUCT 路径在元数据包含完整类型时可追溯到叶字段。本例要求 `ods.complex_users` 提供语法框中的类型。

**语法**

```text
id         BIGINT
tags       ARRAY<STRING>
attributes MAP<STRING, STRING>
profile    STRUCT<account:STRUCT<score:BIGINT>>

数组下标：tags[index]
MAP 访问：attributes[key]
STRUCT 路径：profile.account.score
```

**示例**

```sql
INSERT INTO dwd.result(id, tag, name, amount)
SELECT c.id, c.tags[0], c.attributes['label'], c.profile.account.score
FROM ods.complex_users c;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.complex_users.id` | `dwd.result.id` | `DIRECT` | — | 普通字段 |
| `ods.complex_users.tags` | `dwd.result.tag` | `DERIVED` | — | ARRAY 下标访问 |
| `ods.complex_users.attributes` | `dwd.result.name` | `DERIVED` | — | MAP key 访问 |
| `ods.complex_users.profile.account.score` | `dwd.result.amount` | `DIRECT` | — | STRUCT 叶字段 |

**限制**

仅返回列名的 v1 元数据不足以确认 STRUCT 叶路径，可能报告 `UNRESOLVED_FIELD_PATH` 并保留未知映射。array-of-struct、map-of-struct 和函数返回结构体的具体访问组合尚未全部确认，需同时核对类型信息及诊断。

### 3.5 子查询

<a id="expr-scalar-subqueries"></a>

#### 3.5.1 标量子查询

标量子查询提供一个值，其字段来源可来自另一张物理表。相关子查询中的外层引用按查询范围解析；子查询内用于筛选的字段与返回值字段分别追溯。

**语法**

```text
SELECT 外层字段,
       (SELECT 聚合或字段 FROM 内层表 [WHERE 内层条件])
FROM 外层表;
```

**示例**

```sql
INSERT INTO dwd.result(id, name, amount)
SELECT u.id, u.name,
       (SELECT SUM(o.amount)
        FROM ods.orders o
        WHERE o.user_id = u.id AND o.status = 'PAID')
FROM ods.users u;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 外层投影 |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 外层投影 |
| `ods.orders.amount` | `dwd.result.amount` | `DERIVED` | `["sum"]` | 内层 SUM 返回值 |

表血缘为 `ods.users, ods.orders -> dwd.result`。`u.id`、`o.user_id` 和 `o.status` 用于选择求和记录，不增加到目标金额的值来源中。

标量子查询也可以位于 CASE 条件及 CAST 内部。

**示例**

```sql
INSERT OVERWRITE TABLE dwd.result(id, amount)
SELECT u.id,
       CASE WHEN u.amount > (SELECT AVG(o.amount) FROM ods.orders o)
            THEN CAST((SELECT MAX(a.amount) FROM ods.users_archive a) AS DOUBLE)
            ELSE coalesce(u.amount, 0) END
FROM ods.users u;
```

**血缘结果**

表血缘为 `ods.users, ods.orders, ods.users_archive -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 外层投影 |
| `ods.users.amount, ods.orders.amount, ods.users_archive.amount` | `dwd.result.amount` | `DERIVED` | `["avg","cast","max","coalesce"]` | CASE 条件、转换与兜底值 |

目标 amount 的来源分别参与条件及兜底值、CASE 判断和 THEN 返回值。

**限制**

标量子查询还可嵌入函数参数、BETWEEN 界限、IN 列表、下标及窗口表达式。当前不确认运行时恰好一行，即使使用 SUM/MAX 仍保留 `SCALAR_CARDINALITY_UNKNOWN`，来源质量为部分确认。多列输出报告 `SCALAR_SUBQUERY_WIDTH_UNKNOWN`，保留可确认的表来源，不选取某一列作为猜测值。

<a id="expr-hidden-subquery-inputs"></a>

#### 3.5.2 分组、排序与分发中的子查询

分组、排序或分发键中的子查询会增加物理表来源，但其返回值没有投影到目标列时，不增加到该目标列的值来源中。

**语法**

```text
GROUP BY (SELECT ...)
ORDER BY (SELECT ...)
SORT BY (SELECT ...)
DISTRIBUTE BY (SELECT ...)
CLUSTER BY (SELECT ...)
```

**示例**

```sql
INSERT INTO dwd.result(id)
SELECT u.id
FROM ods.users u
ORDER BY (SELECT MAX(e.user_id) FROM ods.events e);
```

**血缘结果**

表血缘为 `ods.users, ods.events -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 外层投影；MAX 仅参与排序 |

GROUP BY、ORDER BY、SORT BY、DISTRIBUTE BY 和 CLUSTER BY 中的子查询输入在仅请求表血缘时也会保留；未用于目标值的 MAX 不进入该目标的函数列表。

**限制**

分发与聚簇只保留键表达式的依赖，不模拟 Hive 分布执行。上述位置与多层相关子查询的全部组合尚未确认支持。

<a id="expr-filter-subqueries"></a>

#### 3.5.3 EXISTS 与 IN 子查询

EXISTS、NOT EXISTS、IN 和 NOT IN 可根据其他表的记录筛选外层数据。筛选表进入表血缘，外层投影字段仍按其值表达式追溯。

**语法**

```text
WHERE [NOT] EXISTS (SELECT ...)
WHERE probe [NOT] IN (SELECT 单列 ...)
```

**示例**

```sql
INSERT OVERWRITE TABLE dwd.result(id, name)
SELECT u.id, u.name
FROM ods.users u
WHERE u.id IN (
    SELECT o.user_id
    FROM ods.orders o
    WHERE o.user_id NOT IN (
        SELECT e.user_id FROM ods.events e WHERE e.kind = 'blocked'
    )
);
```

**血缘结果**

表血缘为 `ods.users, ods.orders, ods.events -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 外层投影；子查询只参与筛选 |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 外层投影；子查询只参与筛选 |

**限制**

该嵌套 IN/NOT IN 形式保留 `SCALAR_CARDINALITY_UNKNOWN`，来源质量为部分确认；上述表来源和字段关系仍然有效。

NOT EXISTS 用于排除存在匹配记录的外层行。

**示例**

```sql
INSERT OVERWRITE TABLE dwd.result(id)
SELECT u.id
FROM ods.users u
WHERE NOT EXISTS (
    SELECT 1 FROM ods.events e
    WHERE e.user_id = u.id AND e.kind = 'blocked'
);
```

**血缘结果**

表血缘为 `ods.users, ods.events -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 外层投影；子查询只参与筛选 |

多层 EXISTS 可以关联直接父层和更外层查询。

**限制**

IN 子查询需要单列输出，多列输出需检查宽度诊断。NULL 的运行时成员判断不由来源分析求值，相关查询的全部作用域组合尚未确认支持。

### 3.6 窗口

<a id="expr-windows"></a>

#### 3.6.1 窗口函数

窗口函数按分组、顺序及窗口范围计算每行的结果。当前字段依赖包含函数参数、PARTITION BY 和 ORDER BY 引用的字段，这与普通 WHERE 仅控制入选记录的处理口径不同。

**语法**

```text
函数(...) OVER (
    [PARTITION BY 表达式, ...]
    [ORDER BY 表达式 [ASC|DESC], ...]
    [{ROWS | RANGE} frame]
)
```

**示例**

```sql
INSERT INTO dwd.result(id, amount, rn)
SELECT q.user_id,
       SUM(q.amount) OVER (
           PARTITION BY q.user_id ORDER BY q.dt
           ROWS BETWEEN 2 PRECEDING AND CURRENT ROW
       ),
       ROW_NUMBER() OVER (
           PARTITION BY q.user_id ORDER BY q.amount DESC
       )
FROM (SELECT user_id, amount, dt FROM ods.orders WHERE status = 'paid') q;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.orders.user_id` | `dwd.result.id` | `DIRECT` | — | 普通投影 |
| `ods.orders.amount, ods.orders.user_id, ods.orders.dt` | `dwd.result.amount` | `DERIVED` | `["sum"]` | 窗口值、分组键和排序键 |
| `ods.orders.user_id, ods.orders.amount` | `dwd.result.rn` | `DERIVED` | `["row_number"]` | 行号的分组键和排序键 |

**限制**

窗口结果及行号不由血缘分析计算。函数名称规则不保证全部参数形式或返回类型，具体类别见[函数索引](#function-directory)。

<a id="expr-named-windows-frames"></a>

#### 3.6.2 命名窗口、继承与窗口帧

命名窗口可供多个表达式复用，派生窗口在基础定义上增加排序和窗口帧。ROWS 使用行偏移；RANGE 按排序值界定范围，两者保留为不同单位。

**语法**

```text
函数(...) OVER 窗口名
WINDOW 基础名 AS (...), 派生名 AS (基础名 ...)

{ROWS | RANGE} BETWEEN 起点 AND 终点
起点/终点：UNBOUNDED PRECEDING、n PRECEDING、CURRENT ROW、n FOLLOWING、UNBOUNDED FOLLOWING
单边示例：ROWS 2 PRECEDING
```

**示例**

```sql
INSERT INTO dwd.result(id, amount)
SELECT id, SUM(amount) OVER w3
FROM ods.users
WINDOW w1 AS (PARTITION BY dept_id),
       w2 AS (w1 ORDER BY amount),
       w3 AS (w2 ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING);

INSERT INTO dwd.result(id, amount)
SELECT id,
       SUM(amount) OVER (
           PARTITION BY dept_id ORDER BY amount
           ROWS BETWEEN 1 FOLLOWING AND 3 FOLLOWING
       )
FROM ods.users;

INSERT INTO dwd.result(id, amount)
SELECT id,
       SUM(amount) OVER (
           PARTITION BY dept_id ORDER BY amount
           RANGE BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING
       )
FROM ods.users;
```

**血缘结果**

三条语句的表血缘均为 `ods.users -> dwd.result`。

| 语句 | 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- | --- |
| 命名窗口 | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 普通投影 |
| 命名窗口 | `ods.users.amount, ods.users.dept_id` | `dwd.result.amount` | `DERIVED` | `["sum"]` | 窗口 SUM、分组键与排序键 |
| ROWS FOLLOWING | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 普通投影 |
| ROWS FOLLOWING | `ods.users.amount, ods.users.dept_id` | `dwd.result.amount` | `DERIVED` | `["sum"]` | 窗口 SUM、分组键与排序键 |
| RANGE | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 普通投影 |
| RANGE | `ods.users.amount, ods.users.dept_id` | `dwd.result.amount` | `DERIVED` | `["sum"]` | 窗口 SUM、分组键与排序键 |

第一句继承三层定义，第二句使用两个 FOLLOWING 偏移，第三句使用 RANGE。单边 `ROWS 2 PRECEDING` 的终点按 CURRENT ROW 记录；继承定义中的标量子查询来源也会保留。

**限制**

未知窗口名和继承循环分别报告 `UNKNOWN_WINDOW`、`RECURSIVE_WINDOW_DEFINITION`，并返回部分结果。有数值偏移的 RANGE、任意端点及窗口定义重写尚未确认全部支持。

### 3.7 时间间隔

<a id="expr-interval"></a>

#### 3.7.1 INTERVAL 时间间隔

INTERVAL 表示时间间隔。当前可分析 DAY 及 YEAR TO MONTH 两种形式，但其区间函数行为和类型信息仍有未知部分。

**语法**

```text
INTERVAL '数量' DAY
INTERVAL '年-月' YEAR TO MONTH
```

**示例**

```sql
INSERT INTO dwd.result(tag, dt)
SELECT INTERVAL '1' DAY, INTERVAL '1-2' YEAR TO MONTH
FROM ods.users;
```

**血缘结果**

表血缘为 `ods.users -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| 无已确认来源字段 | `dwd.result.tag` | `DERIVED` | `["internal_interval"]` | DAY 区间内部函数 |
| 无已确认来源字段 | `dwd.result.dt` | `DERIVED` | `["internal_interval"]` | YEAR TO MONTH 区间内部函数 |

`internal_interval` 是当前分析结果保留的区间内部函数名称；来源字段为空仍属于未知函数加工，而非常量分类。

**限制**

请求字段分析时保留 `OPAQUE_FUNCTION`，来源质量为部分确认；区间类型推断及计算尚未完整支持。DAY TO SECOND、HOUR/MINUTE/SECOND、负区间和日期运算形式尚未确认支持。

<a id="writes"></a>

## 4. 数据写入与查询定义

### 4.1 INSERT

<a id="write-insert"></a>

#### 4.1.1 INSERT INTO / INSERT OVERWRITE

将查询结果追加到目标表，或覆盖目标表数据。两种形式分别保留写入方式与字段映射。

**语法**

```text
INSERT INTO [TABLE] target [(target_columns)] query;
INSERT OVERWRITE TABLE target [(target_columns)] query;
```

**示例**

```sql
INSERT INTO dwd.result (name, id, amount)
SELECT u.name, u.id, u.amount
FROM ods.users u;

INSERT OVERWRITE TABLE dwd.result (amount, name, id)
SELECT a.amount + 1, UPPER(a.name), a.id
FROM ods.users_archive a;
```

**血缘结果**

两条语句分别保留追加与覆盖方式。函数、算术表达式保留输入参数的字段来源。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users` | `dwd.result` | — | — | 第一条：追加 |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 显式目标列第一个位置 |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 显式目标列第二个位置 |
| `ods.users.amount` | `dwd.result.amount` | `DIRECT` | — | 显式目标列第三个位置 |
| `ods.users_archive` | `dwd.result` | — | — | 第二条：覆盖 |
| `ods.users_archive.amount` | `dwd.result.amount` | `DERIVED` | — | 算术表达式的值来源 |
| `ods.users_archive.name` | `dwd.result.name` | `DERIVED` | `["upper"]` | UPPER 的参数来源 |
| `ods.users_archive.id` | `dwd.result.id` | `DIRECT` | — | 第三个查询输出 |

<a id="write-target-order"></a>

显式目标列按声明顺序与查询输出位置对应，目标表元数据列序不改变本次对应关系。

**限制**

省略目标列时，需要可用的目标列结构才能可靠地按位置映射。显式列声明中的重复名称、源目标宽度不等或未知列结构，应结合诊断与映射质量处理。

<a id="write-static-partition"></a>

#### 4.1.2 静态分区

使用 PARTITION 常量声明固定写入分区，业务字段由查询输出提供。

**语法**

```text
INSERT OVERWRITE TABLE target
PARTITION (partition_column = literal) (data_columns)
query;
```

**示例**

```sql
INSERT OVERWRITE TABLE dwd.result
PARTITION (dt = '20261014') (id, name)
SELECT u.id, u.name
FROM ods.users u;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users` | `dwd.result` | — | — | 表级来源 |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 业务字段 |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 业务字段 |
| `'20261014'`（常量） | `dwd.result.dt` | `CONSTANT` | — | 静态分区赋值；无来源列 |

**限制**

静态分区不消耗额外的 SELECT 业务输出。常量分区字段的来源列列表为空；应结合常量表达式理解该结果。

<a id="write-dynamic-partition"></a>

#### 4.1.3 动态分区

从查询末尾输出取得动态分区值，并沿派生表别名追溯来源。

**语法**

```text
INSERT INTO target PARTITION (partition_column) (data_columns)
SELECT data_expressions, partition_expression FROM source;
```

**示例**

```sql
INSERT INTO dwd.result PARTITION (dt) (id, name)
SELECT q.user_key, q.display_name, q.business_day
FROM (
    SELECT u.id AS user_key,
           u.name AS display_name,
           u.dt AS business_day
    FROM ods.users u
) q;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users` | `dwd.result` | — | — | 表级来源 |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 经查询别名 user_key 对应 |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 经查询别名 display_name 对应 |
| `ods.users.dt` | `dwd.result.dt` | `DIRECT` | — | 最后一个查询输出 business_day 提供动态分区值 |

**限制**

动态分区依赖查询输出位置。输出宽度未知时，分区映射也可能未知；应使用诊断和表达式确认对应关系，不能按输出名称猜测尾部位置。

<a id="write-mixed-partition"></a>

#### 4.1.4 混合分区

在同一次写入中同时保存静态分区常量和查询提供的动态分区值。

**语法**

```text
INSERT OVERWRITE TABLE target
PARTITION (static_key = literal, dynamic_key) (data_columns)
SELECT data_expressions, dynamic_expression FROM source;
```

**示例**

```sql
INSERT OVERWRITE TABLE dwd.result
PARTITION (dt = '20261014', tag) (id, name)
SELECT u.id, u.name, u.tags[0]
FROM ods.users u;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users` | `dwd.result` | — | — | 表级来源 |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 业务字段 |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 业务字段 |
| `ods.users.tags` | `dwd.result.tag` | `DERIVED` | — | 数组下标表达式的来源 |
| `'20261014'`（常量） | `dwd.result.dt` | `CONSTANT` | — | 静态分区赋值；无来源列 |

**限制**

静态键与动态键需要分别解释。静态值不消耗动态分区输出，也不应被添加输入表字段来源。

<a id="write-multi-insert"></a>

#### 4.1.5 多路 INSERT

多个写入分支共享一个 FROM 来源，各自保留目标、目标列和追加或覆盖方式。

**语法**

```text
FROM source
INSERT INTO target_a (columns_a) SELECT expressions_a
INSERT OVERWRITE TABLE target_b (columns_b) SELECT expressions_b;
```

**示例**

```sql
-- 此处的新目标表需要开启 CATALOG_SIMULATION，供同脚本后续语句读取 schema。
CREATE TABLE dwd.result_archive (id BIGINT, amount DECIMAL(12,2));

FROM (
    SELECT combined.id, combined.name, combined.amount
    FROM (
        SELECT u.id, u.name, u.amount FROM ods.users u
        UNION ALL
        SELECT a.id, a.name, a.amount FROM ods.users_archive a
    ) combined
) shared
INSERT INTO dwd.result (id, name)
SELECT shared.id, shared.name
INSERT OVERWRITE TABLE dwd.result_archive (id, amount)
SELECT shared.id, shared.amount;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users`、`ods.users_archive` | `dwd.result` | — | — | 第一分支：追加 |
| `ods.users`、`ods.users_archive` | `dwd.result_archive` | — | — | 第二分支：覆盖 |
| `ods.users.id`、`ods.users_archive.id` | `dwd.result.id` | `DERIVED` | `["union_all"]` | 两个分支分别保留字段映射 |
| `ods.users.id`、`ods.users_archive.id` | `dwd.result_archive.id` | `DERIVED` | `["union_all"]` | 两个分支分别保留字段映射 |
| `ods.users.name`、`ods.users_archive.name` | `dwd.result.name` | `DERIVED` | `["union_all"]` | 只写入第一分支 |
| `ods.users.amount`、`ods.users_archive.amount` | `dwd.result_archive.amount` | `DERIVED` | `["union_all"]` | 只写入第二分支 |

**限制**

本例的新目标表由同脚本普通 CREATE 声明，需开启 `CATALOG_SIMULATION` 以提供其列结构。共享查询来源不改变各分支的显式目标列映射。

### 4.2 查询定义（CTAS 与视图）

<a id="write-ctas"></a>

#### 4.2.1 CREATE TABLE AS SELECT

按查询声明新表，并使后续语句能够展开该表的查询来源。

**语法**

```text
CREATE TABLE target [STORED AS format] AS query;
```

**示例**

```sql
CREATE TABLE dwd.statement_order_totals STORED AS PARQUET AS
SELECT totals.user_id AS id, totals.total_amount AS amount
FROM (
    SELECT o.user_id, SUM(o.amount) AS total_amount
    FROM ods.orders o
    WHERE o.status = 'PAID'
    GROUP BY o.user_id
) totals;

INSERT OVERWRITE TABLE dwd.result (id, amount)
SELECT t.id, t.amount
FROM dwd.statement_order_totals t;
```

**血缘结果**

CTAS 生成正式写入血缘，并登记新对象和查询定义。后续 INSERT 的直接来源表是新表；字段结果同时保留中间表字段和展开后的底层来源。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.orders` | `dwd.statement_order_totals` | — | — | 创建语句的表级来源 |
| `dwd.statement_order_totals` | `dwd.result` | — | — | 后续 INSERT 的直接表级来源 |
| `ods.orders.user_id` | `dwd.statement_order_totals.id` | `DIRECT` | — | CTAS 字段映射 |
| `ods.orders.amount` | `dwd.statement_order_totals.amount` | `DERIVED` | `["sum"]` | SUM 参数来源 |
| `dwd.statement_order_totals.id` | `dwd.result.id` | `DIRECT` | — | 直接传递；同时保留 ods.orders.user_id，展开层数不改变 DIRECT |
| `dwd.statement_order_totals.amount` | `dwd.result.amount` | `DERIVED` | `["sum"]` | 沿定义追溯到 SUM；同时保留 ods.orders.amount |

**限制**

只有列声明的普通 CREATE TABLE 不自带查询来源。STORED AS PARQUET 保存格式声明，分析器不读取或写出文件。

<a id="write-temporary-ctas"></a>

#### 4.2.2 临时 CTAS

使用临时表连接多阶段查询转换，供当前脚本后续语句引用。

**语法**

```text
CREATE TEMPORARY TABLE temporary_name AS query;
```

**示例**

```sql
CREATE TEMPORARY TABLE statement_tmp_users AS
SELECT u.id, u.name, u.amount
FROM ods.users u;

CREATE TEMPORARY TABLE statement_tmp_enriched AS
SELECT t.id, UPPER(t.name) AS name, t.amount + 1 AS amount
FROM statement_tmp_users t;

INSERT OVERWRITE TABLE dwd.result (id, name, amount)
SELECT e.id, e.name, e.amount
FROM statement_tmp_enriched e;
```

**血缘结果**

默认不发布临时对象时，最终正式结果直接展开到原始来源。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users` | `dwd.result` | — | — | 临时定义用于下游展开 |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 经过两阶段临时查询 |
| `ods.users.name` | `dwd.result.name` | `DERIVED` | `["upper"]` | 保留 UPPER 参数来源 |
| `ods.users.amount` | `dwd.result.amount` | `DERIVED` | — | 保留算术表达式来源 |

**限制**

默认不发布临时血缘时，两条临时 CTAS 自身忽略正式发布，其定义仍可供下游展开。是否发布临时对象由临时血缘选项控制。

<a id="write-create-view"></a>

#### 4.2.3 CREATE VIEW

保存视图查询，并用声明列名覆盖查询内部输出名。

**语法**

```text
CREATE VIEW [IF NOT EXISTS] view_name
[(column_name [COMMENT 'text'], ...)]
[COMMENT 'text'] [TBLPROPERTIES (...)]
AS query;
```

**示例**

```sql
-- 开启 CATALOG_SIMULATION；先 DROP 使本脚本明确该视图不存在。
DROP VIEW IF EXISTS dim.ddl_alias_view;

CREATE VIEW IF NOT EXISTS dim.ddl_alias_view (
    user_key COMMENT 'view user key',
    display_name COMMENT 'view display name'
)
COMMENT 'aliased nested view'
TBLPROPERTIES ('owner' = 'integration')
AS (
    SELECT q.id, q.name
    FROM (SELECT id, name FROM ods.users) q
);

INSERT INTO dwd.result (id, name)
SELECT v.user_key, v.display_name
FROM (SELECT user_key, display_name FROM dim.ddl_alias_view) v;
```

**血缘结果**

表级链为 `ods.users -> dim.ddl_alias_view -> dwd.result`。视图展开保留定义边界，字段链按各段映射返回。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dim.ddl_alias_view.user_key` | `DIRECT` | — | 按视图声明位置重命名 |
| `ods.users.name` | `dim.ddl_alias_view.display_name` | `DIRECT` | — | 按视图声明位置重命名 |
| `dim.ddl_alias_view.user_key` | `dwd.result.id` | `DIRECT` | — | 下游使用视图声明别名 |
| `dim.ddl_alias_view.display_name` | `dwd.result.name` | `DIRECT` | — | 下游使用视图声明别名 |

**限制**

本例先 DROP 建立已知不存在状态，随后 IF NOT EXISTS 的创建可以确定登记。存在性未知时应保留条件创建。COMMENT 与属性保存为声明内容，不写入 metastore。

<a id="write-replace-view"></a>

#### 4.2.4 CREATE OR REPLACE VIEW

替换当前脚本中的同名视图定义，让后续读取按新查询和新列名分析。

**语法**

```text
CREATE OR REPLACE VIEW view_name [(columns)] [COMMENT 'text'] AS query;
```

**示例**

```sql
CREATE VIEW dim.ddl_replace_view AS
SELECT id, name FROM ods.users;

CREATE OR REPLACE VIEW dim.ddl_replace_view (user_key, display_name)
COMMENT 'replacement definition'
AS
SELECT q.id, q.name
FROM (SELECT id, name FROM ods.users_archive) q;

INSERT INTO dwd.result (id, name)
SELECT user_key, display_name
FROM dim.ddl_replace_view;
```

**血缘结果**

后续读取使用替换后的来源和声明别名，表级链为 `ods.users_archive -> dim.ddl_replace_view -> dwd.result`。

| 语句 | 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- | --- |
| 初始 CREATE | `ods.users.id` | `dim.ddl_replace_view.id` | `DIRECT` | — | 替换前的视图定义 |
| 初始 CREATE | `ods.users.name` | `dim.ddl_replace_view.name` | `DIRECT` | — | 替换前的视图定义 |
| OR REPLACE | `ods.users_archive.id` | `dim.ddl_replace_view.user_key` | `DIRECT` | — | 替换后的视图定义 |
| OR REPLACE | `ods.users_archive.name` | `dim.ddl_replace_view.display_name` | `DIRECT` | — | 替换后的视图定义 |
| INSERT | `dim.ddl_replace_view.user_key` | `dwd.result.id` | `DIRECT` | — | 下游读取新定义 |
| INSERT | `dim.ddl_replace_view.display_name` | `dwd.result.name` | `DIRECT` | — | 下游读取新定义 |

**限制**

此处描述当前脚本中的定义替换，不模拟 Hive 对权限、其他依赖对象或对象类型冲突的执行校验。

<a id="write-alter-view"></a>

#### 4.2.5 ALTER VIEW AS

更新已有视图查询，并使后续嵌套视图使用新的定义。

**语法**

```text
ALTER VIEW view_name AS query;
```

**示例**

```sql
CREATE VIEW dim.statement_user_view AS
SELECT u.id, u.name FROM ods.users u;

ALTER VIEW dim.statement_user_view AS
SELECT a.id, UPPER(a.name) AS name FROM ods.users_archive a;

CREATE VIEW dim.statement_outer_view AS
SELECT x.id, x.name
FROM (SELECT v.id, v.name FROM dim.statement_user_view v) x;

INSERT OVERWRITE TABLE dwd.result (id, name)
SELECT v.id, v.name FROM dim.statement_outer_view v;
```

**血缘结果**

ALTER 后的表级链为 `ods.users_archive -> dim.statement_user_view -> dim.statement_outer_view -> dwd.result`。下表保留两层视图边界及最终写入关系。

| 语句 | 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- | --- |
| 初始 CREATE | `ods.users.id` | `dim.statement_user_view.id` | `DIRECT` | — | ALTER 前的视图定义 |
| 初始 CREATE | `ods.users.name` | `dim.statement_user_view.name` | `DIRECT` | — | ALTER 前的视图定义 |
| ALTER VIEW | `ods.users_archive.id` | `dim.statement_user_view.id` | `DIRECT` | — | 更新后的直接投影 |
| ALTER VIEW | `ods.users_archive.name` | `dim.statement_user_view.name` | `DERIVED` | `["upper"]` | 更新后的 UPPER 参数来源 |
| 外层 CREATE | `dim.statement_user_view.id` | `dim.statement_outer_view.id` | `DIRECT` | — | 第二层视图 |
| 外层 CREATE | `dim.statement_user_view.name` | `dim.statement_outer_view.name` | `DIRECT` | — | 第二层视图 |
| INSERT | `dim.statement_outer_view.id` | `dwd.result.id` | `DIRECT` | — | 最终写入 |
| INSERT | `dim.statement_outer_view.name` | `dwd.result.name` | `DIRECT` | — | 最终写入 |

**限制**

定义展开受视图展开选项和最大深度约束。当前规则不提供其他依赖对象对旧定义的执行级改写保证。

### 4.3 数据修改

<a id="write-update"></a>

#### 4.3.1 UPDATE

分别分析每项 SET 的值来源，以及决定哪些行被更新的条件。

**语法**

```text
UPDATE target SET column = expression [, ...] [WHERE predicate];
```

**示例**

```sql
UPDATE dwd.result
SET amount = amount + 1,
    name = UPPER(name)
WHERE id > 0;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `dwd.result` | `dwd.result` | — | — | 目标表也是读取来源 |
| `dwd.result.amount`、`dwd.result.id` | `dwd.result.amount` | `DERIVED` | — | 前者为更新前的值来源，后者为 WHERE 条件依赖 |
| `dwd.result.name`、`dwd.result.id` | `dwd.result.name` | `DERIVED` | `["upper"]` | 前者为 UPPER 参数来源，后者为 WHERE 条件依赖 |

**限制**

条件字段 `dwd.result.id` 决定更新行集，不表示将其数值赋给 `dwd.result.amount` 或 `dwd.result.name`。读取结果时，应区分值来源与条件依赖。

<a id="write-delete"></a>

#### 4.3.2 DELETE

分析删除操作的目标与行集条件，保留表级读取和删除关系。

**语法**

```text
DELETE FROM target [WHERE predicate];
```

**示例**

```sql
DELETE FROM dwd.result
WHERE amount = 0 AND dt = '20261014';
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `dwd.result` | `dwd.result` | — | — | 表级读取与删除关系；条件使用 dwd.result.amount 和 dwd.result.dt |
| — | — | — | — | DELETE 没有字段赋值边 |

**限制**

DELETE 的空字段赋值列表符合删除语义。它表示行删除，不能按 INSERT/UPDATE 的字段赋值方式补出数据边。

<a id="write-merge"></a>

#### 4.3.3 MERGE

结合匹配条件和动作条件，分别保留更新、删除与新增贡献。

**语法**

```text
MERGE INTO target target_alias
USING source source_alias ON match_condition
WHEN MATCHED [AND condition] THEN UPDATE SET assignments
WHEN MATCHED [AND condition] THEN DELETE
WHEN NOT MATCHED THEN INSERT VALUES (values);
```

**示例**

```sql
MERGE INTO dwd.result t
USING (
    SELECT n.id, n.name, n.amount, n.dt
    FROM (
        SELECT u.id, UPPER(u.name) AS name, u.amount, u.dt
        FROM ods.users u
    ) n
) s ON t.id = s.id
WHEN MATCHED AND s.amount > 0
    THEN UPDATE SET amount = s.amount, name = s.name
WHEN MATCHED AND s.amount = 0
    THEN DELETE
WHEN NOT MATCHED
    THEN INSERT VALUES (s.id, s.name, s.amount, NULL, NULL, 0, s.dt);
```

**血缘结果**

表级来源同时包含 `ods.users` 和匹配检查读取的 `dwd.result`。INSERT 的七个值按目标列序对应；各动作的字段与条件影响分别如下。

| 动作 | 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- | --- |
| UPDATE | `ods.users.amount` | `dwd.result.amount` | `DIRECT` | — | 值来源；附带 dwd.result.id、ods.users.id 匹配条件 |
| UPDATE | `ods.users.name` | `dwd.result.name` | `DERIVED` | `["upper"]` | 值来源；附带 dwd.result.id、ods.users.id 和 ods.users.amount 动作条件 |
| DELETE | `dwd.result`、`ods.users` | `dwd.result` | — | — | 保留匹配与删除条件；没有字段赋值 |
| INSERT | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 值来源；附带 dwd.result.id、ods.users.id 匹配条件 |
| INSERT | `ods.users.dt` | `dwd.result.dt` | `DIRECT` | — | 值来源；附带 dwd.result.id、ods.users.id 匹配条件 |
| INSERT | 独立来源列表为空 | `dwd.result.name` | `DERIVED` | `["upper"]` | UPPER 表达式保留；当前贡献来源不完整 |
| INSERT | 独立来源列表为空 | `dwd.result.amount` | `DERIVED` | — | 字段引用表达式保留；当前贡献来源不完整 |
| INSERT | `NULL`（常量） | `dwd.result.dept_name` | `CONSTANT` | — | 无值来源列；仍附带两个匹配键的条件依赖 |
| INSERT | `NULL`（常量） | `dwd.result.tag` | `CONSTANT` | — | 无值来源列；仍附带两个匹配键的条件依赖 |
| INSERT | `0`（常量） | `dwd.result.rn` | `CONSTANT` | — | 无值来源列；仍附带两个匹配键的条件依赖 |

**限制**

MERGE 中的 VALUES 属于插入动作，与独立 INSERT VALUES 的审计规则不同。匹配键和动作条件可以出现在来源列表中，但类型仍按值表达式判断：直接复制为 `DIRECT`，NULL/0 为 `CONSTANT`；来源未知的 INSERT name/amount 仍保守记为 `DERIVED`，name 保留 `upper`。当前逐项贡献存在限制：INSERT 的 `dwd.result.name`、`dwd.result.amount` 表达式保留，其独立来源列表仍为空；同名 UPDATE 贡献则有来源。合并来源可见 `ods.users.name`、`ods.users.amount`，不能据此认定插入贡献完整。常量目标 `dwd.result.dept_name`、`dwd.result.tag`、`dwd.result.rn` 仍会附带匹配键的条件来源，应结合表达式与动作上下文理解。

### 4.4 外部与特殊写入

<a id="write-values-directories"></a>

#### 4.4.1 VALUES 与 DIRECTORY

识别独立 VALUES 及目录输出语句，并保留脚本审计结果。

**语法**

```text
INSERT INTO target (columns) VALUES (values) [, ...];
INSERT OVERWRITE [LOCAL] DIRECTORY 'path' query;
```

**示例**

```sql
INSERT INTO dwd.result (id, name)
VALUES (1, 'one'), (2, 'two');

INSERT OVERWRITE DIRECTORY '/tmp/v2_statement_export'
SELECT u.id, u.name FROM ods.users u;

INSERT OVERWRITE LOCAL DIRECTORY '/tmp/v2_statement_local_export'
SELECT u.id FROM ods.users u;

INSERT OVERWRITE TABLE dwd.result (id, name)
SELECT u.id, u.name FROM ods.users u;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| — | `dwd.result`（审计对象） | — | — | 独立 VALUES 不发布正式写入血缘 |
| — | `/tmp/v2_statement_export`（审计路径） | — | — | 目录输出不发布外部目标边 |
| — | `/tmp/v2_statement_local_export`（审计路径） | — | — | 本地目录输出不发布外部目标边 |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 最后一条普通 INSERT 正常分析 |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 最后一条普通 INSERT 正常分析 |

**限制**

当前未建立独立 VALUES 写入或目录输出的正式数据血缘。目录路径和 SELECT 来源不能用于补出未发布的外部目标边。

<a id="write-load-data"></a>

#### 4.4.2 LOAD DATA

以 SQL 声明的本地或分布式路径表示外部输入来源。

**语法**

```text
LOAD DATA [LOCAL] INPATH 'path' [OVERWRITE]
INTO TABLE target [PARTITION (key = literal, ...)];
```

**示例**

```sql
LOAD DATA LOCAL INPATH '/tmp/v2_statement_users.tsv'
OVERWRITE INTO TABLE dwd.result PARTITION (dt = '20261014');

LOAD DATA INPATH 'hdfs:///warehouse/v2_statement_users.tsv'
INTO TABLE dwd.result PARTITION (dt = '20261015');
```

**血缘结果**

开启 `EXTERNAL_DATA_LINEAGE` 后，保留以下外部输入关系。文件列结构未知，结果为部分成功，并提示 `EXTERNAL_SCHEMA_UNKNOWN`。业务字段仍发布未知映射：原生类型为 `UNKNOWN`，兼容 `lineageType` 为 `DERIVED`，且无已确认来源字段；该分类不表示已确认存在函数或运算。

| 语句 | 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- | --- |
| 第一条 LOAD | `/tmp/v2_statement_users.tsv` | `dwd.result` | — | — | 本地路径；覆盖写入 |
| 第二条 LOAD | `hdfs:///warehouse/v2_statement_users.tsv` | `dwd.result` | — | — | 分布式路径；追加写入 |
| 两条 LOAD | 无已确认来源字段 | `dwd.result.id` | `DERIVED` | — | 文件列结构未知 |
| 两条 LOAD | 无已确认来源字段 | `dwd.result.name` | `DERIVED` | — | 文件列结构未知 |
| 两条 LOAD | 无已确认来源字段 | `dwd.result.amount` | `DERIVED` | — | 文件列结构未知 |
| 两条 LOAD | 无已确认来源字段 | `dwd.result.dept_name` | `DERIVED` | — | 文件列结构未知 |
| 两条 LOAD | 无已确认来源字段 | `dwd.result.tag` | `DERIVED` | — | 文件列结构未知 |
| 两条 LOAD | 无已确认来源字段 | `dwd.result.rn` | `DERIVED` | — | 文件列结构未知 |
| 两条 LOAD | 声明中的分区常量 | `dwd.result.dt` | `CONSTANT` | — | 第一条为 20261014；第二条为 20261015；均无来源字段 |

**限制**

关闭该能力时语句明确失败，提示 `EXTERNAL_DATA_NOT_REQUESTED`。路径须为可确定的字符串；动态分区会拒绝。分析器不打开、检查或搬运文件，也不推断文件列到 `dwd.result.id`、`dwd.result.name`、`dwd.result.amount` 的映射。

<a id="ddl"></a>

## 5. 数据定义与目录状态

<a id="ddl-catalog-prerequisite"></a>

普通 CREATE、ALTER、RENAME、DROP 和 TRUNCATE 不产生数据字段赋值边。要使这些声明影响同脚本后续对象查找，需显式开启 `CATALOG_SIMULATION`。目录模拟仅用于分析，不执行 Hive DDL；CTAS 和带查询的视图定义按前述查询来源规则处理。

声明被忽略正式血缘发布时，仍可能提供后续需要的列结构或名称影响。目录影响可为确定、条件成立时生效或未知，应结合后续语句状态和映射质量理解。

### 5.1 表声明

<a id="ddl-create-types"></a>

#### 5.1.1 CREATE TABLE 与类型

用列声明为新表提供列序、基础类型和递归复杂类型信息。

**语法**

```text
CREATE [TEMPORARY] [EXTERNAL] TABLE [IF NOT EXISTS] table_name
(column_name type [COMMENT 'text'], ...)
[PARTITIONED BY (partition_columns)] [storage_options] [table_options];
```

**示例**

```sql
CREATE TABLE dwd.ddl_types (
    flag BOOLEAN,
    tiny TINYINT,
    short_value SMALLINT,
    int_value INT,
    long_value BIGINT,
    float_value FLOAT,
    double_value DOUBLE,
    amount DECIMAL(18,4),
    fixed_text CHAR(8),
    variable_text VARCHAR(64),
    text_value STRING,
    binary_value BINARY,
    timestamp_value TIMESTAMP,
    date_value DATE,
    tags ARRAY<STRING>,
    attributes MAP<STRING,ARRAY<DECIMAL(12,2)>>,
    profile STRUCT<city:STRING,account:STRUCT<score:INT,labels:ARRAY<STRING>>>,
    choice UNIONTYPE<INT,STRING,ARRAY<STRUCT<key:STRING,value:DOUBLE>>>
);
```

**血缘结果**

普通 CREATE 没有查询来源，不产生数据写入边。开启目录模拟后，`dwd.ddl_types` 的列结构可供后续语句使用；ARRAY、MAP、STRUCT、UNIONTYPE 的子类型和字段结构可以保留。

**限制**

DECIMAL 精度/小数位和 CHAR/VARCHAR 长度保留在声明语法中，当前语义列结构不完整持久化这些数字参数，也不校验数据值。UNIONTYPE 的声明能力不包含联合值成员访问保证。

<a id="ddl-external-storage"></a>

#### 5.1.2 EXTERNAL 与存储选项

声明外部表的列、分区角色、注释、分桶、排序、分隔格式、位置与属性。

**语法**

```text
CREATE EXTERNAL TABLE [IF NOT EXISTS] table_name (columns)
[COMMENT 'text']
[PARTITIONED BY (partition_columns)]
[CLUSTERED BY (columns) [SORTED BY (column [ASC|DESC], ...)] INTO n BUCKETS]
[ROW FORMAT DELIMITED delimiter_options]
[STORED AS format] [LOCATION 'path'] [TBLPROPERTIES (...)];
```

**示例**

```sql
CREATE EXTERNAL TABLE IF NOT EXISTS dwd.ddl_full_options (
    id BIGINT COMMENT 'user identifier',
    amount DECIMAL(12,2) COMMENT 'order amount'
)
COMMENT 'full DDL options'
PARTITIONED BY (dt STRING COMMENT 'business date', region STRING COMMENT 'region')
CLUSTERED BY (id) SORTED BY (amount DESC, id ASC) INTO 8 BUCKETS
ROW FORMAT DELIMITED
FIELDS TERMINATED BY ',' ESCAPED BY '\\'
COLLECTION ITEMS TERMINATED BY ':'
MAP KEYS TERMINATED BY '='
LINES TERMINATED BY '\n'
NULL DEFINED AS 'NULL'
STORED AS TEXTFILE
LOCATION 'hdfs:///warehouse/ddl_full_options'
TBLPROPERTIES ('owner' = 'integration', 'retention' = '30');
```

**血缘结果**

保存数据列 `dwd.ddl_full_options.id`、`dwd.ddl_full_options.amount` 和尾部分区列 `dwd.ddl_full_options.dt`、`dwd.ddl_full_options.region`，以及注释、分桶、排序、桶数和六项分隔选项。没有来源查询，不产生数据写入边。

支持保存的通用格式名称包括 `TEXTFILE`、`SEQUENCEFILE`、`RCFILE`、`ORC`、`PARQUET`、`AVRO`、`JSONFILE`；分桶声明也可不含 `SORTED BY`。

**限制**

LOCATION 仅表示声明位置，不作为已读取的数据来源。存储声明不执行文件读写、分桶或反序列化；COMMENT 与属性不写入 metastore。本例含 IF NOT EXISTS，存在性未知时不能确定登记成功。

<a id="ddl-serde"></a>

#### 5.1.3 ROW FORMAT SERDE

分别保存 SerDe 类名、SerDe 属性和表属性。

**语法**

```text
CREATE TABLE table_name (columns)
ROW FORMAT SERDE 'class_name' [WITH SERDEPROPERTIES (...)]
[STORED AS format] [TBLPROPERTIES (...)];
```

**示例**

```sql
CREATE TABLE dwd.ddl_serde (id BIGINT, name STRING)
ROW FORMAT SERDE 'example.JsonSerde'
WITH SERDEPROPERTIES ('separator' = '|', 'null.token' = 'NA')
STORED AS ORC
TBLPROPERTIES ('owner' = 'integration');
```

**血缘结果**

SerDe 类名和 `separator`、`null.token` 属性独立于表属性 `owner` 保存。`dwd.ddl_serde` 是普通列声明，没有数据来源或数据写入边。

**限制**

分析器不加载 Java 类、读取配置或运行 SerDe，因此声明本身不能证明自定义数据的字段解析结果。

<a id="ddl-create-like"></a>

#### 5.1.4 CREATE TABLE LIKE

识别模板表名称，并登记 LIKE 声明的新对象。

**语法**

```text
CREATE [EXTERNAL] TABLE table_name LIKE source_table [LOCATION 'path'];
```

**示例**

```sql
CREATE TABLE dwd.ddl_like_known_source LIKE ods.users;

INSERT OVERWRITE TABLE dwd.result (id)
SELECT * FROM dwd.ddl_like_known_source;

CREATE EXTERNAL TABLE dwd.ddl_like_unknown_source
LIKE ods.ddl_missing_source
LOCATION '/warehouse/ddl/like';

INSERT OVERWRITE TABLE dwd.result (id)
SELECT * FROM dwd.ddl_like_unknown_source;
```

**血缘结果**

目录模拟可以登记 LIKE 新对象，但不克隆模板列结构；即使 `ods.users` 的列结构已知，仍提示 `CREATE_LIKE_SCHEMA_UNKNOWN`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `dwd.ddl_like_known_source` | `dwd.result` | — | — | 第一条后续 INSERT 的表级来源；部分成功 |
| `dwd.ddl_like_unknown_source` | `dwd.result` | — | — | 第二条后续 INSERT 的表级来源；部分成功 |
| 字段来源未知 | `dwd.result.id` | `DERIVED` | — | 两次查询均保留空来源列表，不能按模板字段补全 |

**限制**

当前不克隆模板的列、分区或存储结构，LIKE 也不生成模板到新表的数据边。`dwd.result.id` 的空来源列表应结合未知宽度诊断理解，不能据此判断目标字段是常量或按模板列名补全来源。

### 5.2 结构变更

<a id="ddl-add-replace-columns"></a>

#### 5.2.1 ADD / REPLACE COLUMNS

增加或替换整表数据列，同时保持分区键及其尾部顺序。

**语法**

```text
ALTER TABLE table_name ADD COLUMNS (column_definitions);
ALTER TABLE table_name REPLACE COLUMNS (column_definitions);
```

**示例**

```sql
CREATE TABLE dwd.ddl_partition_roles (id BIGINT, name STRING)
PARTITIONED BY (dt STRING, region STRING);

ALTER TABLE dwd.ddl_partition_roles
ADD COLUMNS (amount DECIMAL(12,2));

ALTER TABLE dwd.ddl_partition_roles
REPLACE COLUMNS (id BIGINT, amount DECIMAL(12,2));

ALTER TABLE dwd.ddl_partition_roles
ADD COLUMNS (name STRING);

INSERT OVERWRITE TABLE dwd.result (id, amount, name, dt, tag)
SELECT q.id, q.amount, q.name, q.dt, q.region AS tag
FROM (SELECT * FROM dwd.ddl_partition_roles) q;
```

**血缘结果**

目录模拟保留分区角色及以下列序；各条 ALTER 自身没有数据赋值边。

| 阶段 | 完整列序 | 说明 |
| --- | --- | --- |
| CREATE | `dwd.ddl_partition_roles.id`、`dwd.ddl_partition_roles.name`、`dwd.ddl_partition_roles.dt`、`dwd.ddl_partition_roles.region` | 声明初始结构 |
| ADD | `dwd.ddl_partition_roles.id`、`dwd.ddl_partition_roles.name`、`dwd.ddl_partition_roles.amount`、`dwd.ddl_partition_roles.dt`、`dwd.ddl_partition_roles.region` | 数据列插在分区键前 |
| REPLACE | `dwd.ddl_partition_roles.id`、`dwd.ddl_partition_roles.amount`、`dwd.ddl_partition_roles.dt`、`dwd.ddl_partition_roles.region` | 替换数据列，保留分区键 |
| ADD | `dwd.ddl_partition_roles.id`、`dwd.ddl_partition_roles.amount`、`dwd.ddl_partition_roles.name`、`dwd.ddl_partition_roles.dt`、`dwd.ddl_partition_roles.region` | 追加数据列，分区键仍在尾部 |

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `dwd.ddl_partition_roles.id` | `dwd.result.id` | `DIRECT` | — | 后续 INSERT |
| `dwd.ddl_partition_roles.amount` | `dwd.result.amount` | `DIRECT` | — | 后续 INSERT |
| `dwd.ddl_partition_roles.name` | `dwd.result.name` | `DIRECT` | — | 后续 INSERT |
| `dwd.ddl_partition_roles.dt` | `dwd.result.dt` | `DIRECT` | — | 保留分区字段来源 |
| `dwd.ddl_partition_roles.region` | `dwd.result.tag` | `DIRECT` | — | 显式查询别名映射 |

**限制**

确定变更需要已知列结构；列结构不确定时保留未知影响。REPLACE 的数据列不能与保留分区键重名；按大小写归一后与 `dwd.ddl_partition_roles.dt` 冲突的名称会被拒绝，原列结构保持。分区级变更与 CASCADE/RESTRICT 传播没有确定模拟。

<a id="ddl-change-column"></a>

#### 5.2.2 CHANGE COLUMN

声明列的新名称、类型、注释，以及 FIRST 或 AFTER 位置。

**语法**

```text
ALTER TABLE table_name CHANGE [COLUMN] old_name new_name type
[COMMENT 'text'] [FIRST | AFTER existing_column];
```

**示例**

```sql
CREATE TABLE dwd.ddl_change (id BIGINT, name STRING, amount DOUBLE);

ALTER TABLE dwd.ddl_change
CHANGE COLUMN amount total DECIMAL(12,2)
COMMENT 'renamed total' FIRST;

ALTER TABLE dwd.ddl_change
CHANGE id user_id BIGINT AFTER name;

INSERT OVERWRITE TABLE dwd.result (amount, name, id)
SELECT q.total AS amount, q.name, q.user_id AS id
FROM (SELECT * FROM dwd.ddl_change) q;
```

**血缘结果**

第一次变更后的列序为 `dwd.ddl_change.total`、`dwd.ddl_change.id`、`dwd.ddl_change.name`；第二次为 `dwd.ddl_change.total`、`dwd.ddl_change.name`、`dwd.ddl_change.user_id`。ALTER 不产生数据赋值边，后续 INSERT 使用以下映射。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `dwd.ddl_change.total` | `dwd.result.amount` | `DIRECT` | — | 改名与 FIRST 后的列 |
| `dwd.ddl_change.name` | `dwd.result.name` | `DIRECT` | — | 原列 |
| `dwd.ddl_change.user_id` | `dwd.result.id` | `DIRECT` | — | 改名与 AFTER 后的列 |

**限制**

需要已知整表列结构。`dwd.ddl_change.id` 变更后不再是有效列名；重名、缺失的 AFTER 列、修改分区键或将数据列放在分区键后都会被拒绝，失败不提交新列结构。不执行数据类型转换或持久化 COMMENT。

### 5.3 名称与生命周期

<a id="ddl-rename-table"></a>

#### 5.3.1 ALTER TABLE RENAME

让同脚本后续 ALTER、读取和 DROP 按新名称定位同一对象。

**语法**

```text
ALTER TABLE old_name RENAME TO new_name;
```

**示例**

```sql
CREATE TABLE dwd.ddl_rename_a (id BIGINT, name STRING);
ALTER TABLE dwd.ddl_rename_a RENAME TO dwd.ddl_rename_b;
ALTER TABLE dwd.ddl_rename_b RENAME TO dwd.ddl_rename_c;
ALTER TABLE dwd.ddl_rename_c ADD COLUMNS (amount DOUBLE);

INSERT OVERWRITE TABLE dwd.result (id, name, amount)
SELECT q.id, q.name, q.amount
FROM (SELECT * FROM dwd.ddl_rename_c) q;

DROP TABLE dwd.ddl_rename_c PURGE;
```

**血缘结果**

连续改名仍追踪同一对象，RENAME 和 DROP 不新增数据边。后续 INSERT 使用新名称。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `dwd.ddl_rename_c` | `dwd.result` | — | — | 表级来源 |
| `dwd.ddl_rename_c.id` | `dwd.result.id` | `DIRECT` | — | 字段映射 |
| `dwd.ddl_rename_c.name` | `dwd.result.name` | `DIRECT` | — | 字段映射 |
| `dwd.ddl_rename_c.amount` | `dwd.result.amount` | `DIRECT` | — | 字段映射 |

DROP 后继续读取 `dwd.ddl_rename_c` 会报告对象不存在。

**限制**

PURGE 的语法可识别，但不模拟文件删除或回收站。对象已经存在的未知状态不会因改名消除。

<a id="ddl-rename-view"></a>

#### 5.3.2 ALTER VIEW RENAME

视图改名后保留查询定义，并使用新名称读取或删除视图。

**语法**

```text
ALTER VIEW old_name RENAME TO new_name;
DROP VIEW [IF EXISTS] view_name;
```

**示例**

```sql
CREATE VIEW dwd.ddl_view_a (id, name) AS
SELECT id, name FROM ods.users;

ALTER VIEW dwd.ddl_view_a RENAME TO dwd.ddl_view_b;

INSERT OVERWRITE TABLE dwd.result (id, name)
SELECT id, name FROM dwd.ddl_view_b;

DROP VIEW IF EXISTS dwd.ddl_view_b;
```

**血缘结果**

| 语句 | 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- | --- |
| CREATE VIEW | `ods.users.id` | `dwd.ddl_view_a.id` | `DIRECT` | — | 改名前登记的视图定义 |
| CREATE VIEW | `ods.users.name` | `dwd.ddl_view_a.name` | `DIRECT` | — | 改名前登记的视图定义 |
| 改名后 INSERT | `ods.users.id` | `dwd.ddl_view_b.id` | `DIRECT` | — | 使用新名称展开原定义 |
| 改名后 INSERT | `ods.users.name` | `dwd.ddl_view_b.name` | `DIRECT` | — | 使用新名称展开原定义 |
| 改名后 INSERT | `dwd.ddl_view_b.id` | `dwd.result.id` | `DIRECT` | — | 下游读取新名 |
| 改名后 INSERT | `dwd.ddl_view_b.name` | `dwd.result.name` | `DIRECT` | — | 下游读取新名 |

RENAME 和 DROP 不新增数据写入边；DROP 后不能继续读取 `dwd.ddl_view_b`。

**限制**

保留被改名视图自身的定义，不提供其他已有视图对旧名引用的自动改写保证。

<a id="ddl-drop-if"></a>

#### 5.3.3 IF 条件与 DROP

按已知存在、已知不存在或存在性未知，判断条件声明对后续对象状态的影响。

**语法**

```text
CREATE TABLE IF NOT EXISTS table_name (columns);
DROP TABLE [IF EXISTS] table_name [PURGE];
DROP VIEW [IF EXISTS] view_name;
```

**示例**

```sql
CREATE TABLE dwd.ddl_if_known (id BIGINT);
CREATE TABLE IF NOT EXISTS dwd.ddl_if_known (name STRING);

INSERT OVERWRITE TABLE dwd.result (id)
SELECT q.id FROM (SELECT * FROM dwd.ddl_if_known) q;

DROP TABLE IF EXISTS dwd.ddl_if_known;
CREATE TABLE IF NOT EXISTS dwd.ddl_if_known (name STRING);

INSERT OVERWRITE TABLE dwd.result (name)
SELECT q.name FROM (SELECT * FROM dwd.ddl_if_known) q;
```

**血缘结果**

第二条 CREATE 因对象已存在而不替换原列。DROP 建立已知不存在状态，随后 CREATE 确定登记新列结构；声明本身不产生数据边。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `dwd.ddl_if_known.id` | `dwd.result.id` | `DIRECT` | — | DROP 前的 INSERT 仍读取原列 |
| `dwd.ddl_if_known.name` | `dwd.result.name` | `DIRECT` | — | DROP 后重建，INSERT 读取新列 |

| 对象状态 | 目录影响 | 说明 |
| --- | --- | --- |
| 已知存在 | 不替换原列结构 | IF NOT EXISTS 为空操作 |
| 已知不存在 | 可以确定登记新声明 | 本例由先前 DROP 建立 |
| 存在性未知 | 保留条件创建 | 后续不能假定新列结构已生效 |

**限制**

非权威外部元数据未找到对象不能证明其不存在。IF NOT EXISTS 在存在性未知时保留条件创建，后续不能假定新列结构已生效。DROP IF EXISTS 保留对象存在时删除的前提，不证明实际执行过删除。

<a id="ddl-temporary-shadow"></a>

#### 5.3.4 临时表遮蔽

当前脚本中的临时表优先于同名永久表被定位，ALTER 与 DROP 沿用该优先级。

**语法**

```text
CREATE TEMPORARY TABLE name (columns);
ALTER TABLE name ADD COLUMNS (...);
DROP TABLE name;
```

**示例**

```sql
CREATE TABLE default.ddl_shadow (id BIGINT, name STRING);
CREATE TEMPORARY TABLE ddl_shadow (id BIGINT);
ALTER TABLE ddl_shadow ADD COLUMNS (name STRING);

INSERT OVERWRITE TABLE dwd.result (id, name)
SELECT q.id, q.name FROM (SELECT * FROM ddl_shadow) q;

DROP TABLE ddl_shadow;

INSERT OVERWRITE TABLE dwd.result (id, name)
SELECT q.id, q.name FROM (SELECT * FROM default.ddl_shadow) q;
```

**血缘结果**

第一次 INSERT 优先读取临时对象；DROP 删除临时对象后，第二次 INSERT 可读取仍存在的永久表。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `〈临时命名空间〉.ddl_shadow.id` | `dwd.result.id` | `DIRECT` | — | 第一次 INSERT |
| `〈临时命名空间〉.ddl_shadow.name` | `dwd.result.name` | `DIRECT` | — | 第一次 INSERT |
| `default.ddl_shadow.id` | `dwd.result.id` | `DIRECT` | — | DROP 临时对象后的第二次 INSERT |
| `default.ddl_shadow.name` | `dwd.result.name` | `DIRECT` | — | DROP 临时对象后的第二次 INSERT |

临时命名空间由当前脚本隔离，用于区分同名永久对象。

**限制**

临时与永久声明均不自带数据来源。相同表名不能用于合并两种对象的身份或来源；临时血缘发布规则与临时对象查找规则应分别理解。

<a id="ddl-temporary-rename"></a>

#### 5.3.5 临时表重命名

临时表改名后，用新名称继续 ALTER、读取与 DROP。

**语法**

```text
ALTER TABLE temporary_old_name RENAME TO temporary_new_name;
```

**示例**

```sql
DROP TABLE IF EXISTS default.ddl_temp_new;
CREATE TEMPORARY TABLE ddl_temp_old (id BIGINT);
ALTER TABLE ddl_temp_old RENAME TO ddl_temp_new;
ALTER TABLE ddl_temp_new ADD COLUMNS (name STRING);

INSERT OVERWRITE TABLE dwd.result (id, name)
SELECT q.id, q.name FROM (SELECT * FROM ddl_temp_new) q;

DROP TABLE ddl_temp_new;

-- 新名已经删除；这条后续读取应报告对象不存在。
INSERT OVERWRITE TABLE dwd.result (id)
SELECT id FROM ddl_temp_new;
```

**血缘结果**

新普通名 `ddl_temp_new` 及默认库限定名可以定位改名对象，旧普通名不继续指向它。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `〈临时命名空间〉.ddl_temp_new.id` | `dwd.result.id` | `DIRECT` | — | 第一次 INSERT 读取新名 |
| `〈临时命名空间〉.ddl_temp_new.name` | `dwd.result.name` | `DIRECT` | — | 第一次 INSERT 读取新名 |

DROP 后的最后一条 INSERT 因对象不存在而失败，没有正式血缘。

**限制**

先 DROP 永久新名用于明确没有同名永久对象可供回退。后续删除不能让查找重新退回旧临时名称。

<a id="ddl-truncate"></a>

### 5.4 TRUNCATE

TRUNCATE 表达清空整表或指定分区的意图。它没有来源查询或字段赋值，因此自身不生成数据字段边。当前目录模拟识别受影响表，以 `TRUNCATE_VERSION_UNKNOWN` 记录数据版本未知；后续同对象读取保留不确定性。其他已知来源仍可完整成功，同时可能附带 `SESSION_EFFECT_UNCERTAIN` 会话诊断。

<a id="ddl-truncate-table"></a>

#### 5.4.1 TRUNCATE TABLE

表达整表清空意图，识别受到影响的对象。

**语法**

```text
TRUNCATE TABLE table_name;
```

**示例**

```sql
CREATE TABLE dwd.ddl_truncate_all (id BIGINT, name STRING);

TRUNCATE TABLE dwd.ddl_truncate_all;

INSERT OVERWRITE TABLE dwd.result (id, name)
SELECT * FROM dwd.ddl_truncate_all;

-- 未受影响的其他来源仍可继续正常分析。
INSERT OVERWRITE TABLE dwd.result (id, name)
SELECT id, name FROM ods.users;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| — | `dwd.ddl_truncate_all` | — | — | TRUNCATE 记录受影响对象，不生成数据写入边 |
| `dwd.ddl_truncate_all` | `dwd.result` | — | — | 随后读取保留表关系，列级结果为部分成功 |
| 字段来源未知 | `dwd.result.id` | `DERIVED` | — | 同对象读取的来源列表为空；保留未知宽度 |
| 字段来源未知 | `dwd.result.name` | `DERIVED` | — | 同对象读取的来源列表为空；保留未知宽度 |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 独立已知来源仍可完整分析 |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 独立已知来源仍可完整分析 |

**限制**

当前不证明截断后数据版本已空，不模拟清空执行。不能添加目标字段自赋值边，也不能据此推断下游确定零行或确定无来源。

<a id="ddl-truncate-partition"></a>

#### 5.4.2 分区 TRUNCATE

表达清空指定分区的意图，保留受影响表的状态变化。

**语法**

```text
TRUNCATE TABLE table_name PARTITION (key = literal, ...);
```

**示例**

```sql
CREATE TABLE dwd.ddl_truncate (id BIGINT)
PARTITIONED BY (dt STRING);

TRUNCATE TABLE dwd.ddl_truncate
PARTITION (dt = '2026-10-15');

INSERT OVERWRITE TABLE dwd.result (id, dt)
SELECT * FROM dwd.ddl_truncate;

INSERT OVERWRITE TABLE dwd.result (id)
SELECT id FROM ods.users;
```

**血缘结果**

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| — | `dwd.ddl_truncate` | — | — | 记录对象范围未知影响；分区常量不是字段赋值 |
| `dwd.ddl_truncate` | `dwd.result` | — | — | 随后读取保留表关系，列级结果为部分成功 |
| 字段来源未知 | `dwd.result.dt` | `DERIVED` | — | 同对象读取的来源列表为空 |
| 字段来源未知 | `dwd.result.id` | `DERIVED` | — | 同对象读取的来源列表为空 |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 独立已知来源仍可完整分析 |

**限制**

当前保留的是对象范围不确定性，没有精确到分区的数据状态模拟；不能据此认定指定分区已空、其他分区已知。该操作不等于字段删除、DROP 或 INSERT。

<a id="ddl-unknown-alter"></a>

### 5.5 未完整模拟的操作

未完整模拟的 ALTER 可以识别操作类别与目标对象，目录模拟保留对象范围的未知影响。后续同对象读取为部分结果；独立已知来源仍可完整成功，同时可能继续附带会话级不确定提示。自定义 CREATE 选项的边界则在于选项结构或语义未完整保存，不统一视为未知 ALTER。

<a id="ddl-alter-properties-location"></a>

#### 5.5.1 SET TBLPROPERTIES / LOCATION

保存表属性与存储位置变更的声明。

**语法**

```text
ALTER TABLE table_name SET TBLPROPERTIES (...);
ALTER TABLE table_name SET LOCATION 'path';
```

**示例**

```sql
CREATE TABLE dwd.statement_catalog_boundary (id BIGINT, name STRING);

ALTER TABLE dwd.statement_catalog_boundary
SET TBLPROPERTIES ('owner' = 'integration');

ALTER TABLE dwd.statement_catalog_boundary
SET LOCATION 'hdfs:///warehouse/statement_catalog_boundary';
```

**血缘结果**

两条 ALTER 对 `dwd.statement_catalog_boundary` 保留未知影响，没有数据字段边。

**限制**

当前不确定模拟属性或位置变化的后续效果，也不持久化属性或移动文件。LOCATION 不作为已读取文件来源。

<a id="ddl-alter-partitions"></a>

#### 5.5.2 分区管理与分区列变更

识别分区增删、重命名和分区作用域的列变更。

**语法**

```text
ALTER TABLE table_name ADD [IF NOT EXISTS] PARTITION (...) [LOCATION 'path'] ...;
ALTER TABLE table_name DROP [IF EXISTS] PARTITION (...) [PURGE];
ALTER TABLE table_name PARTITION (...) RENAME TO PARTITION (...);
ALTER TABLE table_name PARTITION (...) ADD/REPLACE COLUMNS (...);
ALTER TABLE table_name PARTITION (...) CHANGE old_name new_name type [FIRST|AFTER col];
```

**示例**

```sql
CREATE TABLE dwd.ddl_partition_actions (id BIGINT)
PARTITIONED BY (dt STRING);

ALTER TABLE dwd.ddl_partition_actions
ADD IF NOT EXISTS
PARTITION (dt = '2026-10-15') LOCATION '/warehouse/ddl/20261015'
PARTITION (dt = '2026-10-16');

ALTER TABLE dwd.ddl_partition_actions
DROP IF EXISTS PARTITION (dt = '2026-10-15') PURGE;

ALTER TABLE dwd.ddl_partition_actions
PARTITION (dt = '2026-10-16')
RENAME TO PARTITION (dt = '2026-10-17');

INSERT OVERWRITE TABLE dwd.result (id, dt)
SELECT * FROM dwd.ddl_partition_actions;
```

```sql
CREATE TABLE dwd.ddl_partition_columns (id BIGINT)
PARTITIONED BY (dt STRING);

ALTER TABLE dwd.ddl_partition_columns
PARTITION (dt = '2026-10-15') ADD COLUMNS (name STRING);

ALTER TABLE dwd.ddl_partition_columns
PARTITION (dt = '2026-10-15') REPLACE COLUMNS (new_id BIGINT);

ALTER TABLE dwd.ddl_partition_columns
PARTITION (dt = '2026-10-15') CHANGE id partition_id BIGINT FIRST;

INSERT OVERWRITE TABLE dwd.result (id, dt)
SELECT * FROM dwd.ddl_partition_columns;
```

**血缘结果**

两段脚本分别对 `dwd.ddl_partition_actions`、`dwd.ddl_partition_columns` 保留未知影响，操作自身没有数据赋值边。分区作用域列变更不会作为确定的整表列变更提交。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `dwd.ddl_partition_actions` | `dwd.result` | — | — | 第一段后续 INSERT 的表级来源；部分成功 |
| `dwd.ddl_partition_columns` | `dwd.result` | — | — | 第二段后续 INSERT 的表级来源；部分成功 |
| 字段来源未知 | `dwd.result.dt` | `DERIVED` | — | 两段读取均保留未知字段宽度 |
| 字段来源未知 | `dwd.result.id` | `DERIVED` | — | 两段读取均保留未知字段宽度 |

**限制**

当前没有完整分区目录、分区级列结构、位置、PURGE 或存在性条件模拟；CASCADE/RESTRICT 的传播也未建模。

<a id="ddl-alter-storage"></a>

#### 5.5.3 SerDe、FILEFORMAT 与分桶变更

识别表存储配置变化，并保留后续对象状态的不确定性。

**语法**

```text
ALTER TABLE table_name SET SERDE 'class';
ALTER TABLE table_name SET SERDEPROPERTIES (...);
ALTER TABLE table_name SET FILEFORMAT format;
ALTER TABLE table_name CLUSTERED BY (...) [SORTED BY (...)] INTO n BUCKETS;
```

**示例**

```sql
CREATE TABLE dwd.ddl_storage (id BIGINT);

ALTER TABLE dwd.ddl_storage
SET SERDE 'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe';
ALTER TABLE dwd.ddl_storage SET SERDEPROPERTIES ('field.delim' = ',');
ALTER TABLE dwd.ddl_storage SET FILEFORMAT ORC;
ALTER TABLE dwd.ddl_storage
CLUSTERED BY (id) SORTED BY (id) INTO 4 BUCKETS;

INSERT OVERWRITE TABLE dwd.result (id)
SELECT * FROM dwd.ddl_storage;

ALTER TABLE dwd.ddl_storage RENAME TO dwd.ddl_storage_renamed;
ALTER TABLE dwd.ddl_storage_renamed ADD COLUMNS (name STRING);

INSERT OVERWRITE TABLE dwd.result (id)
SELECT q.id FROM (SELECT * FROM dwd.ddl_storage_renamed) q;

INSERT OVERWRITE TABLE dwd.result (id)
SELECT id FROM ods.users;
```

**血缘结果**

四类存储 ALTER 不产生数据字段边。RENAME 保留对象身份及已有未知状态，后续 ADD 无法确定更新列结构。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `dwd.ddl_storage` | `dwd.result` | — | — | 改名前读取为部分成功 |
| `dwd.ddl_storage_renamed` | `dwd.result` | — | — | 改名后读取仍为部分成功 |
| 字段来源未知 | `dwd.result.id` | `DERIVED` | — | 两次读取的独立字段来源列表为空 |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 独立已知来源仍有完整字段映射 |

**限制**

CREATE 中保存存储声明不表示 ALTER 已有确定执行模型。当前不运行 SerDe、转换文件或重新分桶，改名不能恢复此前未知的列结构。

<a id="ddl-maintenance"></a>

#### 5.5.4 表维护操作

识别 TOUCH、COMPACT、CONCATENATE 和 UPDATE STATISTICS 的对象范围。

**语法**

```text
ALTER TABLE table_name TOUCH;
ALTER TABLE table_name COMPACT 'major';
ALTER TABLE table_name CONCATENATE;
ALTER TABLE table_name UPDATE STATISTICS SET (...);
```

**示例**

```sql
CREATE TABLE dwd.ddl_maintenance (id BIGINT);
ALTER TABLE dwd.ddl_maintenance TOUCH;
ALTER TABLE dwd.ddl_maintenance COMPACT 'major';
ALTER TABLE dwd.ddl_maintenance CONCATENATE;
ALTER TABLE dwd.ddl_maintenance UPDATE STATISTICS SET ('numRows' = '42');

INSERT OVERWRITE TABLE dwd.result (id)
SELECT * FROM dwd.ddl_maintenance;
```

**血缘结果**

四条维护操作对 `dwd.ddl_maintenance` 保留未知影响，自身没有字段赋值边。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `dwd.ddl_maintenance` | `dwd.result` | — | — | 后续读取保留表关系，列级结果为部分成功 |
| 字段来源未知 | `dwd.result.id` | `DERIVED` | — | 保留未知字段宽度 |

**限制**

分析器不执行压缩、文件合并、刷新钩子或统计写入，也不证明这些操作完成。

<a id="ddl-unmodeled-options"></a>

#### 5.5.5 自定义存储、倾斜与约束

保留可解析声明中的普通列，同时明确自定义选项的当前语义边界。

**语法**

```text
CREATE TABLE name (columns)
STORED AS INPUTFORMAT 'input_class' OUTPUTFORMAT 'output_class';
CREATE TABLE name (columns) STORED BY 'handler' WITH SERDEPROPERTIES (...);
CREATE TABLE name (columns) SKEWED BY (...) ON (...) STORED AS DIRECTORIES;
CREATE TABLE name (column type PRIMARY KEY DISABLE NOVALIDATE, column type NOT NULL, ...);
```

**示例**

```sql
CREATE TABLE dwd.ddl_custom_format (id BIGINT)
STORED AS INPUTFORMAT 'example.InputFormat' OUTPUTFORMAT 'example.OutputFormat';

CREATE TABLE dwd.ddl_storage_handler (id BIGINT)
STORED BY 'example.StorageHandler'
WITH SERDEPROPERTIES ('handler.option' = 'enabled');

CREATE TABLE dwd.ddl_skewed (id BIGINT, name STRING)
SKEWED BY (id) ON (1, 2) STORED AS DIRECTORIES;

CREATE TABLE dwd.ddl_constraints (
    id BIGINT PRIMARY KEY DISABLE NOVALIDATE,
    name STRING NOT NULL,
    amount DECIMAL(12,2) COMMENT 'actual amount comment'
);
```

**血缘结果**

这些普通 CREATE 没有数据写入边。目录模拟可提供 `dwd.ddl_custom_format`、`dwd.ddl_storage_handler`、`dwd.ddl_skewed`、`dwd.ddl_constraints` 的声明列，但不完整建模自定义选项。PRIMARY KEY、NOT NULL 不会被误读为列注释。

**限制**

自定义 INPUTFORMAT/OUTPUTFORMAT、storage handler、skew 目录和约束尚无完整结构或执行模型。原 SQL 可供审计，但并非每项选项都结构化保存；主键与 NOT NULL 也不用于数据校验或血缘推断。

### 5.6 边界与恢复

<a id="ddl-rejected-families"></a>

#### 5.6.1 物化视图、IMPORT / EXPORT

区分可解析的语句与当前能够建立的正式血缘或目录效果。

**语法**

```text
CREATE MATERIALIZED VIEW name AS query;
ALTER MATERIALIZED VIEW name REBUILD;
DROP MATERIALIZED VIEW [IF EXISTS] name;
IMPORT TABLE name FROM 'path';
EXPORT TABLE name TO 'path';
```

**示例**

```sql
CREATE MATERIALIZED VIEW dim.ddl_materialized AS
SELECT q.id FROM (SELECT id FROM ods.users) q;

ALTER MATERIALIZED VIEW dim.ddl_materialized REBUILD;
DROP MATERIALIZED VIEW IF EXISTS dim.ddl_materialized;

IMPORT TABLE dwd.ddl_imported FROM 'hdfs:///export/generated_users';
EXPORT TABLE ods.users TO 'hdfs:///export/generated_users';

INSERT INTO dwd.result (id, name)
SELECT id, name FROM ods.users;
```

**血缘结果**

| 操作 | 结果 | 说明 |
| --- | --- | --- |
| CREATE MATERIALIZED VIEW AS | 明确拒绝 | 不发布物化查询写入，不登记成功创建 |
| ALTER MATERIALIZED VIEW REBUILD | 明确拒绝 | 不发布重建写入或版本影响 |
| DROP MATERIALIZED VIEW | 仅审计 | 不模拟删除目录对象 |
| IMPORT TABLE | 明确拒绝 | 不建立导入路径、列结构或写入映射 |
| EXPORT TABLE | 明确拒绝 | 不发布导出文件或外部目标血缘 |

继续处理后续语句时，末尾普通 INSERT 仍产生以下映射。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 正常恢复后续分析 |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 正常恢复后续分析 |

**限制**

前端解析成功不构成数据写入支持。DROP MATERIALIZED VIEW 当前不模拟删除目录对象，导入导出路径也不建立文件血缘。

<a id="ddl-invalid-recovery"></a>

#### 5.6.2 非查询 DDL 错误恢复

对可恢复的非查询 DDL 语法错误保留诊断，并按继续处理配置分析后续语句。

**处理规则**

```text
非查询 DDL 语法错误 -> 保存诊断与原语句 -> 继续后续语句（非 fail-fast）；
```

**示例**

```sql
-- id 缺少类型；此语句无有效表声明效果。
CREATE TABLE dwd.ddl_invalid (id);

INSERT INTO dwd.result (id, name)
SELECT id, name FROM ods.users;
```

**血缘结果**

缺少列类型的 CREATE 保留语法错误诊断，不为 `dwd.ddl_invalid` 登记有效列结构，也不产生正式血缘。继续处理后续语句时，INSERT 产生以下映射。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 后续 INSERT |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 后续 INSERT |

**限制**

这是容错审计行为，不表示非法声明被接受为有效 DDL。启用遇错停止时，后续语句的处理遵循对应配置。

<a id="session-commands"></a>

<a id="commands"></a>

## 6. 会话与脚本

会话命令为后续语句提供变量和默认数据库；资源、函数及元数据命令保留为审计记录。它们本身不产生字段血缘，后续的数据语句仍按脚本顺序分析。

### 6.1 会话上下文

<a id="session-set-use"></a>

#### 6.1.1 SET 与 USE

SET 提供变量值，USE 改变未限定表名的默认数据库。

**语法**

```text
SET key=value;
SET hivevar:key=value;
SET hiveconf:key=value;
USE 数据库名;
```

**示例**

```sql
SET hivevar:source_table=users;
SET bizdate=20261014;
USE ods;

INSERT OVERWRITE TABLE dwd.result(id, name, dt)
SELECT u.id, u.name, '${hiveconf:bizdate}'
FROM ${hivevar:source_table} u
WHERE u.dt='${bizdate}';
```

**血缘结果**

INSERT 的来源表解析为 `ods.users`，字段关系如下。结果保留原始占位符 SQL 和展开后的 SQL。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 直接投影 |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 直接投影 |
| 无源字段 | `dwd.result.dt` | `CONSTANT` | — | 变量替换后的日期常量 |

**限制**

SET/USE 的影响限定在本次脚本上下文，不会执行全局 Hive 会话操作。`SET`、`SET hiveconf:key` 查询形式只保留审计记录，不返回配置值。对 system/env 的 SET 不会修改真实 OS/JVM 环境。

### 6.2 资源与函数声明

<a id="session-resources-functions"></a>

#### 6.2.1 资源、函数与宏

脚本可以包含资源和函数声明，分析器会继续处理后面的数据语句。SOURCE 文件不会被读取，JAR、FILE 和 ARCHIVE 不会被加载。

**语法**

```text
{ADD | DELETE} {JAR | FILE | ARCHIVE} 资源路径;
LIST {JAR | FILE | ARCHIVE};
SOURCE 路径;
CREATE [TEMPORARY] FUNCTION 名称 AS '类名' [USING {JAR | FILE | ARCHIVE} 'URI', ...];
DROP [TEMPORARY] FUNCTION [IF EXISTS] 名称;
CREATE TEMPORARY MACRO 名称(参数 类型, ...) 表达式;
DROP TEMPORARY MACRO [IF EXISTS] 名称;
RELOAD FUNCTIONS;
```

**示例**

```sql
ADD JAR hdfs:///lib/statement_udf.jar;
ADD FILE /tmp/statement_lookup.txt;
ADD ARCHIVE hdfs:///lib/statement_lookup.zip;
LIST JAR;
DELETE FILE /tmp/statement_lookup.txt;
SOURCE /tmp/statement_source.hql;
CREATE TEMPORARY FUNCTION statement_fn AS 'example.StatementFunction'
USING JAR 'hdfs:///lib/statement_udf.jar';
DROP TEMPORARY FUNCTION IF EXISTS statement_fn;
CREATE TEMPORARY MACRO ddl_plus_one(x INT) x + 1;
DROP TEMPORARY MACRO IF EXISTS ddl_plus_one;
RELOAD FUNCTIONS;

INSERT INTO dwd.result(id, name)
SELECT u.id, u.name FROM ods.users u;
```

**血缘结果**

资源和声明命令只保留审计记录，不增加输入表或字段边；最后一条 INSERT 的表血缘为 `ods.users -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 最后一条 INSERT 的直接投影 |
| `ods.users.name` | `dwd.result.name` | `DIRECT` | — | 最后一条 INSERT 的直接投影 |

**限制**

函数和宏声明不会注册可执行逻辑，调用自定义函数时仍按表达式分析规则处理。`IF EXISTS` 不会触发函数存在性校验。分析器不会执行 SOURCE、检查资源路径是否存在或展开外部脚本内容。

<a id="audit"></a>

### 6.3 元数据与维护命令

<a id="audit-metadata-statements"></a>

#### 6.3.1 数据库、元数据查询与统计维护

数据库 DDL、SHOW、DESCRIBE、EXPLAIN、ANALYZE 和 MSCK 用于描述管理操作或查询元数据。下列形式保留为审计记录。

**语法**

```text
CREATE {DATABASE | SCHEMA} ...;
ALTER DATABASE ... SET {DBPROPERTIES | OWNER | LOCATION} ...;
DROP {DATABASE | SCHEMA} ... {CASCADE | RESTRICT};
SHOW {DATABASES | TABLES | VIEWS | FUNCTIONS | PARTITIONS | TBLPROPERTIES} ...;
SHOW CREATE TABLE ...;
DESCRIBE [FORMATTED] 表;
DESCRIBE {DATABASE | FUNCTION} [EXTENDED] 名称;
EXPLAIN 查询;
ANALYZE TABLE ... COMPUTE STATISTICS [FOR COLUMNS];
MSCK REPAIR TABLE ...;
```

**示例**

```sql
CREATE DATABASE IF NOT EXISTS ddl_database
COMMENT 'generated database' LOCATION 'hdfs:///warehouse/ddl_database'
WITH DBPROPERTIES ('owner'='integration');
CREATE SCHEMA IF NOT EXISTS ddl_schema;
ALTER DATABASE ddl_database SET DBPROPERTIES ('owner'='updated');
ALTER DATABASE ddl_database SET OWNER USER generated_owner;
ALTER DATABASE ddl_database SET LOCATION 'hdfs:///warehouse/ddl_database_new';
SHOW DATABASES LIKE 'ddl*';
SHOW TABLES IN ods LIKE 'users*';
SHOW VIEWS IN dim;
SHOW FUNCTIONS LIKE 'ddl*';
SHOW PARTITIONS ods.users;
SHOW TBLPROPERTIES ods.users;
SHOW CREATE TABLE ods.users;
DESCRIBE FORMATTED ods.users;
DESCRIBE DATABASE EXTENDED ods;
DESCRIBE FUNCTION EXTENDED default.ddl_function;
EXPLAIN SELECT q.id FROM (SELECT id FROM ods.users) q;
ANALYZE TABLE ods.users COMPUTE STATISTICS;
ANALYZE TABLE ods.users PARTITION(dt='20261015') COMPUTE STATISTICS FOR COLUMNS;
MSCK REPAIR TABLE ods.users;
DROP DATABASE IF EXISTS ddl_database CASCADE;
DROP SCHEMA IF EXISTS ddl_schema RESTRICT;
```

**血缘结果**

这些语句不产生数据写入或字段血缘。EXPLAIN 内部的 SELECT 是待解释的查询，不产生 `ods.users -> 某目标表` 的写入血缘；SHOW CREATE TABLE 也不构成建表操作。

**限制**

分析器不会模拟数据库目录、权限、级联删除或统计更新。其他 SHOW/DESCRIBE/EXPLAIN 选项、NOSCAN、MSCK 分区同步、权限和角色、调度、数据连接器等形式需要分别确认支持范围。物化视图写入及 IMPORT/EXPORT 的失败边界见[数据操作边界](#ddl-rejected-families)。

<a id="script-input"></a>

### 6.4 脚本输入与错误恢复

<a id="script-variables"></a>

#### 6.4.1 变量替换

变量可以用于表名，也可以出现在字符串中。需要确定物理来源时，应为表名变量提供明确值。

**语法**

```text
${hivevar:name}
${hiveconf:name}
${name}
@{name}                   模板占位符，按变量策略处理
```

**示例**

```sql
SET hivevar:source_table=users;
SET hiveconf:business_day=20261015;
USE ods;
INSERT INTO dwd.result(id, dt)
SELECT id, '${hiveconf:business_day}'
FROM ${hivevar:source_table};
```

**血缘结果**

变量展开后的来源表为 `ods.users`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 变量展开后的直接投影 |
| 无来源字段 | `dwd.result.dt` | `CONSTANT` | — | 变量替换后的常量 |

请求中的 runtime 变量可被后续 SET 覆盖。下一次请求使用自己的初始变量，不继承前一次脚本中的临时赋值。

**限制**

未知变量可按配置严格失败、保留并告警、使用兼容兜底值，或按内置变量兜底。v1 兼容入口通过请求选项控制兜底值；`@{...}` 在不同策略下不一定读取显式环境值。环境/JVM 值不会自动取自分析进程。变量替换不会递归展开，也不按 SQL 词法排除注释中的占位符；复杂脚本应核对结果中的原始 SQL 与展开 SQL。

<a id="script-quotes-comments"></a>

<a id="lexical"></a>

#### 6.4.2 引号与注释

单引号字符串、反引号名称及注释中的分号不会直接作为脚本分隔符。注释中的查询文本不会成为输入表。

**语法**

```text
字符串：'文本;仍是同一个值'
标识符：`名称`
行注释：-- 注释
块注释：/* 非嵌套注释 */
默认脚本分隔符：;
```

**示例**

```sql
-- 这里的 ; 不拆出新语句，SELECT 'ignore;me' 也只是注释。
/* 这里的 (SELECT id FROM missing) 不会成为输入表。 */
INSERT INTO `dwd`.`result` (`id`, `name`)
SELECT u.`id`, concat(u.`name`, ';中文')
FROM `ods`.`users` u;
```

**血缘结果**

表血缘为 `ods.users -> dwd.result`。

| 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- |
| `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 反引号字段引用 |
| `ods.users.name` | `dwd.result.name` | `DERIVED` | `["concat"]` | CONCAT 拼接 |

字符串 `';中文'` 是 CONCAT 的常量参数；注释中的 `missing` 不成为输入表。

**限制**

双引号受到语句边界保护，不代表可将它用作标识符。复杂转义、特殊 Unicode 和嵌套块注释需要分别确认支持范围。SELECT 中的单引号别名规则不能直接用于函数类名或数据库名。

<a id="script-error-recovery"></a>

#### 6.4.3 错误恢复

脚本结果区分忽略、失败和部分结果。无查询的错误 DDL 可以保留为 `INVALID_DDL` 审计记录；错误查询或非法 USE 不会作为有效数据语句处理。

**处理规则**

```text
无查询的错误 DDL：可恢复为 INVALID_DDL 审计，不提交有效定义
非法 USE：INVALID_USE_DATABASE，失败，不覆盖此前有效默认库
错误数据查询：失败；默认可继续后续语句
failFast=true：遇失败停止后续分析
```

**示例**

第一句遗漏列类型，随后继续写入：

```sql
CREATE TABLE dwd.bad_definition (id);
INSERT INTO dwd.result(id)
SELECT id FROM ods.users;
```

下面是非法 USE 的独立示例：

```sql
USE `ods`;
USE 'invalid_database';
INSERT INTO dwd.result(id)
SELECT id FROM users;
```

**血缘结果**

第一个示例中的建表语句不产生有效定义或数据血缘；其后的 INSERT 正常分析。第二个示例中非法 USE 失败，先前有效的默认库 `ods` 保持生效，后续 users 仍解析为 ods.users。

| 语句 | 来源 | 目标 | `lineageType` | `functionNames` | 说明 |
| --- | --- | --- | --- | --- | --- |
| 错误 DDL 后的 INSERT | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 恢复后 INSERT 的直接投影 |
| 非法 USE 后的 INSERT | `ods.users.id` | `dwd.result.id` | `DIRECT` | — | 恢复后 INSERT 的直接投影 |

含 SELECT/WITH 的错误 CTAS 不适用无查询 DDL 恢复规则。

**限制**

未闭合引号或注释、过深括号、过多 token、过长展开文本等问题会产生前端诊断，不能保证恢复任意错误脚本。SELECT 投影中的 `???` 仅在启用有限恢复时按未知表达式处理。

<a id="configuration"></a>

## 7. 接口与配置

<a id="configuration-basic"></a>

### 7.1 v1 请求适配与解析选项

使用 `new HiveScriptLineageAdapter().analyzeScript(existingRequest)` 调用 v2 引擎，入参及兼容返回值继续使用 v1 类型。`analyzeNative(existingRequest)` 返回 v2 原生结果，供调用方读取质量、目录效果、诊断和独立写入贡献。基本接入及依赖配置见 [README](../README.md)。

原生请求与结果分别为 `com.panfan.hivelineage.v2.api.ScriptAnalysisRequest`、`ScriptAnalysisResult`，默认实现为 `DefaultScriptLineageAnalyzer`。v2 实现及测试统一放在 `com.panfan.hivelineage.v2` 包树，旧 API 和 v1 实现保持不变；原类名的对应关系见[包结构与入口迁移](../README.md#package-layout-and-api-migration)。

**配置示例**

```java
LineageAnalyzeOptions options = LineageAnalyzeOptions.builder()
    .parseMode(LineageParseMode.TABLE_AND_COLUMN)
    .includeIgnoredStatements(true)
    .parseJoinConditions(true)
    .expandViewLineage(true)
    .includeTemporaryLineage(false)
    .failFast(false)
    .build();
```

将选项设置到现有 `LineageAnalyzeRequest`。元数据提供器负责源表和目标表的结构，`HiveMetadataCache` 用于元数据缓存；变量和脚本内对象按请求隔离。

| 选项 | 行为 |
| --- | --- |
| `TABLE_ONLY` | 返回表血缘；处理要求以表维度为准。 |
| `COLUMN_ONLY` | 返回字段血缘；表集合为空不表示查询没有来源表。 |
| `TABLE_AND_COLUMN` | 同时返回表和字段血缘。正文示例采用此模式。 |
| `includeIgnoredStatements` | 返回普通 DDL、纯查询、SET/USE 和审计命令的明细及忽略原因。 |
| `parseJoinConditions` | 返回关联条件信息。 |
| `expandViewLineage` | 保留视图边界并展开可用定义；定义缺失、循环或达到深度限制时返回诊断。 |
| `maxViewExpandDepth` | 视图展开深度上限，默认 `5`。 |
| `includeTemporaryLineage` | 控制临时对象相关正式血缘的发布；关闭时仍可为后续语句保留临时定义。 |
| `fallbackVariableValue` | 兼容变量替换的兜底值，默认 `__HIVE_LINEAGE_DEFAULT__`；不等于已确认的表名或源字段。 |
| `failFast` | `true` 遇失败停止；`false` 继续处理后续语句。 |

<a id="configuration-star-mapping"></a>

### 7.2 星号映射

v1 适配入口默认采用 `CURRENT_API_TARGET_MAPPING`。星号展开经过兼容映射时，已确认的字段关系可以与同名诊断及 `PARTIAL_SUCCESS` 同时返回。调用方应核对具体映射和未知部分；显式投影及目标列有助于明确对应关系。

需要严格按输出位置匹配时，在 v2 原生请求中使用 `POSITIONAL_EXACT`。下节辅助类的请求适配语句可替换为：

```java
ScriptAnalysisRequest base = new DefaultRequestAdapter(
    com.panfan.hivelineage.v2.api.StarMappingPolicy.POSITIONAL_EXACT).adapt(legacy);
```

位置映射仍要求来源列宽和目标列信息完整，不补齐缺失元数据，也不改变 SQL 投影顺序。

<a id="configuration-native-capabilities"></a>

### 7.3 原生分析能力

以下能力通过 v2 `AnalysisContract` 设置，v1 `LineageAnalyzeOptions` 没有对应的 setter。

| 能力 | 用途 | 未启用时 |
| --- | --- | --- |
| `CATALOG_SIMULATION` | 模拟普通 CREATE/ALTER/DROP、名称变更、临时遮蔽和 TRUNCATE 对后续语句的影响 | 普通声明 DDL 不保证建立可供后续读取的新结构。CTAS 和视图的定义注册另有处理规则。 |
| `EXTERNAL_DATA_LINEAGE` | 建立 LOAD DATA 的本地或分布式路径与目标表关系 | LOAD 因请求能力不足而失败。启用后文件结构仍未知，不生成文件字段映射。 |

#### 7.3.1 能力配置辅助类

`ReferenceLineage` 是供调用方复制的示例辅助类，不是库中已有的 API。它沿用 v1 请求的模式与选项，添加指定能力，并返回 `ScriptAnalysisResult`。第二个重载允许传入包含完整类型的 v2 `MetadataProvider`。

```java
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.v2.api.AnalysisCapabilities;
import com.panfan.hivelineage.v2.api.AnalysisCapability;
import com.panfan.hivelineage.v2.api.AnalysisContract;
import com.panfan.hivelineage.v2.api.DefaultScriptLineageAnalyzer;
import com.panfan.hivelineage.v2.api.ScriptAnalysisRequest;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.compat.DefaultRequestAdapter;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.DefaultV1MetadataAdapterFactory;
import com.panfan.hivelineage.v2.metadata.LegacyMetadataBinding;
import com.panfan.hivelineage.v2.metadata.MetadataProvider;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;

public final class ReferenceLineage {
    public static ScriptAnalysisResult analyze(LineageAnalyzeRequest legacy, AnalysisCapability... extra) {
        return analyze(legacy, null, extra);
    }

    public static ScriptAnalysisResult analyze(LineageAnalyzeRequest legacy, MetadataProvider nativeProvider,
            AnalysisCapability... extra) {
        ScriptAnalysisRequest base = new DefaultRequestAdapter().adapt(legacy);
        AnalysisContract old = base.getContract();
        EnumSet<AnalysisCapability> enabled = EnumSet.noneOf(AnalysisCapability.class);
        enabled.addAll(old.getCapabilities().getCapabilities());
        enabled.addAll(Arrays.asList(extra));
        AnalysisContract contract = new AnalysisContract(new AnalysisCapabilities(enabled),
            old.getVariablePolicy(), old.getDefinitionPolicy(), old.getStarMappingPolicy(),
            old.getPublicationPolicy(), old.isIncludeTemporaryLineage(), old.isIncludeIgnoredStatements(),
            old.isExpandViewLineage(), old.getMaxViewExpandDepth(), old.isFailFast(), old.getFallbackVariableValue());
        ScriptAnalysisRequest request = new ScriptAnalysisRequest(base.getTask(), base.getSource(), base.getDialect(),
            base.getInitialSession(), base.getMetadataContext(), contract, base.getResourceLimits());
        MetadataProvider provider = nativeProvider != null ? nativeProvider : new DefaultV1MetadataAdapterFactory().adapt(new LegacyMetadataBinding(
            Optional.ofNullable(legacy.getMetadataProvider()), Optional.ofNullable(legacy.getMetadataCache()),
            request.getMetadataContext(), false));
        WorkflowContext workflow = new WorkflowContext(request.getTask().getWorkflowId(),
            request.getMetadataContext(), provider, new DefaultMetadataCache());
        return new DefaultScriptLineageAnalyzer().analyzeScript(request, workflow);
    }
}
```

#### 7.3.2 调用示例

沿用现有请求，启用普通 DDL 的目录模拟：

```java
ScriptAnalysisResult result = ReferenceLineage.analyze(
    existingRequest, AnalysisCapability.CATALOG_SIMULATION);
```

同时分析 LOAD 路径时，追加 `AnalysisCapability.EXTERNAL_DATA_LINEAGE`。已有带完整类型的 v2 提供器时，可调用 `ReferenceLineage.analyze(existingRequest, typedProvider, ...)`；能力开关不会为仅含列名的提供器补充 STRUCT 等类型。

需要兼容返回模型时，使用 `new DefaultLegacyResultProjector().project(result).getResult()`。同一目标字段可能对应多个独立贡献，应保留各语句和动作的记录。

<a id="function-directory"></a>

## 附录 A. 函数索引

以下名称具有函数类别及一般参数依赖规则。名称可识别不等于全部重载、参数数目或返回类型均已确认；函数调用不会在分析过程中执行。未声明规则的函数按[未知函数](#expr-unknown-udf)处理。

### A.1 标量函数

| 用途 | 名称 |
| --- | --- |
| 数值 | `abs`, `ceil`, `floor`, `round`, `pmod`, `greatest`, `least` |
| 大小写与修剪 | `upper`, `lower`, `trim`, `ltrim`, `rtrim` |
| 字符串切片与定位 | `substr`, `substring`, `length`, `instr` |
| 字符串拼接与转换 | `concat`, `concat_ws`, `split`, `reverse`, `replace`, `format_number` |
| 条件与空值 | `coalesce`, `nvl`, `nullif`, `if`, `isnull`, `isnotnull` |
| 模式替换与提取 | `regexp_replace`, `regexp_extract` |
| 日期转换与格式 | `to_date`, `date_format`, `unix_timestamp`, `from_unixtime`, `to_unix_timestamp` |
| 日期计算 | `date_add`, `date_sub`, `datediff`, `add_months` |
| 日期时间分量 | `year`, `month`, `day`, `dayofmonth`, `hour`, `minute`, `second` |
| 当前日期与时间 | `current_date`, `current_timestamp` |
| 集合与结构 | `size`, `array`, `map`, `struct`, `named_struct`, `element_at` |
| JSON 与 URL | `get_json_object`, `parse_url` |
| 哈希与摘要 | `hash`, `md5`, `sha1`, `sha2` |
| 类型转换 | `cast` |

CAST 及 IS NULL / IS NOT NULL 的专用语法规则分别见[条件与类型转换](#expr-case-cast)及[比较与逻辑](#expr-predicates)。这些语法规则与同名函数调用的规则应分别理解。

### A.2 聚合函数

| 用途 | 名称 |
| --- | --- |
| 求和与计数 | `sum`, `count` |
| 均值与极值 | `avg`, `min`, `max` |
| 集合聚合 | `collect_set`, `collect_list` |
| 统计聚合 | `stddev`, `variance` |
| 首尾值 | `first_value`, `last_value` |

聚合值沿参数字段追溯，COUNT(*) 保留行集依赖。`first_value`、`last_value` 的类别记录不保证所有 OVER 调用形式均已确认。多参数 COUNT DISTINCT、全部重载及函数 FILTER 也应按[聚合函数限制](#expr-aggregate-functions)处理。

### A.3 窗口函数

| 用途 | 名称 |
| --- | --- |
| 行号与排名 | `row_number`, `rank`, `dense_rank` |
| 分桶与分布 | `ntile`, `cume_dist`, `percent_rank` |
| 相邻行取值 | `lead`, `lag` |

窗口调用的来源包括参数、`PARTITION BY` 分区键和 `ORDER BY` 排序键。ROW_NUMBER 的规则不能推广为其余函数全部参数形式的保证；LEAD/LAG 的偏移、默认值及返回类型需结合具体规则和诊断确认。用法见[窗口函数](#expr-windows)。

### A.4 生成函数

| 用途 | 名称 |
| --- | --- |
| 集合展开 | `explode`, `posexplode`, `inline` |
| 文本字段提取 | `json_tuple`, `parse_url_tuple` |
| 多行构造 | `stack` |

生成列沿参数追溯，具体用法见[LATERAL VIEW](#query-lateral-view)。explode、posexplode 之外的多列输出、参数对应关系及精确输出类型尚未全部确认；名称规则本身不提供完整输出 schema。

<a id="syntax-index"></a>

## 附录 B. 语法索引

相同关键字在不同语句中的语义可能不同，以下按使用位置索引。

| 关键字或语法 | 参见 |
| --- | --- |
| `SELECT`、`ALL`、`DISTINCT`、`AS`、`*` | [投影与别名](#query-select)、[纯查询](#query-pure-select) |
| `FROM (SELECT ...)` | [派生表](#query-derived) |
| `WITH ... AS`、CTE 列名列表 | [CTE](#query-cte)、[作用域与自连接](#query-scopes) |
| `INNER JOIN`、`LEFT OUTER JOIN` | [内连接与左外连接](#query-inner-left) |
| `RIGHT OUTER JOIN`、`FULL OUTER JOIN` | [右外连接与全外连接](#query-right-full) |
| `LEFT SEMI JOIN` | [半连接](#query-semi) |
| `CROSS JOIN`、逗号连接 | [交叉连接](#query-cross-comma) |
| `ON`、`USING`、`NATURAL JOIN`、`LEFT ANTI` | [连接条件与边界](#query-on-boundaries) |
| `UNION`、`INTERSECT`、`EXCEPT`、`ALL`、`DISTINCT` | [集合查询](#query-set-operations) |
| `INSERT OVERWRITE ... UNION ALL ... FROM (SELECT ...)` | [集合分支与派生表](#query-insert-union-derived) |
| `WHERE` | [筛选条件](#query-where) |
| `GROUP BY`、`HAVING` | [分组与聚合筛选](#query-group-having) |
| `ROLLUP`、`CUBE`、`GROUPING SETS` | [分组扩展](#query-grouping-sets) |
| `ORDER BY`、`SORT BY`、`DISTRIBUTE BY`、`CLUSTER BY`、`LIMIT` | [排序、分发与行数限制](#query-order-distribution-limit) |
| `TABLESAMPLE`、`BUCKET`、`PERCENT`、`ROWS` | [采样](#query-sampling) |
| `LATERAL VIEW`、`OUTER`、`explode`、`posexplode` | [横向视图](#query-lateral-view) |
| 字面量、布尔值、`NULL`、`DATE`、`TIMESTAMP` | [字面量与常量](#expr-literals) |
| 算术、位、比较、逻辑和模式运算符 | [算术与位运算](#expr-operators)、[谓词](#expr-predicates) |
| `CASE`、`WHEN`、`THEN`、`ELSE`、`CAST` | [条件与类型转换](#expr-case-cast) |
| 标量函数、聚合函数、UDF | [标量函数](#expr-scalar-functions)、[聚合函数](#expr-aggregate-functions)、[未知函数](#expr-unknown-udf)、[函数索引](#function-directory) |
| `ARRAY`、`MAP`、`STRUCT` 字段访问 | [复杂字段路径](#expr-complex-paths) |
| 标量子查询、相关子查询 | [标量子查询](#expr-scalar-subqueries)、[分组与排序中的子查询](#expr-hidden-subquery-inputs) |
| `EXISTS`、`NOT EXISTS`、`IN`、`NOT IN` | [筛选子查询](#expr-filter-subqueries) |
| `OVER`、`PARTITION BY`、`WINDOW`、`ROWS`、`RANGE` | [窗口函数](#expr-windows)、[命名窗口与帧边界](#expr-named-windows-frames) |
| `INTERVAL` | [时间间隔](#expr-interval) |
| `INSERT INTO`、`INSERT OVERWRITE`、目标列列表 | [INSERT](#write-insert)、[目标列顺序](#write-target-order) |
| `PARTITION`（写入） | [静态分区](#write-static-partition)、[动态分区](#write-dynamic-partition)、[混合分区](#write-mixed-partition) |
| `FROM ... INSERT ... INSERT ...` | [多路 INSERT](#write-multi-insert) |
| `CREATE TABLE ... AS SELECT`、临时 CTAS | [CTAS](#write-ctas)、[临时 CTAS](#write-temporary-ctas) |
| `CREATE VIEW`、`CREATE OR REPLACE VIEW`、`ALTER VIEW ... AS` | [创建视图](#write-create-view)、[替换视图](#write-replace-view)、[修改视图定义](#write-alter-view) |
| `UPDATE ... SET`、`DELETE FROM`、`MERGE INTO` | [UPDATE](#write-update)、[DELETE](#write-delete)、[MERGE](#write-merge) |
| `INSERT ... VALUES`、`INSERT ... DIRECTORY`、`LOAD DATA` | [特殊写入](#write-values-directories)、[外部数据加载](#write-load-data) |
| `CREATE TABLE`、基础类型、复杂类型、`UNIONTYPE` | [表与类型声明](#ddl-create-types) |
| `EXTERNAL`、`PARTITIONED BY`、`COMMENT`、`CLUSTERED BY`、`SORTED BY`、`ROW FORMAT`、`STORED AS`、`LOCATION`、`TBLPROPERTIES` | [存储与表选项](#ddl-external-storage)、[SerDe](#ddl-serde) |
| `CREATE TABLE ... LIKE` | [模板表声明](#ddl-create-like) |
| `ADD COLUMNS`、`REPLACE COLUMNS`、`CHANGE COLUMN`、`FIRST`、`AFTER` | [增替数据列](#ddl-add-replace-columns)、[列变更](#ddl-change-column) |
| `ALTER TABLE/VIEW ... RENAME TO` | [表重命名](#ddl-rename-table)、[视图重命名](#ddl-rename-view) |
| `IF NOT EXISTS`、`IF EXISTS`、`DROP` | [条件与生命周期](#ddl-drop-if) |
| `TEMPORARY`（声明与名称） | [临时遮蔽](#ddl-temporary-shadow)、[临时名称变更](#ddl-temporary-rename) |
| `TRUNCATE TABLE`、`TRUNCATE ... PARTITION` | [TRUNCATE](#ddl-truncate)、[整表清空](#ddl-truncate-table)、[分区清空](#ddl-truncate-partition) |
| `ALTER ... SET TBLPROPERTIES`、`SET LOCATION` | [属性与位置变更](#ddl-alter-properties-location) |
| 分区 `ADD`、`DROP`、`RENAME`，分区级列变更 | [分区操作](#ddl-alter-partitions) |
| `SET SERDE`、`SET FILEFORMAT`、分桶变更 | [存储变更](#ddl-alter-storage) |
| `TOUCH`、`COMPACT`、`CONCATENATE`、`UPDATE STATISTICS` | [表维护](#ddl-maintenance) |
| `STORED BY`、`SKEWED BY`、约束 | [未完整建模的声明选项](#ddl-unmodeled-options) |
| `MATERIALIZED VIEW`、`IMPORT`、`EXPORT` | [数据操作边界](#ddl-rejected-families) |
| `SET`、`USE` | [会话上下文](#session-set-use) |
| `SOURCE`、资源 `ADD/LIST/DELETE`、`FUNCTION`、`MACRO`、`RELOAD` | [资源与函数声明](#session-resources-functions) |
| `DATABASE`、`SCHEMA`、`SHOW`、`DESCRIBE`、`EXPLAIN`、`ANALYZE`、`MSCK` | [元数据与维护命令](#audit-metadata-statements) |
| `${...}`、`@{...}`、引号、注释、语句分隔 | [变量](#script-variables)、[词法边界](#script-quotes-comments) |
| 错误恢复、`failFast` | [脚本恢复](#script-error-recovery)、[DDL 恢复](#ddl-invalid-recovery)、[解析选项](#configuration-basic) |

<a id="reference-resources"></a>

## 附录 C. 相关资料

接口接入见 [README](../README.md)。实现状态、场景覆盖及剩余缺口另归档于[开发验证矩阵](v2-sql-syntax-coverage-matrix.md)；完整 SQL 文件及验证方法见 [SQL 资源索引](../src/test/resources/hql/generated/v2_syntax_coverage.md#扩展-sql-文件清单)和 [DDL 审计资料](../src/test/resources/hql/generated/v2_keyword_ddl_coverage_audit.md)。这些资料用于实现维护；用户查阅语法与结果解释以本文对应条目为入口。
