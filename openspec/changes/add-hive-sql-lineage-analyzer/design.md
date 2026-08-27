# Design: Hive SQL Lineage Analyzer

## Goals

构建一个面向工作流批量解析场景的 Hive SQL 血缘解析工具，解析粒度以 Hive 任务脚本为主。工具需要在一个任务脚本内维护 Session 状态，并在一个工作流内复用元数据缓存。

## Non-Goals

- 不负责调度工作流或读取 Hive 任务脚本。
- 不负责持久化血缘结果。
- 不负责替代现有注释处理和 SQL 分割逻辑。
- 不把只有输入没有输出的 `select` 语句计入正式血缘。

## Technical Constraints

- 运行环境使用 Java 8。
- 构建工具使用 Maven。
- 项目没有统一依赖 BOM，本工具模块需要在 `pom.xml` 中显式声明依赖版本。
- Hive SQL 解析依赖先使用 Hive parser 4.0.1。
- 主解析器使用 Hive parser，而不是 Calcite。Calcite 更适合通用 SQL 和关系表达式处理，Hive parser 与 HiveQL 专有语法、DDL/DML 和生产执行口径更一致。
- Hive parser 相关类必须封装在 `HiveAstParserAdapter` 后面，业务血缘逻辑不得直接依赖 parser 的具体 AST 类型，便于后续按生产 Hive 版本调整依赖。
- 第一版不实现 TDSQL 落库和图数据库写入，只保证脚本解析结果结构完整。

## Main Entry

```java
public interface HiveLineageAnalyzer {
    LineageAnalyzeResult analyzeScript(LineageAnalyzeRequest request);

    default StatementLineageResult analyzeStatement(String sql, LineageAnalyzeOptions options) {
        return analyzeScript(LineageAnalyzeRequest.singleStatement(sql, options))
            .getStatements()
            .get(0);
    }
}
```

`analyzeScript` 是核心入口，因为解析器需要在脚本内维护 `set` 变量、`use database` 和临时表上下文。解析器内部必须逐条处理脚本中的所有语句，但结果和持久化粒度是“需要记录的单条 SQL 语句”：默认只有产生血缘、解析失败或需要诊断的语句返回 `StatementLineageResult`。

`analyzeStatement` 只作为便捷方法，不承载跨语句的 Session 级变量和临时表串联语义。

```java
public class LineageAnalyzeRequest {
    private String workflowId;
    private String taskId;
    private String taskVersion;
    private String defaultDatabase;
    private String rawScript;
    private Map<String, String> runtimeVariables;
    private LineageAnalyzeOptions options;
}
```

`taskVersion` 是血缘结果的业务隔离维度。不同版本任务产生的血缘需要可并存、可回溯、可比较。

解析选项：

```java
public class LineageAnalyzeOptions {
    private EnumSet<AnalyzeFeature> analyzeFeatures =
        EnumSet.of(AnalyzeFeature.TABLE_LINEAGE, AnalyzeFeature.COLUMN_LINEAGE);
    private LineageParseMode parseMode = LineageParseMode.TABLE_AND_COLUMN;
    private boolean parseJoinConditions = false;
    private boolean includeTemporaryLineage;
    private boolean includeIgnoredStatements;
    private boolean expandViewLineage = false;
    private int maxViewExpandDepth = 5;
    private boolean failFast;
}

public enum AnalyzeFeature {
    TABLE_LINEAGE,
    COLUMN_LINEAGE,
    JOIN_CONDITION
}

public enum LineageParseMode {
    TABLE_ONLY,
    COLUMN_ONLY,
    TABLE_AND_COLUMN
}
```

解析模式语义：

- `TABLE_ONLY`：只输出表级血缘，不输出字段级血缘；默认不查询 Hive 表字段元数据。
- `COLUMN_ONLY`：只输出字段级血缘，不输出表级血缘；需要按需加载源表和目标表字段元数据。
- `TABLE_AND_COLUMN`：同时输出表级血缘和字段级血缘；字段解析阶段按需加载元数据。

`AnalyzeFeature` 是更贴近功能的按需解析开关。`TABLE_LINEAGE`、`COLUMN_LINEAGE` 和 `JOIN_CONDITION` 彼此独立；其中 `JOIN_CONDITION` 表示 Join 条件解析，不属于正式血缘。调用方如果只需要解析 Join 条件，可以设置 `analyzeFeatures = [JOIN_CONDITION]`。

`parseJoinConditions` 是兼容式布尔开关，语义等价于在 `analyzeFeatures` 中包含 `JOIN_CONDITION`。如果同时存在 `analyzeFeatures` 和 `parseJoinConditions`，实现时应以 `analyzeFeatures` 为准，或在构造选项时统一归一化为 `AnalyzeFeature` 集合。

即使选择 `COLUMN_ONLY`，解析器内部仍需要识别输入表和输出表，用于判断语句是否满足“有输入 + 有输出”的正式血缘口径，并用于字段归属解析。但最终结果中的 `tableLineages` 应为空。

`includeIgnoredStatements` 默认为 `false`。默认情况下，成功执行解析但不产生血缘的语句，例如 `set`、`use`、纯查询语句、`insert ... values (...)`，只在解析器内部影响 Session 或被忽略，不返回 `StatementLineageResult`，也不建议落 TDSQL。若排查问题需要完整语句审计，可临时打开该选项。

当 `JOIN_CONDITION` 开启时，纯查询语句即使不产生正式血缘，只要包含显式 `JOIN ... ON ...`，或逗号连接中写在同一查询块 `WHERE` 后的跨来源关联条件，也应返回语句级解析结果。该结果的 `formalLineageProduced = false`，但 `joinConditions` 不为空，便于后续单独落 Join 条件表。

`expandViewLineage` 默认为 `false`。默认关闭是为了保证表级解析不额外访问元数据服务；当调用方明确需要追溯视图时，可开启该选项。开启后，解析器应通过元数据获取视图定义 SQL，并把视图血缘展开到真实上游表或上游视图。`maxViewExpandDepth` 用于限制视图递归展开深度，防止嵌套过深或异常循环。

## Architecture

```text
HiveScriptLineageAnalyzer
  ├── ScriptPreprocessor
  │   ├── removeComments(rawScript)
  │   └── splitStatements(scriptWithoutComments)
  ├── VariableResolver
  │   ├── runtimeVariables
  │   ├── sessionVariables
  │   └── builtInFallbackVariables
  ├── HiveStatementLineageAnalyzer
  │   ├── parseStatement(sql)
  │   ├── extractInputObjects(ast)
  │   ├── extractOutputObject(ast)
  │   ├── extractTableLineage(ast)
  │   ├── extractColumnLineage(ast, metadata, session)
  │   └── extractJoinConditions(ast, metadata, session)
  ├── MetadataProvider
  │   └── CachedMetadataProvider
  ├── SessionContext
  │   ├── currentDatabase
  │   ├── sessionVariables
  │   └── temporaryTables
  └── LineageComposer
      ├── composeStatementLineage()
      ├── expandTemporaryTables()
      └── buildResult()
```

## Parser Adapter

解析器适配层负责把 Hive parser 4.0.1 的 AST 转换为工具内部稳定模型。

```java
public interface HiveAstParserAdapter {
    ParsedHiveStatement parse(String normalizedSql);
}
```

内部模型至少需要表达以下信息：

- 语句类型：`INSERT_SELECT`、`CREATE_TABLE_AS_SELECT`、`CREATE_TEMPORARY_TABLE_AS_SELECT`、`CREATE_VIEW_AS_SELECT`、`SELECT`、`SET`、`USE`、`INSERT_VALUES`、`UNSUPPORTED`。
- 输出对象：目标表、临时表或视图。
- 输入对象：`from`、`join`、子查询、CTE、union 分支中涉及的表或视图。
- 查询结构：select item、目标字段、where、group by、union、join、lateral view、partition 信息。
- 字段表达式：表达式文本、表达式 AST、函数名、字段引用集合。
- Join 条件：显式 `JOIN ... ON ...` 子句、逗号连接的跨来源 `WHERE` 关联条件、Join 类型、所在查询块路径、关联条件表达式文本、关联条件字段引用集合。

业务血缘逻辑只消费 `ParsedHiveStatement`，不直接遍历 Hive parser AST。这样如果后续 Hive 4.0.1 与生产 Hive 版本存在差异，只需要替换 adapter。

## Script Preprocessing

脚本处理顺序：

1. 移除注释。
2. 按语句分割。
3. 逐句按顺序解析。

当前只定义占位方法，后续复用已有实现：

```java
public interface ScriptPreprocessor {
    String removeComments(String rawScript);

    List<String> splitStatements(String scriptWithoutComments);
}
```

占位实现：

```java
public class NoopScriptPreprocessor implements ScriptPreprocessor {
    @Override
    public String removeComments(String rawScript) {
        return rawScript;
    }

    @Override
    public List<String> splitStatements(String scriptWithoutComments) {
        return Collections.singletonList(scriptWithoutComments);
    }
}
```

## Session Context

一个 Hive 任务脚本对应一个解析 Session。

```java
public class SessionContext {
    private String currentDatabase;
    private Map<String, String> sessionVariables;
    private Map<TableKey, TemporaryTableLineage> temporaryTables;
}
```

语句处理规则：

- `set key=value`：更新 `sessionVariables`，不产生血缘。
- `use db`：更新 `currentDatabase`，不产生血缘。
- `create temporary table tmp as select ...`：生成临时表血缘并登记到 `temporaryTables`。
- 其他可产生血缘的语句：先做变量替换，再解析输入、输出、字段映射。

## Variable Resolution

变量替换优先级：

1. 当前脚本前序 `set` 语句写入的 Session 变量。
2. `LineageAnalyzeRequest.runtimeVariables`。
3. 内置兜底变量。
4. 仍无法识别时保留原始变量并返回 warning。

支持变量形式：

```text
${bizdate}
${hiveconf:bizdate}
${hivevar:bizdate}
${system:bizdate}
@{env}
```

建议内置兜底变量：

```java
Map<String, String> builtInFallbackVariables = Map.of(
    "bizdate", "19700101",
    "dt", "19700101",
    "yyyyMMdd", "19700101",
    "yyyy-MM-dd", "1970-01-01"
);
```

变量来源分三类：

- SQL 中通过 `set` 设置的 Session 变量，例如 `SET hivevar:VARI = 'xx'`，后续 `${hivevar:VARI}` 必须解析为 `xx`。
- 任务运行时替换变量，例如 `${bizdate}`、`${xxx}`，如果入参 `runtimeVariables` 提供则按入参替换，否则走兜底策略。
- 环境变量，例如 `@{xxx}`，解析工具不依赖真实运行环境，统一按兜底策略替换。

`set bizdate=20260629` 应同时支持后续通过 `${bizdate}`、`${hiveconf:bizdate}` 和 `${hivevar:bizdate}` 引用。若用户显式设置带 namespace 的变量，例如 `set hivevar:VARI='xx'`，则按原 key 记录，并同时保留可匹配的 namespace 形式。`rawSql` 必须保留变量原文，`normalizedSql` 保存替换后的 SQL。

## Metadata Cache

字段血缘解析依赖表字段元数据，表血缘解析通常不依赖表字段元数据。但如果开启视图展开，表血缘也需要查询对象级元数据来判断输入对象是物理表还是视图。因此元数据加载必须拆成“对象类型元数据”和“字段元数据”两类按需行为：

- `TABLE_ONLY` 模式：默认不调用 `MetadataProvider` 加载字段元数据；如果 `expandViewLineage = true`，需要查询对象类型元数据来判断输入对象是否为视图。
- `COLUMN_ONLY` 模式：只在解析字段映射、展开 `*`、解析无表别名前缀字段、目标字段对齐、临时表字段串联时加载必要元数据。
- `TABLE_AND_COLUMN` 模式：表级血缘先基于 SQL 结构解析，字段级血缘阶段再按需加载元数据。

对于 `insert overwrite table target select * from source` 这类未显式目标字段的单来源语句，如果目标表和源表字段元数据都可用，分别按来源表和目标表的字段顺序展开，并按位置生成字段血缘。源、目标有效字段数量不一致时返回 `COLUMN_COUNT_MISMATCH`，不生成部分字段血缘。

元数据查询通过接口隔离：

```java
public interface MetadataProvider {
    TableObjectMetadata getTableObjectMetadata(TableKey tableKey);

    TableMetadata getTableMetadata(TableKey tableKey);

    Map<TableKey, TableMetadata> getTableMetadataBatch(Collection<TableKey> tableKeys);
}
```

第一版实现只提供 mock provider，用于单元测试和解析流程联调。生产环境可以通过 Hive Metastore 元数据库 MySQL 表适配该接口，例如读取 `TBLS`、`DBS`、`SDS`、`COLUMNS_V2`、`PARTITION_KEYS` 等表来返回对象类型、字段列表、分区字段和视图 SQL。

对象级元数据用于判断对象类型，不要求加载完整字段列表：

```java
public class TableObjectMetadata {
    private TableKey tableKey;
    private TableObjectType objectType;
    private String viewOriginalSql;
    private String viewExpandedSql;
}
```

字段级表元数据用于字段血缘解析：

```java
public class TableMetadata {
    private TableKey tableKey;
    private TableObjectType objectType;
    private List<ColumnMetadata> columns;
    private List<ColumnMetadata> partitionColumns;
    private String viewOriginalSql;
    private String viewExpandedSql;
}

public enum TableObjectType {
    TABLE,
    VIEW
}
```

缓存分两层：

### Workflow Cache

单个工作流解析周期内有效。

```text
key: cluster + database + table
value: TableMetadata
```

用途：

- 多个任务解析同一张表时避免重复查元数据。
- 工作流解析完成后整体释放。

对象类型元数据和字段元数据可以使用不同缓存区或同一缓存值中的不同字段，但加载动作应当可拆分，避免只为了判断视图就加载完整字段列表。

视图还需要额外缓存“展开结果”，避免同一脚本或同一工作流中反复解析同一个视图定义 SQL：

```java
public class ViewLineageExpansion {
    private TableKey viewKey;
    private String viewSqlDigest;
    private Set<DataObjectRef> upstreamObjects;
    private List<ColumnLineage> columnMappings;
    private int expandDepth;
}
```

建议缓存 key：

```text
cluster + database + view + viewSqlDigest
```

其中 `viewSqlDigest` 基于 `viewExpandedSql` 或 `viewOriginalSql` 计算。这样视图定义变更后不会复用旧展开结果。缓存生命周期建议：

- Session 级缓存：当前任务脚本内有效，优先级最高。
- Workflow 级缓存：当前工作流解析周期内有效，避免多个任务重复展开同一个视图。
- Global TTL 缓存：可选，适合视图定义较稳定的环境；TTL 不宜过长。

### Global TTL Cache

进程级共享缓存。

建议策略：

- `maximumSize` 可配置。
- `expireAfterWrite` 可配置，默认 5 到 30 分钟。
- 对不存在的表做短 TTL negative cache，例如 1 分钟。
- 对同一 `TableKey` 的并发加载做 single-flight 合并。

`taskVersion` 不进入元数据缓存 key。任务版本只用于隔离血缘结果，不影响 Hive 表结构元数据。表结构元数据与任务版本无关。

元数据加载口径：

- 解析表级血缘只需要识别输入对象和输出对象，不需要表字段列表。
- 解析字段级血缘需要字段列表、分区字段、视图展开 SQL 等元数据。
- 如果输入对象是视图，解析器需要通过元数据获取视图定义 SQL，并递归解析视图定义来追溯真实上游。
- 如果调用方只请求表血缘，则元数据缺失不应影响表血缘输出，也不应产生字段元数据相关 warning。
- 如果调用方请求字段血缘，元数据缺失时应保留可推断的语句信息，并返回 `METADATA_NOT_FOUND` 或 `UNKNOWN_COLUMN_LINEAGE` warning。

## Lineage Generation Rules

正式血缘必须同时具备：

- 明确输入数据对象。
- 明确输出数据对象。

产生血缘的语句：

```sql
insert into target select ... from source;
insert overwrite table target select ... from source;
create table target as select ... from source;
create temporary table tmp as select ... from source;
create view v as select ... from source;
alter view v as select ... from source;
```

默认不产生正式血缘的语句：

```sql
select ... from source;
set key=value;
use db;
drop table t;
truncate table t;
create table t (...);
alter table t add partition (...);
msck repair table t;
analyze table t;
```

其中纯查询语句完全忽略，不产生正式血缘、不产生查询依赖信息、不写图数据库边。

特殊语句：

- `insert overwrite table target values (...)` 有输出但没有输入数据对象，完全忽略，不产生正式血缘、不产生写入事件、不写图数据库边。
- 静态分区字段来自常量，只有当该语句同时存在输入表和输出表时，才作为字段赋值信息输出，`sourceColumns = []`，`expression = constant(...)`，`lineageType = CONSTANT`；因为不存在输入字段，不应写字段到字段血缘边。
- 动态分区字段必须参与目标字段对齐，但是否产生字段血缘边取决于表达式中是否存在输入字段。若动态分区表达式只依赖运行变量、常量或无字段 UDF，例如 `ETL_HQT.bdpdatefunc('${work_day}',1,'yyyyMMdd',-1) AS BIZ_DATE`，则保留目标字段、表达式和函数名用于审计，`sourceColumns = []`，不写字段到字段血缘边。
- 条件字段默认不作为输出字段来源。`where BIZ_DATE = ...`、`join on ...`、窗口排序字段等只影响数据范围或行选择；除非后续单独设计“条件依赖/影响血缘”，否则不写入字段血缘边。
- `count(*)` 不依赖具体字段，但依赖输入表行集，字段血缘类型记为 `DERIVED`，并通过 `expression = "count(*)"` 和 `sourceObjects` 说明其依赖。

## Column Lineage Expression Handling

字段血缘解析的复杂度高于表血缘解析。字段血缘首期最重要的是解析出“源字段集合 -> 目标字段”和 `DIRECT/DERIVED/CONSTANT` 类型。表达式文本也需要尽量提取：实现必须先尝试从 SQL parser 的源码位置或 AST unparse 中获取表达式，只有在 parser 无法稳定提供表达式文本时才允许置空，并通过 `expressionExtracted` 标记。

核心策略：

- 对每个目标字段，先确定其对应的 select item 或分区表达式。
- 对 select item 表达式构建或使用 SQL AST 表达式树。
- 从表达式树中收集所有源字段引用，作为 `sourceColumns`。
- 尽量保留表达式原文或标准化表达式，作为 `expression`；实现必须尝试提取表达式文本。如果解析框架无法稳定提取，则 `expression` 可以为空，但 `expressionExtracted` 必须为 `false`。
- 根据表达式形态标记 `lineageType`，首期区分直接来源、加工来源和常量赋值。
- 对无法完全理解语义的函数或 UDF，不阻断解析；只要能提取参数中的源字段，就输出字段血缘。表达式提取失败只影响解释性字段，不影响字段血缘主链路。

示例：

```sql
insert into test_a(a1, a2)
select b1 + b2, b3
from test_b;
```

字段血缘：

```text
test_b.b1, test_b.b2 -> test_a.a1, expression: b1 + b2, lineageType: DERIVED
test_b.b3            -> test_a.a2, expression: b3,      lineageType: DIRECT
```

字段血缘类型首期保留三类：

- `DIRECT`：目标字段直接来自单个源字段，例如 `select b3 as a2`。
- `DERIVED`：目标字段经过加工生成，包括算术计算、普通函数、UDF、聚合、窗口函数、CASE WHEN、类型转换、字符串拼接、union 分支合并等。
- `CONSTANT`：目标字段由常量赋值得到，例如 `select 't1' as a1`、静态分区 `partition(dt='20260701')`、union 多分支常量值。

不建议首期把 `EXPRESSION`、`FUNCTION`、`AGGREGATION`、`WINDOW` 拆成多个血缘类型。原因是分类越细，实现和使用成本越高，但对首期图谱检索价值有限。函数名、表达式和复杂度可以作为附加字段尽力保留，满足排查和展示需要。

复杂表达式处理口径：

- `b1 + b2 as a1`：`sourceColumns = [b1, b2]`，`expression = "b1 + b2"`，`lineageType = DERIVED`，`complexExpression = true`。
- `nvl(b1, 0) as a1`：`sourceColumns = [b1]`，`expression = "nvl(b1, 0)"`，`lineageType = DERIVED`，`functionNames = ["nvl"]`。
- `sum(amount) as total_amount`：`sourceColumns = [amount]`，`expression = "sum(amount)"`，`lineageType = DERIVED`，`functionNames = ["sum"]`。
- `count(*) as cnt`：`sourceColumns = []`，`sourceObjects = [source table]`，`expression = "count(*)"`，`lineageType = DERIVED`，`functionNames = ["count"]`。
- `case when status = 1 then price else 0 end as valid_price`：`sourceColumns = [status, price]`，`lineageType = DERIVED`，`complexExpression = true`。
- `row_number() over(partition by user_id order by create_time) as rn`：`sourceColumns = [user_id, create_time]`，`expression = "row_number() over(partition by user_id order by create_time)"`，`lineageType = DERIVED`，`functionNames = ["row_number"]`。
- `'t1' as a1`：`sourceColumns = []`，`expression = "constant('t1')"`，`lineageType = CONSTANT`。
- `partition(source_system='profile', dt) select user_id, tag, dt ...`：`source_system` 作为 `constant('profile')` 输出，`dt` 对齐 `select` 中的动态分区字段。
- `partition(BIZ_DATE) select ..., ETL_HQT.bdpdatefunc('${work_day}',1,'yyyyMMdd',-1) as BIZ_DATE ...`：`BIZ_DATE` 保留为无源表达式赋值，`sourceColumns = []`，`expression = "ETL_HQT.bdpdatefunc(...)"`，但不产生字段到字段血缘边。
- `select t1.a from t1 union all select t2.b from t2`：`sourceColumns = [t1.a, t2.b]`，`expression = "union_all(t1.a, t2.b)"`，`lineageType = DERIVED`。
- `select t1.a from t1 union select t2.b from t2`：`sourceColumns = [t1.a, t2.b]`，`expression = "union_distinct(t1.a, t2.b)"`，`lineageType = DERIVED`。
- `select 'A' from t1 union all select 'B' from t2`：`sourceColumns = []`，`expression = "union_all(constant('A'), constant('B'))"`，`lineageType = CONSTANT`。
- 未知 UDF：提取参数中的字段，保留完整表达式，`lineageType = DERIVED`；如果参数字段也无法可靠识别，返回 `UNKNOWN_COLUMN_LINEAGE` warning。

复杂度评估：

- 表血缘复杂度较低，主要依赖 SQL 结构中的输入表和输出表。
- 字段血缘复杂度中到高，依赖元数据、目标字段对齐、表达式 AST 遍历、函数参数识别、`*` 展开、别名解析、临时表字段展开。
- 表达式文本提取的准确率取决于 SQL parser 能否提供表达式源码位置或稳定的 AST unparse 能力。如果 parser 支持源码 span，提取原表达式准确率较高；如果只能从 AST 反向生成，表达式语义通常准确，但格式、大小写、括号可能与原 SQL 不完全一致。
- 首期实现建议采用“字段集合 + 简单类型”为硬要求，同时必须尝试提取“表达式文本 + 函数名 + 复杂度标记”。这样即使少数表达式文本无法稳定提取，也能保证字段血缘主链路可用，并能通过 `expressionExtracted = false` 暴露提取缺口。

## Temporary Table Expansion

临时表是 Session 内中间节点。解析器先记录临时表自身血缘，再在最终结果中展开。

示例：

```sql
create temporary table tmp_user as
select id, name, age
from ods.user;

insert overwrite table dwd.user_profile
select id, name, age + 1 as next_age
from tmp_user;
```

内部血缘：

```text
tmp_user.id       <- ods.user.id
tmp_user.name     <- ods.user.name
tmp_user.age      <- ods.user.age
dwd.user_profile.id       <- tmp_user.id
dwd.user_profile.name     <- tmp_user.name
dwd.user_profile.next_age <- tmp_user.age
```

最终默认输出：

```text
dwd.user_profile.id       <- ods.user.id
dwd.user_profile.name     <- ods.user.name
dwd.user_profile.next_age <- ods.user.age
```

可通过 `includeTemporaryLineage` 决定是否额外输出临时表中间血缘。

## View Detection And Lineage Expansion

Hive 视图不能简单当成普通物理表停住，否则血缘只能追到视图，无法追到真实上游表。解析器需要在元数据中识别输入对象是否为视图，并在需要时展开视图定义 SQL。

视图判断不能依赖 SQL 文本或表名规则。SQL parser 只能告诉解析器“读取了哪个对象名”，不能可靠判断该对象是物理表还是视图。对象类型必须来自元数据服务。

判定流程：

1. 从 SQL AST 中提取输入对象名，例如 `dim.v_user`。
2. 先查 `SessionContext.temporaryTables`。如果命中，按临时表处理，不查 Hive Metastore。
3. 如果未命中临时表，并且 `expandViewLineage = true`，则调用 `MetadataProvider` 获取对象类型。
4. 如果 `objectType = TABLE`，按物理表处理。
5. 如果 `objectType = VIEW`，按视图处理，并读取 `viewExpandedSql` 或 `viewOriginalSql` 展开血缘。
6. 如果对象类型元数据缺失，表血缘可先保留该对象本身作为上游；字段血缘返回 `METADATA_NOT_FOUND` 或视图相关 warning。

处理策略：

- 当 SQL 读取对象是物理表时，按普通表处理。
- 当 SQL 读取对象是视图时，从 `TableMetadata.viewExpandedSql` 或 `viewOriginalSql` 获取视图定义。
- 对视图定义 SQL 递归执行解析，得到视图字段到上游字段、视图对象到上游对象的映射，并缓存为 `ViewLineageExpansion`。
- 外层 SQL 读取视图字段时，通过视图字段映射继续展开到真实上游字段。
- 如果视图依赖另一个视图，继续递归展开，直到遇到物理表、临时表、元数据缺失或达到 `maxViewExpandDepth`。
- 如果检测到视图循环依赖，停止展开并返回 `VIEW_EXPANSION_CYCLE` warning。

示例：

```sql
create view dim.v_user as
select id, name
from ods.user;

insert overwrite table dwd.user_profile
select id, name
from dim.v_user;
```

默认展开后的表血缘：

```text
ods.user -> dwd.user_profile
```

默认展开后的字段血缘：

```text
ods.user.id   -> dwd.user_profile.id
ods.user.name -> dwd.user_profile.name
```

如果 `expandViewLineage = false`，则表血缘可以停留在：

```text
dim.v_user -> dwd.user_profile
```

但字段血缘解析若需要追溯真实上游，仍建议开启视图展开。字段血缘模式下，如果视图定义缺失，应返回 `VIEW_METADATA_NOT_FOUND` 或 `VIEW_EXPANSION_FAILED` warning，并尽量保留视图到目标对象的可推断血缘。

## Result Model

```java
public class LineageAnalyzeResult {
    private String workflowId;
    private String taskId;
    private String taskVersion;
    private List<StatementLineageResult> statements;
    private List<LineageWarning> warnings;
    private int totalStatementCount;
    private int ignoredStatementCount;
    private int failedStatementCount;
}
```

`LineageAnalyzeResult` 是内存中的批量返回包装，不建议作为一行整体持久化，也不默认保存完整 `rawScript` 到结果中。持久化到 TDSQL 时，推荐把 `statements` 拆开，一条 `StatementLineageResult` 对应一行记录；写图数据库时，推荐以每条语句中的 `tableLineages` 和 `columnLineages` 生成边。

`statements` 默认不等于脚本中的全部语句，而是可持久化语句结果集合。默认进入集合的语句包括：

- 产生正式血缘的语句。
- 解析失败的语句。
- 解析部分成功且带有需要排查 warning 的语句。
- 当 `includeIgnoredStatements = true` 时，被忽略的成功语句也可以进入集合。

语句级结果：

```java
public class StatementLineageResult {
    private String workflowId;
    private String taskId;
    private String taskVersion;
    private int statementIndex;
    private String statementId;
    private StatementType statementType;
    private String rawSql;
    private String normalizedSql;
    private Integer parseStatusCode;
    private boolean formalLineageProduced;
    private DataObjectRef outputObject;
    private Set<DataObjectRef> inputObjects;
    private LineageParseMode parseMode;
    private String skipReason;
    private String failureCode;
    private String failureMessage;
    private List<TableLineage> tableLineages;
    private List<ColumnLineage> columnLineages;
    private List<JoinCondition> joinConditions;
    private List<LineageWarning> warnings;
}
```

```java
public enum StatementParseStatus {
    SUCCESS(0),
    PARTIAL_SUCCESS(1),
    FAILED(2),
    IGNORED(3);

    private final int code;
}
```

字段含义：

- `statementId`：建议由 `workflowId + taskId + taskVersion + statementIndex` 生成，用作 TDSQL 主键或唯一键的一部分，也可作为图数据库边的来源引用。
- `rawSql`：语句分割后、变量替换前的原始语句文本。应尽量保留用户脚本中的原始写法。
- `normalizedSql`：变量替换、默认库补全、必要标准化后的语句文本，用于实际解析和排查问题。
- `parseStatusCode`：语句解析状态编码。解析失败的语句必须落结果，便于后续按状态码从 TDSQL 查询。
- `parseMode`：本次语句解析使用的解析模式，用于解释为何只存在表血缘或只存在字段血缘。
- `formalLineageProduced`：该语句是否产生正式血缘。`set`、`use`、单独 `select` 通常为 `false`。
- `skipReason`：当 `parseStatusCode = 3` 时记录忽略原因，例如 `SESSION_SET`、`USE_DATABASE`、`PURE_QUERY`、`INSERT_VALUES`。
- `failureCode` / `failureMessage`：当 `parseStatusCode = 2` 或 `1` 时记录失败类型和原因。
- `tableLineages` 和 `columnLineages` 只保存当前语句按 `parseMode` 产生的正式血缘。若当前语句读取临时表，结果应已经展开到真实上游对象。
- `joinConditions` 保存当前语句中的显式 `JOIN ... ON ...` 条件解析结果，以及逗号连接中从 `WHERE` 提取出的跨来源关联条件。Join 条件解析不是正式血缘，可以单独落库，并通过 `statementId + joinIndex` 唯一定位。
- 临时表语句可以保留自身语句级血缘，用于审计和后续串联。写图数据库时，如果不希望图中出现临时节点，可以由图写入层跳过临时表目标，或仅写后续语句展开后的真实源表到目标表边。

建议血缘唯一键：

```text
workflowId + taskId + taskVersion + statementIndex + targetObject + targetColumn
```

建议 TDSQL 存储方式：

```text
lineage_statement_result
  statement_id
  workflow_id
  task_id
  task_version
  statement_index
  statement_type
  raw_sql
  normalized_sql
  parse_status_code
  formal_lineage_produced
  skip_reason
  failure_code
  failure_message
  input_objects_json
  output_object_json
  table_lineages_json
  column_lineages_json
  join_conditions_json
  warnings_json
  created_at
```

这种设计避免一段大脚本对应一条超大记录。图数据库写入时也不依赖脚本级大对象，而是逐条语句生成：

```text
table edge:  source_table -> target_table, properties: statement_id, operation, task_version
column edge: source_column -> target_column, properties: statement_id, expression, lineage_type, task_version
```

如果 Join 条件解析结果单独落库，建议一条 `JoinCondition` 对应一行：

```text
sql_join_condition_result
  statement_id
  workflow_id
  task_id
  task_version
  statement_index
  join_index
  query_block_path
  join_type
  join_type_name
  join_expression
  direct_join_objects_json
  direct_join_columns_json
  join_tables_json
  join_columns_json
  raw_sql
  created_at
```

表血缘：

```java
public class TableLineage {
    private DataObjectRef targetObject;
    private Set<DataObjectRef> sourceObjects;
    private LineageOperation operation;
    private int statementIndex;
    private boolean throughTemporaryTable;
}
```

字段血缘：

```java
public class ColumnLineage {
    private ColumnRef targetColumn;
    private Set<ColumnRef> sourceColumns;
    private Set<DataObjectRef> sourceObjects;
    private String expression;
    private boolean expressionExtracted;
    private LineageType lineageType;
    private Set<String> functionNames;
    private boolean complexExpression;
    private int statementIndex;
}
```

Join 条件：

```java
public class JoinCondition {
    private String workflowId;
    private String taskId;
    private String taskVersion;
    private int statementIndex;
    private String statementId;
    private int joinIndex;
    private String queryBlockPath;
    private int joinType;
    private String joinTypeName;
    private List<DataObjectRef> directJoinObjects;
    private List<ColumnRef> directJoinColumns;
    private List<DataObjectRef> joinTables;
    private List<ColumnRef> joinColumns;
    private String joinExpression;
    private String rawSql;
}
```

`joinIndex` 在同一条 SQL 语句内全局递增，即使 Join 条件分布在 CTE、子查询、派生表和主查询中也不能在各查询块内重新计数。`queryBlockPath` 用于标识 Join 条件所在查询块，例如 `root`、`cte.x`、`root.where.exists[1]`。

`directJoinObjects` 表示当前查询块中 `ON` 表达式直接引用到的对象。若直接对象是物理表，则输出完整物理表名；若直接对象是视图，则输出视图名；若直接对象是 CTE、临时表或派生表，则输出当前查询块中的名称或别名。`directJoinColumns` 表示当前查询块中 `ON` 表达式直接引用的字段，例如 `x.id`、`a.id`。`joinTables` 和 `joinColumns` 表示尽量追溯后的物理表和物理字段集合。四个集合都必须按完整名称去重，并保留首次出现顺序。

如果视图、CTE、临时表或派生表字段无法唯一追溯到物理字段，解析器必须保留 `directJoinObjects`、`directJoinColumns` 和原始 `joinExpression`，并返回字段追溯不明确的 warning。`joinTables` 和 `joinColumns` 可以只包含已成功追溯的物理对象和字段。

当 `directJoinObjects` 命中 `SessionContext.temporaryTables` 时，Join 条件解析必须优先使用当前 Session 中登记的临时表字段映射进行追溯，不应查询 Hive Metastore。临时表字段能唯一映射到物理字段时，`joinTables` 和 `joinColumns` 输出物理表与物理字段；无法唯一映射时，保留临时表名和直接字段，并返回临时表字段追溯 warning。

类型：

```java
enum LineageType {
    DIRECT,
    DERIVED,
    CONSTANT
}
```

## Example

Input:

```sql
set bizdate=20260629;

create temporary table tmp_user as
select id, name, age
from ods.user
where dt='${hiveconf:bizdate}';

insert overwrite table dwd.user_profile partition(dt='${hiveconf:bizdate}')
select id,
       name,
       age + 1 as next_age
from tmp_user;

select id from ods.user;
```

Output:

```json
{
  "workflowId": "wf_001",
  "taskId": "task_hive_001",
  "taskVersion": "V2",
  "totalStatementCount": 4,
  "ignoredStatementCount": 2,
  "failedStatementCount": 0,
  "statements": [
    {
      "workflowId": "wf_001",
      "taskId": "task_hive_001",
      "taskVersion": "V2",
      "statementIndex": 1,
      "statementId": "wf_001_task_hive_001_V2_1",
      "statementType": "CREATE_TEMPORARY_TABLE_AS_SELECT",
      "rawSql": "create temporary table tmp_user as\nselect id, name, age\nfrom ods.user\nwhere dt='${hiveconf:bizdate}'",
      "normalizedSql": "create temporary table tmp_user as\nselect id, name, age\nfrom ods.user\nwhere dt='20260629'",
      "parseStatusCode": 0,
      "formalLineageProduced": true,
      "parseMode": "TABLE_AND_COLUMN",
      "inputObjects": ["ods.user"],
      "outputObject": "tmp_user",
      "tableLineages": [
        {
          "targetObject": "tmp_user",
          "sourceObjects": ["ods.user"],
          "operation": "CREATE_TEMPORARY_TABLE_AS_SELECT",
          "statementIndex": 1,
          "throughTemporaryTable": false
        }
      ],
      "columnLineages": [
        {
          "targetColumn": "tmp_user.id",
          "sourceColumns": ["ods.user.id"],
          "sourceObjects": ["ods.user"],
          "expression": "id",
          "lineageType": "DIRECT",
          "statementIndex": 1
        },
        {
          "targetColumn": "tmp_user.name",
          "sourceColumns": ["ods.user.name"],
          "sourceObjects": ["ods.user"],
          "expression": "name",
          "lineageType": "DIRECT",
          "statementIndex": 1
        },
        {
          "targetColumn": "tmp_user.age",
          "sourceColumns": ["ods.user.age"],
          "sourceObjects": ["ods.user"],
          "expression": "age",
          "lineageType": "DIRECT",
          "statementIndex": 1
        }
      ],
      "warnings": []
    },
    {
      "workflowId": "wf_001",
      "taskId": "task_hive_001",
      "taskVersion": "V2",
      "statementIndex": 2,
      "statementId": "wf_001_task_hive_001_V2_2",
      "statementType": "INSERT_OVERWRITE",
      "rawSql": "insert overwrite table dwd.user_profile partition(dt='${hiveconf:bizdate}')\nselect id,\n       name,\n       age + 1 as next_age\nfrom tmp_user",
      "normalizedSql": "insert overwrite table dwd.user_profile partition(dt='20260629')\nselect id,\n       name,\n       age + 1 as next_age\nfrom tmp_user",
      "parseStatusCode": 0,
      "formalLineageProduced": true,
      "parseMode": "TABLE_AND_COLUMN",
      "inputObjects": ["tmp_user"],
      "outputObject": "dwd.user_profile",
      "tableLineages": [
        {
          "targetObject": "dwd.user_profile",
          "sourceObjects": ["ods.user"],
          "operation": "INSERT_OVERWRITE",
          "statementIndex": 2,
          "throughTemporaryTable": true
        }
      ],
      "columnLineages": [
        {
          "targetColumn": "dwd.user_profile.id",
          "sourceColumns": ["ods.user.id"],
          "sourceObjects": ["ods.user"],
          "expression": "id",
          "lineageType": "DIRECT",
          "statementIndex": 2
        },
        {
          "targetColumn": "dwd.user_profile.name",
          "sourceColumns": ["ods.user.name"],
          "sourceObjects": ["ods.user"],
          "expression": "name",
          "lineageType": "DIRECT",
          "statementIndex": 2
        },
        {
          "targetColumn": "dwd.user_profile.next_age",
          "sourceColumns": ["ods.user.age"],
          "sourceObjects": ["ods.user"],
          "expression": "age + 1",
          "lineageType": "DERIVED",
          "statementIndex": 2
        },
        {
          "targetColumn": "dwd.user_profile.dt",
          "sourceColumns": [],
          "sourceObjects": [],
          "expression": "constant('20260629')",
          "lineageType": "CONSTANT",
          "statementIndex": 2
        }
      ],
      "warnings": []
    }
  ],
  "warnings": []
}
```

这个 JSON 是接口批量返回示例。落 TDSQL 时不建议整体保存为一行，而是把 `statements` 中的对象分别保存。默认情况下，第一条 `set bizdate=20260629` 只用于更新解析 Session，不返回结果；最后一条 `select id from ods.user` 是纯查询语句，完全忽略，不返回结果、不产生查询依赖信息、不写图数据库边。`ignoredStatementCount = 2` 用于统计这类被成功处理但不落结果的语句数量。

解析失败示例：

```json
{
  "workflowId": "wf_001",
  "taskId": "task_hive_001",
  "taskVersion": "V2",
  "statementIndex": 4,
  "statementId": "wf_001_task_hive_001_V2_4",
  "statementType": "UNKNOWN",
  "rawSql": "insert overwrite table dwd.bad select from",
  "normalizedSql": "insert overwrite table dwd.bad select from",
  "parseStatusCode": 2,
  "formalLineageProduced": false,
  "parseMode": "TABLE_AND_COLUMN",
  "failureCode": "SQL_PARSE_ERROR",
  "failureMessage": "Cannot parse select list",
  "tableLineages": [],
  "columnLineages": [],
  "warnings": []
}
```

## Error Handling

解析器不应因为单条语句失败导致整个脚本失败，除非配置 `failFast=true`。

解析状态口径：

- `0 SUCCESS`：语句解析成功，并且如果满足血缘口径则已产出对应血缘。
- `1 PARTIAL_SUCCESS`：语句部分解析成功，例如表血缘可推断但字段血缘因元数据缺失或表达式无法完全解析而降级。
- `2 FAILED`：语句无法解析或解析过程中发生不可恢复错误。该语句必须返回 `StatementLineageResult` 并可持久化到 TDSQL。
- `3 IGNORED`：语句成功处理但不需要产生或持久化血缘结果。默认不返回，仅在 `includeIgnoredStatements = true` 时返回。

默认持久化口径：

- `0 SUCCESS` 且产生血缘：持久化。
- `1 PARTIAL_SUCCESS`：持久化。
- `2 FAILED`：持久化。
- `3 IGNORED`：默认不持久化。

warning 类型建议：

- `UNRESOLVED_VARIABLE`
- `METADATA_NOT_FOUND`
- `UNSUPPORTED_STATEMENT`
- `AMBIGUOUS_COLUMN`
- `UNKNOWN_COLUMN_LINEAGE`
- `TEMP_TABLE_NOT_FOUND`
- `VIEW_METADATA_NOT_FOUND`
- `VIEW_EXPANSION_FAILED`
- `VIEW_EXPANSION_DEPTH_EXCEEDED`
- `VIEW_EXPANSION_CYCLE`

每个 warning 需要包含：

- `statementId`
- `statementIndex`
- `statementSql`
- `code`
- `message`

## Fixed Decisions

- `insert ... values (...)` 完全忽略，不产生正式血缘、不产生写入事件、不写图数据库边。
- 纯查询语句完全忽略，不产生正式血缘、不产生查询依赖信息、不写图数据库边。
- 表结构元数据与任务版本无关，元数据缓存 key 不包含 `taskVersion`。
