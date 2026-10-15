# v2 SQL 语法支持与场景覆盖

面向使用者的 SQL 语法与血缘示例见 [Hive SQL Lineage Reference](../../../../../docs/hive-sql-lineage-reference.md)；逐项“已覆盖 / 未覆盖”证据见 [开发验证矩阵附录](../../../../../docs/v2-sql-syntax-coverage-matrix.md)。本文保留完整 SQL 资源索引、测试运行方法和验证记录。

本清单按 Hive 4.0.1 前端、语法归一化、语义绑定和血缘结果分别梳理。文件型集成测试分别验证已实现结构、效果和明确边界；语法树能表示某种结构，不等于实际 SQL 已能通过 lexer/parser，也不等于能生成完整字段血缘。不能据此承诺全部 Hive 词库或所有关键字组合 100% 支持；DDL 的逐项证据和剩余范围见 [关键字与 DDL 覆盖审计](v2_keyword_ddl_coverage_audit.md)。

## 支持语法清单

| 类别 | 语句或语法 | 当前支持与边界 |
| --- | --- | --- |
| INSERT | `INSERT INTO`、`INSERT OVERWRITE TABLE`、显式目标列 | 产生写入贡献和表/字段血缘；显式列按声明顺序映射，不采用目标元数据顺序。 |
| 分区写入 | 静态、动态、混合 `PARTITION` | 分别保留常量赋值与查询输出；动态分区消耗末尾输出，静态分区不占用业务查询输出。 |
| 多目标写入 | `FROM ... INSERT ... INSERT ...` | 共享 FROM 可包含多层 derived 和 UNION；各分支有独立 `BoundWrite`、目标和写入模式。v1 投影接口的单目标字段不能替代原生多目标断言。 |
| CTAS | `CREATE TABLE ... AS SELECT` | 查询可组合嵌套、聚合、CTE；注册当前脚本定义和输出 schema，后续读取可复用。 |
| 临时 CTAS | `CREATE TEMPORARY TABLE ... AS SELECT` | 支持多阶段临时表链；`includeTemporaryLineage=false` 时创建语句为 `IGNORED`，定义仍影响后续血缘。 |
| 视图 | `CREATE [OR REPLACE] VIEW ... AS SELECT`、`ALTER VIEW ... AS SELECT`、声明视图列 | 建立/替换定义，保留显式列名、COMMENT、OR REPLACE 标志及视图中间节点；`expandViewLineage=true` 展开上游定义和嵌套视图。 |
| SELECT 投影 | 显式字段/表达式、别名、常量、`DISTINCT` | 作为写入来源产生映射；独立纯 SELECT 在 `CURRENT_API` 口径默认忽略正式血缘。 |
| 星号 | `*`、`alias.*` | 展开依赖源表 schema 与目标映射策略；schema 缺失保留未知宽度，不伪造字段数量。 |
| FROM 嵌套 | derived table，多层嵌套、两侧嵌套 JOIN、内层聚合/集合/窗口 | 每层有独立 query block、作用域和输出 slot，字段按层回溯。 |
| CTE | 多个 `WITH ... AS`、依赖链、列别名、重复引用/self-join、局部同名遮蔽 | 复用定义时每个引用有独立 relation instance；列别名通过前端扩展保存。兼容口径允许前序纯 SELECT 的 CTE 在后续语句复用。 |
| 递归 CTE | 定义内部再次引用自身 | 语义阶段 `FAILED`，诊断为 `RECURSIVE_DEFINITION`；不是已支持的递归执行语义。 |
| JOIN | INNER、LEFT/RIGHT/FULL OUTER、LEFT SEMI、CROSS、逗号 JOIN | 保存类型、ON 或逗号 WHERE 条件、直接列及回溯物理列；允许输入为 CTE/derived/aggregate。 |
| LEFT ANTI JOIN | 实际 SQL 的 `LEFT ANTI JOIN` | 当前 lexer 缺少 `KW_ANTI`，真实前端以 `HIVE_SYNTAX_ERROR` 拒绝。normalizer 存在 AST 分支不能作为 SQL 支持依据；专属边界文件固定此行为。 |
| 集合查询 | UNION、INTERSECT、EXCEPT 的 ALL/DISTINCT | 可以位于 derived/CTE、与 JOIN 等组合；按输出位置汇合分支血缘，不保证结果行集合等同于所有源行。 |
| WHERE / HAVING | 普通表达式、嵌套查询、聚合后 HAVING | 遍历谓词中的来源；写入值依赖与过滤/行集依赖分别保存在原生 facts。 |
| 存在性子查询 | `EXISTS`、`NOT EXISTS` | 支持相关引用与多层相关引用；保留其输入表，不能将仅用于过滤的表字段误算为投影值来源。 |
| 集合成员子查询 | `IN (SELECT ...)`、`NOT IN (SELECT ...)` | 支持子查询输入、probe 表达式和相关作用域；单列输出约束保留为诊断。 |
| 标量子查询 | 非相关/相关 SELECT、derived 聚合，以及 CASE/CAST/函数/BETWEEN/IN-list/下标/窗口中的嵌套 | 已知来源继续保留；运行时返回行数不能由解析保证，原生 scalar cardinality 为 `UNKNOWN` 并带 `SCALAR_CARDINALITY_UNKNOWN`，facts coverage 保留 `PARTIAL`。 |
| 标量宽度边界 | `(SELECT a, b ...)` 用作标量值 | 语法可以通过；字段模式生成 `UnknownExpression` 和 `SCALAR_SUBQUERY_WIDTH_UNKNOWN`，结果为 `PARTIAL_SUCCESS`，不是完整映射或语法失败。 |
| 分组聚合 | GROUP BY、HAVING、聚合 DISTINCT、`COUNT(*)` | 保留组键、参数值来源和行集依赖；`COUNT(*)` 不伪造源字段。 |
| 扩展分组 | ROLLUP、CUBE、GROUPING SETS | 前端保存语法，binder 汇合分组字段依赖；不模拟 grouping sets 的执行行展开。 |
| 窗口 | inline/named window、命名窗口继承、PARTITION/ORDER、ROWS/RANGE/PRECEDING/FOLLOWING 边界 | 构建 window computations，继承分组/排序/frame 并保留参数依赖；继承定义中的子查询也需遍历。未知或循环窗口明确保留 `UNKNOWN_WINDOW/RECURSIVE_WINDOW_DEFINITION` 和部分结果。 |
| 生成器 | LATERAL VIEW/OUTER、explode、posexplode、连续 generator | 保存生成列、outer 标志和参数依赖；生成列回溯源参数，允许外层 derived/CTE。 |
| 查询尾部 | ORDER BY、SORT BY、ASC/DESC、NULLS FIRST/LAST、LIMIT/offset | 保存排序/限制关系和字段依赖；GROUP/ORDER/SORT 中标量子查询的来源均有独立文件检查。 |
| 分发与采样 | DISTRIBUTE BY、CLUSTER BY、TABLESAMPLE bucket/percent/rows/byte length | 结构可达；分发/聚簇表达式中的子查询仍遍历其输入。采样单位与参数分别保存，ROWS/BYTES 不当成额外数值参数；当前 binder 未完整建模分发/采样执行语义。 |
| 运算表达式 | 数学、比较、NULL-safe equal、逻辑、位运算、LIKE/RLIKE/REGEXP、unary | 保存操作数依赖和表达式；支持 BETWEEN/NOT BETWEEN、IN-list、IS NULL/IS NOT NULL。 |
| 条件与类型转换 | simple/searched CASE、CAST | 保留条件、分支值和转换参数来源；嵌套查询不能被外层表达式隐藏。 |
| 函数 | 内置 scalar/aggregate/window/generator、函数 DISTINCT/星号参数 | 通过 function catalog 和参数绑定保存依赖；不实际执行函数。 |
| 未知 UDF | 未声明的函数名 | 保留已知参数来源，并以 `OPAQUE_FUNCTION` 明确未知行为；binding 成功时 statement status 可为 `SUCCESS`，原生 facts coverage 为 `PARTIAL`。 |
| 复杂类型访问 | 数组/map 下标、struct/嵌套字段、标量 struct 的字段访问 | 有 typed 元数据时校验字段路径并保留来源，不将下标中的查询遗漏。v1 元数据不提供字段类型，struct 路径无法验证时保留 `UNRESOLVED_FIELD_PATH` 和未知映射；另以 typed native fixture 验证完整路径。 |
| 名称与脚本 | 引号/反引号别名、注释、字符串中的分号 | 保留原始 SQL 和声明拼写；脚本拆分不能将字符串或注释里的分号当分隔符。 |
| 字面量与类型语法 | 数字、字符串/相邻字符串、NULL/布尔、DATE/TIMESTAMP；primitive、ARRAY/MAP/STRUCT/UNIONTYPE 类型参数 | normalizer 保留 literal kind、类型名、数值参数和递归字段结构；ARRAY 的 native canonical 名 `list` 转换为语义 ARRAY，UNIONTYPE 的原生 coltypelist 容器在语义转换中展开，catalog 深层类型有断言。精度/长度/约束不在分析时执行；v1 provider 自身不提供类型。 |
| INTERVAL 边界 | 日/月/年等区间表达式 | native AST 将代表语句表示为 `internal_interval`，当前以 opaque function 保留 `OPAQUE_FUNCTION` / facts `PARTIAL`；不宣称完整 interval 类型或计算语义。 |
| JOIN USING 边界 | `JOIN ... USING (id)` | parser 可达但 USING 列表未完整归一化；保留未知 ON、`UNSUPPORTED_EXPRESSION/OUTPUT_SCHEMA_UNKNOWN` 和部分结果，已知投影来源仍保存。 |
| 列归属不确定 | 多来源的无前缀同名字段 | `AMBIGUOUS_COLUMN_OWNER`，字段模式为 `PARTIAL_SUCCESS`；保留表来源，不随意选一个列归属。 |
| UPDATE / DELETE | 多 SET 赋值、WHERE 条件 | UPDATE 保留独立赋值与更新前字段依赖；DELETE 保留条件/行集而不伪造字段写入。 |
| MERGE | derived 来源、matched UPDATE/DELETE、not matched INSERT | 每个动作有独立写入贡献和条件；INSERT 按目标 schema 顺序映射，不能丢失其余动作。 |
| 会话命令 | SET、USE、hivevar/hiveconf 变量替换 | 命令为 `IGNORED`，对应会话效果提交后影响后续 SQL；同一脚本内保留 raw 与 normalized SQL。 |
| 资源/函数/SOURCE | ADD/DELETE/LIST JAR/FILE/ARCHIVE、CREATE/DROP FUNCTION、SOURCE | 保存命令类型，`IGNORED` 且无正式血缘；分析器不读取 SOURCE 文件或执行外部资源。 |
| 普通 catalog DDL | CREATE/TEMPORARY/EXTERNAL TABLE、声明列/分区、DROP TABLE/VIEW、ALTER TABLE/VIEW RENAME、整表 ADD/REPLACE/CHANGE COLUMNS | 开启 `CATALOG_SIMULATION` 后提议并提交效果；维护对象身份、临时遮蔽、重命名后的 lookup aliases 和 schema/分区角色。CHANGE 支持类型、COMMENT、FIRST/AFTER；REPLACE 也拒绝与保留分区键重名的数据列，非法修改失败且不提交。普通声明不产生数据写入血缘。 |
| 已建模 DDL 选项 | TEMPORARY/EXTERNAL、IF EXISTS/IF NOT EXISTS、列/表/视图 COMMENT、PARTITIONED BY、DELIMITED 六类分隔符、SerDe class/props、generic STORED AS、CLUSTERED/SORTED buckets、LOCATION、TBLPROPERTIES | 具体 getters 有断言；修复 Hive CREATE AST 的 buckets/Serde wrappers 丢失。IF 条件按对象存在性确定；注释和存储 option 保存语法，不执行元数据写入或格式读写。 |
| CREATE storage/constraint 边界 | INPUTFORMAT/OUTPUTFORMAT、STORED BY、SKEWED、PRIMARY KEY/NOT NULL | 代表 SQL 被 parser 接受，但这些选项/语义未完整建模；不宣称结构化完整保存或约束校验。约束节点不能误作列 COMMENT。 |
| catalog 未知边界 | CREATE LIKE、分区作用域列修改、ALTER partition/properties/location/storage/maintenance、TRUNCATE | LIKE 保存来源名但 schema 为 UNKNOWN，诊断 `CREATE_LIKE_SCHEMA_UNKNOWN`。未绑定 ALTER 保存动作及对象范围 `CATALOG_ACTION_NOT_BOUND` unknown effect，后续相关读取保留不确定性；TRUNCATE 为 `TRUNCATE_VERSION_UNKNOWN`，均无字段赋值。 |
| VALUES / DIRECTORY | VALUES、多行 VALUES、DIRECTORY/LOCAL DIRECTORY | 可以完成语法审计，默认 `IGNORED` 且没有写入贡献/正式表列血缘；后续正常语句仍解析。 |
| LOAD DATA | LOCAL/distributed path、OVERWRITE/append、静态分区 | 必须开启 `EXTERNAL_DATA_LINEAGE`；未开启为 `UNSUPPORTED_STATEMENT` 并带 `EXTERNAL_DATA_NOT_REQUESTED`。开启时保留路径身份，但外部 schema 未知，`PARTIAL_SUCCESS` 并带 `EXTERNAL_SCHEMA_UNKNOWN`。 |
| 其他审计语句 | DATABASE/SCHEMA、MACRO/RELOAD FUNCTIONS、DROP MATERIALIZED VIEW、MSCK/ANALYZE、SHOW/DESCRIBE/EXPLAIN | 代表组合被 parser 接受后归类为 Other 审计，IGNORED、无 bound unit/effects/正式血缘；没有目录、权限、维护或宏执行模拟。 |
| 未支持数据变更 | CREATE MATERIALIZED VIEW AS SELECT、ALTER MATERIALIZED VIEW REBUILD、IMPORT/EXPORT | 前端接受但缺少数据写入绑定，明确 `FAILED/UNSUPPORTED_STATEMENT`，无效果；不能视作正常审计忽略。 |
| malformed DDL | 无查询 DDL 的缺失类型等错误 | 限于非查询 DDL 恢复为 frontend PARTIAL 的 `INVALID_DDL` 审计，不创建有效定义/效果；含查询的 malformed CTAS 仍解析失败。 |

语法分派依据是 [HiveSyntaxNormalizer](../../../../main/java/com/panfan/hivelineage/v2/dialect/hive/HiveSyntaxNormalizer.java)、[HiveQueryNormalizer](../../../../main/java/com/panfan/hivelineage/v2/dialect/hive/HiveQueryNormalizer.java) 和 [HiveExpressionNormalizer](../../../../main/java/com/panfan/hivelineage/v2/dialect/hive/HiveExpressionNormalizer.java)；实际绑定依据是 [QueryBinder](../../../../main/java/com/panfan/hivelineage/v2/semantic/QueryBinder.java)、[ExpressionBinder](../../../../main/java/com/panfan/hivelineage/v2/semantic/ExpressionBinder.java) 和 [StatementBinder](../../../../main/java/com/panfan/hivelineage/v2/semantic/StatementBinder.java)。

## 系统设计矩阵

测试以“语法位置 × 嵌套关系 × 名称作用域 × 输出期望 × 不确定性”组合设计。每份 SQL 直接从资源读取，通过真实 lexer/parser、normalizer、binder、IR verifier、血缘分析和 v1 结果投影；预期物理来源由测试独立声明。

| 测试组 | SQL 文件数 | 设计轴 | 对应集成测试 |
| --- | --- | --- | --- |
| 嵌套查询 | 28 | 三层 derived、内外同名 alias、CTE 依赖/复用/遮蔽、集合嵌套、顶层 INSERT OVERWRITE UNION ALL 的首/末/双派生分支、相关/非相关 scalar、双层 EXISTS、IN/NOT IN、ON/HAVING、CASE/BETWEEN/函数/下标、窗口外层过滤 | [NestedQueryIntegrationTest](../../../java/com/panfan/hivelineage/v2/api/NestedQueryIntegrationTest.java) |
| 关系与查询子句 | 24 | JOIN 类型与嵌套两端、集合 ALL/DISTINCT、分组、named/inline window、outer generator、查询尾部/采样、星号及 ANTI 前端拒绝边界 | [RelationalQueryIntegrationTest](../../../java/com/panfan/hivelineage/v2/api/RelationalQueryIntegrationTest.java) |
| 语句与会话 | 19 | 写入列顺序/分区/multi-insert、CTAS/临时表/视图链、变量与默认库、前序 CTE、mutation 多贡献、catalog 效果、忽略语句与外部能力开关 | [StatementLineageIntegrationTest](../../../java/com/panfan/hivelineage/v2/api/StatementLineageIntegrationTest.java) |
| 表达式与边界 | 15 | 操作符、两类 CASE、CAST/嵌套函数、复杂类型、COUNT(*)、常量、quoted/comment、opaque UDF、窗口/struct 内 scalar、歧义/递归/多列标量 | [ExpressionLineageIntegrationTest](../../../java/com/panfan/hivelineage/v2/api/ExpressionLineageIntegrationTest.java) |
| DDL 结构与审计 | 20 | primitive/复杂类型、COMMENT/分区/buckets/完整分隔符/SerDe、generic 格式、flags/LIKE、view alias/replace、database/function/macro/statistics/display 审计、自定义存储/约束/物化视图/搬运边界、malformed 非查询恢复 | [HiveDdlSyntaxIntegrationTest](../../../java/com/panfan/hivelineage/v2/dialect/hive/HiveDdlSyntaxIntegrationTest.java) |
| DDL catalog 效果 | 16 | CHANGE FIRST/AFTER/类型、ADD/REPLACE 分区角色/重复列拒绝、临时遮蔽/重命名别名、名称/身份链、view rename/drop、IF 条件、LIKE unknown schema、TRUNCATE 和对象范围未知动作、失败不提交 | [DdlCatalogEffectsIntegrationTest](../../../java/com/panfan/hivelineage/v2/session/DdlCatalogEffectsIntegrationTest.java) |
| 剩余查询关键字 | 16 | 五处子句 scalar 来源、NOT EXISTS、literal/CAST/运算符、window 继承/frame/未知循环、默认集合量词、USING/interval 边界、SET/quoted USE/resource、sampling 单位 | [HiveKeywordSyntaxIntegrationTest](../../../java/com/panfan/hivelineage/v2/dialect/hive/HiveKeywordSyntaxIntegrationTest.java) |
| 字段类型兼容输出 | 14 | DIRECT/DERIVED/CONSTANT、常量参数函数、CTE/派生表/CTAS/视图边界、MERGE 条件与未知来源 | [LegacyColumnDerivationClassificationTest](../../../java/com/panfan/hivelineage/v2/compat/LegacyColumnDerivationClassificationTest.java) |
| 原生字段类型与函数 | 15 | 原生 UNKNOWN、零参数函数、COUNT(*)、常量集合、窗口键函数、生成函数与表达式形态 | [ColumnDerivationExportTest](../../../java/com/panfan/hivelineage/v2/export/ColumnDerivationExportTest.java)、[WindowFunctionExportTest](../../../java/com/panfan/hivelineage/v2/export/WindowFunctionExportTest.java) |

目前扩展共 167 份 `v2_*.sql`，包含原有 138 份语法场景和新增 29 份字段类型与函数场景。其中表达式组包含 12 份 `v2_expression_*.sql` 和 3 份 `v2_boundary_*.sql`。文件数是资源清单数量，不是通过测试数；字段分类专项覆盖列级模式，原有语法矩阵继续覆盖三种模式。

[GeneratedSqlResourceContractTest](../../../java/com/panfan/hivelineage/v2/contracts/GeneratedSqlResourceContractTest.java) 扫描全部 `v2_*.sql`，与九个 suite 的 `scenarioFiles()` 联合注册表比较：每份文件必须恰好归属一个 suite；重复注册、遗漏、新增后未测试、注册不存在资源都应失败。[DdlSyntaxCoverageContractTest](../../../java/com/panfan/hivelineage/v2/contracts/DdlSyntaxCoverageContractTest.java) 另确保七个已建模 `DefinitionKindSyntax` family 均有真实 SQL 文件；它不证明完整 Hive option 或组合空间穷尽覆盖。原有 18 份 `core_lineage_cases.sql` / `lineage_case_*.sql` 由 [LegacyCorpusMetadataFixture.legacyCorpus()](../../../java/com/panfan/hivelineage/v2/testing/LegacyCorpusMetadataFixture.java) 独立加载，继续作为 v1/v2 比较和原始配置回归基线。扩展集不通过调整旧快照或隐藏边界结果来维持旧基线；生产 corpus loader 仍发现全部新旧 SQL。

## 文件命名与维护规则

- SQL 统一放在 `src/test/resources/hql/generated`，文件名使用小写下划线。
- `v2_nested_<结构或位置>_<组合>.sql`：突出嵌套深度、相关性或作用域，例如 `derived_three_levels`、`exists_double_correlation`。
- `v2_query_<关系类型>_<嵌套/子句>.sql`：突出 JOIN/集合/分组/窗口/generator 等关系场景。
- `v2_statement_<语句或会话>_<行为>.sql`：突出目标映射、分区、链式效果或能力边界。
- `v2_expression_<表达式组合>.sql`、`v2_boundary_<不确定或失败原因>.sql`：区分参数来源与应明确报告的未知行为。
- `v2_ddl_syntax_<结构或审计边界>.sql`、`v2_ddl_effect_<catalog行为>.sql`：分别检验 option 保存和对象状态效果，不能只以关键字出现证明支持。
- `v2_keyword_<子句/操作符/边界>.sql`：补充此前缺少代表资源的关键字位置或语法分支。
- 文件首行说明场景；多个语句放在同文件时用于检验会话、顺序或恢复行为。
- 新文件同步添加到所属 `scenarioFiles()` 和明确的来源/状态断言；修改边界时同时更新诊断、原生 facts 和本清单。

## 共享元数据与三模式断言

[GeneratedSqlSupport](../../../java/com/panfan/hivelineage/v2/testing/GeneratedSqlSupport.java) 仅提供以下表的有序列 schema，未知名称返回 `null`，不使用“任意表都存在”的兜底。CTAS、临时表和视图由脚本定义及会话效果建立。

| 表 | 有序列 |
| --- | --- |
| `ods.users`、`ods.users_archive` | `id, name, dept_id, amount, dt, tags, payload` |
| `ods.departments` | `dept_id, dept_name` |
| `ods.orders` | `user_id, order_id, amount, status, dt` |
| `ods.events` | `user_id, kind, dt` |
| `ods.complex_users` | `id, tags, attributes, profile` |
| `dwd.result`、`dwd.result_archive` | `id, name, amount, dept_name, tag, rn, dt` |

`analyzeWithComplexTypes()` 为 `ods.complex_users` 补充 typed native schema：`id: bigint`、`tags: array<string>`、`attributes: map<string,string>`、`profile: struct<account:struct<score:bigint>>`。复杂类型场景复用同一 SQL，分别核对 typed 路径解析及 v1 无类型时的显式未知行为。

基础配置为 `workflowId=wf_generated_v2`、`taskVersion=V1`、`defaultDatabase=default`、`includeIgnoredStatements=true`、`expandViewLineage=true`、`parseJoinConditions=true`、`includeTemporaryLineage=false`。SQL 中显式 USE 可改变默认库；`taskVersion=V1` 是调用方业务版本，不决定引擎版本。

每组遍历 `TABLE_ONLY`、`COLUMN_ONLY`、`TABLE_AND_COLUMN`：

| 模式 | 核对内容 |
| --- | --- |
| TABLE_ONLY | 精确输入对象与表来源；字段血缘为空；嵌套投影/谓词/窗口中的子查询仍必须被遍历。 |
| COLUMN_ONLY | 表血缘为空；精确目标字段和物理源字段集合；保留常量空来源、未知映射、字段路径。 |
| TABLE_AND_COLUMN | 同时核对表与字段来源；结合写入模式、partition scope、JOIN 条件和注册定义验证结构。 |

`assertNoFailed()` 只保证没有 `FAILED`，完整支持场景仍应断言 `SUCCESS`；忽略场景断言 `IGNORED` 和 skip reason；未知宽度/外部 schema 的绑定降级断言 `PARTIAL_SUCCESS`，UDF 与 scalar cardinality 的分析降级断言具体 diagnostic 和 native coverage，不把 partial 当完整成功。statement status 与 `LineageQuality.coverage` 属于不同层：标量 cardinality 未证明或函数不透明时，即使绑定阶段接受、status 为 `SUCCESS`，原生 facts 仍需保存未知 endpoint 和 `PARTIAL` coverage，同时保留已知来源。TABLE_ONLY 未请求字段时，字段歧义/标量宽度的降级不能机械套用字段模式期望。

mutation 和 multi-insert 直接检查原生 `BoundWrite` 数量、独立 ID、目标、模式、assignment 与 condition；catalog 测试显式加入 `CATALOG_SIMULATION`；LOAD 分别验证关闭及开启 `EXTERNAL_DATA_LINEAGE`。所有 SQL 从资源文件读取，不在测试方法内重新拼一份 SQL。

DDL / keyword 组同时使用 `syntax()` 检查真实前端的 getters，及 `analyzeWithCatalog()` 检查原生效果/下游行为。只有明确恢复资源允许 frontend PARTIAL；malformed DDL 审计、非法 USE、未知 schema、明确未支持的数据写入分别检查各自诊断和状态，不用 `assertNoFailed()` 一概替代。

## 运行方法

2026-10-14 验证：JDK 17 离线 `test-compile` 成功；四组新增集成测试共 249 项，覆盖检查 3 项；通过仓库验证脚本执行全部回归共 964 项，全部通过，失败、跳过、忽略均为 0。JDBC 集成测试使用内存连接 stub，保留真实 Provider 和原有断言；原始 18 份基线及 v1 血缘快照保持一致。

2026-10-15 追加顶层 `INSERT OVERWRITE ... SELECT ... UNION ALL SELECT ... FROM (SELECT ...)` 的首分支、末分支及双分支嵌套场景，共增加 3 份 SQL、9 项三模式测试。独立核对一个 OVERWRITE 写入、UNION ALL 两分支及字段按位置汇合；集成测试总数为 258 项，全量回归 973 项全部通过，失败、跳过、忽略均为 0。

2026-10-15 本轮在上述 973 项基线之后新增 52 份 SQL：DDL 结构/审计 20 份、DDL catalog 效果 16 份、剩余关键字 16 份。对应新增 156 项三模式集成测试、1 项 DDL family 覆盖检查和 1 项会话不确定性回归，共 158 项测试；DDL 效果组为 48 项。七组文件型集成测试累计 414 项。

DDL 扩充阶段验证：JDK 17 离线 `test-compile` 成功；仓库验证脚本的全部回归 **1131 项全部通过**，失败、assumption skip、class failure、忽略均为 0。JDBC 仍使用内存连接 stub，保留实际 Provider 和断言；这次运行未连接真实 MySQL。原始 18 份基线及 v1 血缘快照未修改。资源完整性、DDL family、Java 文件大小及 import/brace 风格检查均包含在该回归中。

字段类型与函数输出补充验证：按 [Column Lineage Expression Handling](../../../../../openspec/changes/add-hive-sql-lineage-analyzer/design.md#column-lineage-expression-handling) 统一值表达式分类，新增 29 份文件型 SQL、24 项兼容分类断言、13 项原生导出断言、4 项 JSON 输出回归、3 项标量子查询函数追溯回归和 1 项窗口键函数回归。最终重新离线编译并运行完整验证，**1176 项全部通过**，另运行原 v1 断言迁移至 v2 的兼容验证 **200 项全部通过**，失败、跳过和忽略均为 0；JDBC 继续使用内存连接 stub，未连接真实 MySQL。reference 的 82 个 SQL 示例重做 249 次分析、687 条语句处理，逐项核对类型和函数名；来源、表达式、状态与质量保持不变。

使用 JDK 17。以下命令从项目根目录运行；`JAVA_HOME` 替换为本机 JDK 17 安装路径。macOS Homebrew 的一种路径为 `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`。

依赖齐全时直接运行测试：

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn '-Dtest=*GeneratedV2IntegrationTest,GeneratedSqlResourceContractTest,DdlSyntaxCoverageContractTest' test
```

若本地 Surefire 依赖尚未缓存，可先完成编译，再用已有测试 classpath 直接调用 JUnit：

```sh
mvn -o -DskipTests test-compile
LINEAGE_TEST_CP="target/classes:target/test-classes:src/test/resources:$(cat target/v2-classpath.txt)"
java --add-opens=java.base/java.net=ALL-UNNAMED -cp "$LINEAGE_TEST_CP" \
  org.junit.runner.JUnitCore \
  com.panfan.hivelineage.v2.api.NestedQueryIntegrationTest \
  com.panfan.hivelineage.v2.api.RelationalQueryIntegrationTest \
  com.panfan.hivelineage.v2.api.StatementLineageIntegrationTest \
  com.panfan.hivelineage.v2.api.ExpressionLineageIntegrationTest \
  com.panfan.hivelineage.v2.dialect.hive.HiveDdlSyntaxIntegrationTest \
  com.panfan.hivelineage.v2.session.DdlCatalogEffectsIntegrationTest \
  com.panfan.hivelineage.v2.dialect.hive.HiveKeywordSyntaxIntegrationTest \
  com.panfan.hivelineage.v2.contracts.GeneratedSqlResourceContractTest \
  com.panfan.hivelineage.v2.contracts.DdlSyntaxCoverageContractTest
```

已有 classpath 不存在时，在依赖插件可用的环境生成它，然后重用同一运行命令：

```sh
mvn org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath \
  -DincludeScope=test -Dmdep.outputFile=target/v2-classpath.txt
```

使用已有 Maven 测试依赖 classpath 执行全部新旧回归（本轮验证入口）：

```sh
python3 scripts/run-v2-validation.py --suite all --offline --no-compile \
  --classpath-file target/v2-maven-classpath.txt
```

JDK 9+ 的 `--add-opens=java.base/java.net=ALL-UNNAMED` 用于 Hive 的反射访问；项目 Surefire profile 已配置此参数。这里不给出固定通过数，以实际运行报告和注册表检查结果为准。

## 扩展 SQL 文件清单

以下清单按文件名排序；每份文件可直接打开，与上述七个 suite 的注册表对应。

<!-- GENERATED_SQL_SCENARIO_INDEX -->

| SQL 文件 | 测试组 | 场景 |
| --- | --- | --- |
| [v2_boundary_ambiguous_column_owner.sql](v2_boundary_ambiguous_column_owner.sql) | 表达式与边界 | Unqualified amount belongs to both inputs; never invent a source owner. |
| [v2_boundary_recursive_cte.sql](v2_boundary_recursive_cte.sql) | 表达式与边界 | Recursive definition without WITH RECURSIVE is rejected by the semantic binder. |
| [v2_boundary_scalar_multiple_outputs.sql](v2_boundary_scalar_multiple_outputs.sql) | 表达式与边界 | The scalar query is syntactically valid but its width is invalid; retain diagnostic. |
| [v2_ddl_effect_change_column_first_after_comments.sql](v2_ddl_effect_change_column_first_after_comments.sql) | DDL catalog 效果 | Whole-table CHANGE preserves declared types, comments and positional order. |
| [v2_ddl_effect_change_column_invalid_keeps_schema.sql](v2_ddl_effect_change_column_invalid_keeps_schema.sql) | DDL catalog 效果 | Rejected CHANGE proposals must not mutate the committed schema. |
| [v2_ddl_effect_create_like_unknown_schema.sql](v2_ddl_effect_create_like_unknown_schema.sql) | DDL catalog 效果 | CREATE LIKE preserves unknown schema until cloning is explicitly implemented. |
| [v2_ddl_effect_if_not_exists_known_noop_and_recreate.sql](v2_ddl_effect_if_not_exists_known_noop_and_recreate.sql) | DDL catalog 效果 | Known existence suppresses CREATE; a prior DROP proves absence for recreation. |
| [v2_ddl_effect_if_not_exists_unknown_precondition.sql](v2_ddl_effect_if_not_exists_unknown_precondition.sql) | DDL catalog 效果 | A nonauthoritative missing metastore result cannot prove object absence. |
| [v2_ddl_effect_maintenance_touch_compact_stats_unknown.sql](v2_ddl_effect_maintenance_touch_compact_stats_unknown.sql) | DDL catalog 效果 | Maintenance actions cannot silently leave catalog/data certainty unchanged. |
| [v2_ddl_effect_partition_add_drop_rename_unknown.sql](v2_ddl_effect_partition_add_drop_rename_unknown.sql) | DDL catalog 效果 | Parsed partition operations retain an explicit unknown object effect. |
| [v2_ddl_effect_partition_scoped_column_changes_unknown.sql](v2_ddl_effect_partition_scoped_column_changes_unknown.sql) | DDL catalog 效果 | A partition-specific schema is not substituted for the table schema. |
| [v2_ddl_effect_rename_chain_identity_then_drop.sql](v2_ddl_effect_rename_chain_identity_then_drop.sql) | DDL catalog 效果 | Two renames, an ALTER, and DROP preserve one object's identity. |
| [v2_ddl_effect_replace_partition_name_collision.sql](v2_ddl_effect_replace_partition_name_collision.sql) | DDL catalog 效果 | REPLACE 拒绝与保留分区键同名的数据列；失败后沿用原 schema。 |
| [v2_ddl_effect_replace_preserves_partition_roles.sql](v2_ddl_effect_replace_preserves_partition_roles.sql) | DDL catalog 效果 | Partition keys remain last and keep their role across successive table schema changes. |
| [v2_ddl_effect_serde_fileformat_bucket_unknown.sql](v2_ddl_effect_serde_fileformat_bucket_unknown.sql) | DDL catalog 效果 | These catalog options are parsed but their schema/data effects are not simulated. |
| [v2_ddl_effect_temporary_rename_alter_drop_aliases.sql](v2_ddl_effect_temporary_rename_alter_drop_aliases.sql) | DDL catalog 效果 | 临时表重命名更新 lookup aliases，后续 ALTER/DROP 复用同一对象并阻止删除后回退。 |
| [v2_ddl_effect_temporary_shadow_alter_drop.sql](v2_ddl_effect_temporary_shadow_alter_drop.sql) | DDL catalog 效果 | ALTER/DROP choose the temporary lookup before a permanent object with the same name. |
| [v2_ddl_effect_truncate_partition_unknown.sql](v2_ddl_effect_truncate_partition_unknown.sql) | DDL catalog 效果 | Partition truncation invalidates data certainty of this object, without claiming a data write. |
| [v2_ddl_effect_view_rename_drop_if_exists.sql](v2_ddl_effect_view_rename_drop_if_exists.sql) | DDL catalog 效果 | Renaming a view retains its definition, then DROP removes it from lookup. |
| [v2_ddl_syntax_bucket_without_sorted_columns.sql](v2_ddl_syntax_bucket_without_sorted_columns.sql) | DDL 结构与审计 | 不带 SORTED BY 的 bucket AST 只有列名与桶数，不能误读成排序列表。 |
| [v2_ddl_syntax_comments_partition_buckets_delimiters.sql](v2_ddl_syntax_comments_partition_buckets_delimiters.sql) | DDL 结构与审计 | Hive CREATE AST 的 bucket token 与 SerDeProps wrapper；保存全部已建模选项。 |
| [v2_ddl_syntax_custom_storage_audit_boundary.sql](v2_ddl_syntax_custom_storage_audit_boundary.sql) | DDL 结构与审计 | 自定义 input/output format 与 storage handler 仅 parser 可达，目前没有结构化存储模型。 |
| [v2_ddl_syntax_database_schema_lifecycle_audit.sql](v2_ddl_syntax_database_schema_lifecycle_audit.sql) | DDL 结构与审计 | DATABASE/SCHEMA 同义语法及选项只审计，不模拟数据库目录和权限。 |
| [v2_ddl_syntax_generic_storage_formats.sql](v2_ddl_syntax_generic_storage_formats.sql) | DDL 结构与审计 | 存储格式保留为名称；不检查存储引擎执行能力，也不产生数据来源。 |
| [v2_ddl_syntax_if_exists_like_options.sql](v2_ddl_syntax_if_exists_like_options.sql) | DDL 结构与审计 | IF 标记与 LIKE 源名保存；LIKE 当前不复制来源 schema。 |
| [v2_ddl_syntax_import_export_unsupported_boundary.sql](v2_ddl_syntax_import_export_unsupported_boundary.sql) | DDL 结构与审计 | IMPORT/EXPORT 涉及未支持的数据搬运；语法可达但引擎明确失败，不影响后续普通 INSERT。 |
| [v2_ddl_syntax_macros_reload_functions_audit.sql](v2_ddl_syntax_macros_reload_functions_audit.sql) | DDL 结构与审计 | MACRO 与 RELOAD FUNCTIONS 是审计边界，不执行注册或函数重载。 |
| [v2_ddl_syntax_malformed_nonquery_recovery_then_insert.sql](v2_ddl_syntax_malformed_nonquery_recovery_then_insert.sql) | DDL 结构与审计 | 非查询 DDL 缺失字段类型，仅保留 INVALID_DDL 审计，前端 PARTIAL；后续 INSERT 继续。 |
| [v2_ddl_syntax_materialized_view_lifecycle_audit.sql](v2_ddl_syntax_materialized_view_lifecycle_audit.sql) | DDL 结构与审计 | Materialized view 的 CREATE/REBUILD 涉及未支持的数据写入，明确失败；DROP 仅审计。 |
| [v2_ddl_syntax_nested_complex_types.sql](v2_ddl_syntax_nested_complex_types.sql) | DDL 结构与审计 | ARRAY/MAP/STRUCT/UNIONTYPE 递归声明；类型与字段名称不应丢失或变成输入表。 |
| [v2_ddl_syntax_permanent_function_resources.sql](v2_ddl_syntax_permanent_function_resources.sql) | DDL 结构与审计 | 永久函数名及 JAR/FILE/ARCHIVE 资源审计；不会加载 class 或资源。 |
| [v2_ddl_syntax_primitive_parameterized_types.sql](v2_ddl_syntax_primitive_parameterized_types.sql) | DDL 结构与审计 | DDL 类型声明保留每个 primitive 名称，以及 DECIMAL/CHAR/VARCHAR 的参数。 |
| [v2_ddl_syntax_serde_class_properties.sql](v2_ddl_syntax_serde_class_properties.sql) | DDL 结构与审计 | TOK_TABLESERIALIZER 下 TOK_SERDENAME 同时包裹 class 与属性。 |
| [v2_ddl_syntax_show_describe_explain_audit.sql](v2_ddl_syntax_show_describe_explain_audit.sql) | DDL 结构与审计 | 元数据展示与计划展示的查询内容不会被误报成正式写入血缘。 |
| [v2_ddl_syntax_skew_constraints_audit_boundary.sql](v2_ddl_syntax_skew_constraints_audit_boundary.sql) | DDL 结构与审计 | SKEWED 与约束目前仅 parser 可达；约束 AST 不得被误当成列 comment。 |
| [v2_ddl_syntax_statistics_msck_audit.sql](v2_ddl_syntax_statistics_msck_audit.sql) | DDL 结构与审计 | 统计与分区修复只做审计，不访问数据或 metastore 写接口。 |
| [v2_ddl_syntax_temporary_external_flags.sql](v2_ddl_syntax_temporary_external_flags.sql) | DDL 结构与审计 | 无查询声明式 TEMPORARY/EXTERNAL 选项与 location；两者不产生写入血缘。 |
| [v2_ddl_syntax_view_explicit_columns_comments.sql](v2_ddl_syntax_view_explicit_columns_comments.sql) | DDL 结构与审计 | 显式 view 列名/COMMENT 覆盖内部别名；括号查询中含嵌套派生表。 |
| [v2_ddl_syntax_view_or_replace_definition.sql](v2_ddl_syntax_view_or_replace_definition.sql) | DDL 结构与审计 | OR REPLACE 保留语法选项；后续定义应切换到 archive 的来源。 |
| [v2_expression_arithmetic_bitwise.sql](v2_expression_arithmetic_bitwise.sql) | 表达式与边界 | Arithmetic, unary operators and bitwise operators through a derived query. |
| [v2_expression_array_map_struct_access.sql](v2_expression_array_map_struct_access.sql) | 表达式与边界 | Array/map subscript and nested struct field access preserve distinct field paths. |
| [v2_expression_cast_nested_functions.sql](v2_expression_cast_nested_functions.sql) | 表达式与边界 | CAST, nested builtin calls and null fallback keep known argument sources. |
| [v2_expression_constant_assignments.sql](v2_expression_constant_assignments.sql) | 表达式与边界 | Literal outputs retain assignments without creating source column edges. |
| [v2_expression_distinct_aggregate_count_star.sql](v2_expression_distinct_aggregate_count_star.sql) | 表达式与边界 | DISTINCT aggregate arguments and COUNT(*) have different field provenance. |
| [v2_expression_field_access_scalar_struct.sql](v2_expression_field_access_scalar_struct.sql) | 表达式与边界 | A field accessor wrapping a scalar subquery must not hide its physical table. |
| [v2_expression_named_window_scalar_order.sql](v2_expression_named_window_scalar_order.sql) | 表达式与边界 | Named window specifications can also contain scalar subquery dependencies. |
| [v2_expression_opaque_udf_arguments.sql](v2_expression_opaque_udf_arguments.sql) | 表达式与边界 | Opaque UDF retains proven arguments and explicitly reports unknown behavior. |
| [v2_expression_predicate_operators.sql](v2_expression_predicate_operators.sql) | 表达式与边界 | Projection predicates exercise BETWEEN, IN-list, null-safe comparison, LIKE and regexp. |
| [v2_expression_quoted_identifiers_comments.sql](v2_expression_quoted_identifiers_comments.sql) | 表达式与边界 | Semicolons in comments and strings must not split this statement; SELECT 'ignore;me'. |
| [v2_expression_simple_searched_case.sql](v2_expression_simple_searched_case.sql) | 表达式与边界 | Simple CASE and searched CASE retain condition and value column dependencies. |
| [v2_expression_window_scalar_order.sql](v2_expression_window_scalar_order.sql) | 表达式与边界 | A scalar subquery in window ordering must remain an input even in TABLE_ONLY. |
| [v2_keyword_cast_primitive_type_parameters.sql](v2_keyword_cast_primitive_type_parameters.sql) | 剩余查询关键字 | Primitive CAST grammar preserves declared type names, precision/scale and string lengths. |
| [v2_keyword_interval_internal_function_boundary.sql](v2_keyword_interval_internal_function_boundary.sql) | 剩余查询关键字 | Hive represents INTERVAL using an internal function; its opaque semantics stay explicit. |
| [v2_keyword_join_using_partial_boundary.sql](v2_keyword_join_using_partial_boundary.sql) | 剩余查询关键字 | USING is parsed but its native column-list condition has no declared binding rule. |
| [v2_keyword_literal_boolean_datetime_adjacent_strings.sql](v2_keyword_literal_boolean_datetime_adjacent_strings.sql) | 剩余查询关键字 | Typed literals and adjacent string tokens retain their kinds and constant assignments. |
| [v2_keyword_nested_cluster_by_scalar_subquery.sql](v2_keyword_nested_cluster_by_scalar_subquery.sql) | 剩余查询关键字 | Scalar input in CLUSTER BY must remain an object dependency in every parse mode. |
| [v2_keyword_nested_distribute_by_scalar_subquery.sql](v2_keyword_nested_distribute_by_scalar_subquery.sql) | 剩余查询关键字 | Scalar input in DISTRIBUTE BY must remain an object dependency in every parse mode. |
| [v2_keyword_nested_group_by_scalar_subquery.sql](v2_keyword_nested_group_by_scalar_subquery.sql) | 剩余查询关键字 | Scalar input in GROUP BY must remain an object dependency in every parse mode. |
| [v2_keyword_nested_order_by_scalar_subquery.sql](v2_keyword_nested_order_by_scalar_subquery.sql) | 剩余查询关键字 | Scalar input in ORDER BY must remain an object dependency in every parse mode. |
| [v2_keyword_nested_sort_by_scalar_subquery.sql](v2_keyword_nested_sort_by_scalar_subquery.sql) | 剩余查询关键字 | Scalar input in SORT BY must remain an object dependency in every parse mode. |
| [v2_keyword_not_exists_correlated.sql](v2_keyword_not_exists_correlated.sql) | 剩余查询关键字 | NOT EXISTS preserves filtering input while the inserted value comes only from users. |
| [v2_keyword_remaining_unary_comparison_operators.sql](v2_keyword_remaining_unary_comparison_operators.sql) | 剩余查询关键字 | Distinct native operators cannot hide behind the same id source column; CASE omits ELSE. |
| [v2_keyword_sample_bucket_percent_rows_byte_length.sql](v2_keyword_sample_bucket_percent_rows_byte_length.sql) | 剩余查询关键字 | Sampling unit markers are structural metadata, not numeric sample arguments. |
| [v2_keyword_select_all_default_set_quantifiers.sql](v2_keyword_select_all_default_set_quantifiers.sql) | 剩余查询关键字 | Explicit SELECT ALL is semantically the default; omitted set quantifiers mean DISTINCT. |
| [v2_keyword_session_queries_quoted_use_resource_kinds.sql](v2_keyword_session_queries_quoted_use_resource_kinds.sql) | 剩余查询关键字 | SET query forms, quoted USE and every resource command kind are audited without execution. |
| [v2_keyword_window_named_inheritance_following_frames.sql](v2_keyword_window_named_inheritance_following_frames.sql) | 剩余查询关键字 | Recursive window inheritance must merge partitions, ordering and complete frame bounds. |
| [v2_keyword_window_unknown_and_recursive_boundaries.sql](v2_keyword_window_unknown_and_recursive_boundaries.sql) | 剩余查询关键字 | Unknown base windows are accepted syntactically and explicitly partial during binding. |
| [v2_nested_cte_column_aliases.sql](v2_nested_cte_column_aliases.sql) | 嵌套查询 | CTE 显式列别名覆盖 SELECT 别名，依赖链再次更名。 |
| [v2_nested_cte_dependency_chain.sql](v2_nested_cte_dependency_chain.sql) | 嵌套查询 | 三层 CTE 依赖链与派生表交错；加工字段不丢失物理来源。 |
| [v2_nested_cte_local_shadowing.sql](v2_nested_cte_local_shadowing.sql) | 嵌套查询 | 派生表内 WITH 与外部同名 CTE；局部 users_archive 不覆盖外部 orders。 |
| [v2_nested_cte_self_join.sql](v2_nested_cte_self_join.sql) | 嵌套查询 | 一个 CTE 两次引用的自连接，关系实例与输出槽必须独立。 |
| [v2_nested_derived_alias_shadowing.sql](v2_nested_derived_alias_shadowing.sql) | 嵌套查询 | 内外查询都使用别名 x，内层归属 archive，外层归属 users。 |
| [v2_nested_derived_join_aggregate.sql](v2_nested_derived_join_aggregate.sql) | 嵌套查询 | JOIN 两侧都是派生表，右侧聚合，外层过滤聚合别名。 |
| [v2_nested_derived_three_levels.sql](v2_nested_derived_three_levels.sql) | 嵌套查询 | 三层 FROM 派生表连续重命名与算术加工；来源始终回溯 ods.users。 |
| [v2_nested_except_inside_cte.sql](v2_nested_except_inside_cte.sql) | 嵌套查询 | CTE 内 EXCEPT，再由外层派生表投影。 |
| [v2_nested_exists_double_correlation.sql](v2_nested_exists_double_correlation.sql) | 嵌套查询 | 两层 EXISTS，最内层同时关联直接父层 orders 和祖先 users。 |
| [v2_nested_having_scalar_subquery.sql](v2_nested_having_scalar_subquery.sql) | 嵌套查询 | GROUP BY/HAVING 中嵌非关联 scalar，目标聚合值来自外层 users.amount。 |
| [v2_nested_in_and_not_in.sql](v2_nested_in_and_not_in.sql) | 嵌套查询 | WHERE IN 子查询中再嵌 NOT IN 子查询；筛选来源不冒充投影字段来源。 |
| [v2_nested_insert_overwrite_union_all_both_branches_derived.sql](v2_nested_insert_overwrite_union_all_both_branches_derived.sql) | 嵌套查询 | 直接 INSERT OVERWRITE 的 UNION ALL 两分支均嵌套，分别包含函数和聚合。 |
| [v2_nested_insert_overwrite_union_all_first_branch_derived.sql](v2_nested_insert_overwrite_union_all_first_branch_derived.sql) | 嵌套查询 | 直接 INSERT OVERWRITE 接 UNION ALL，首分支 FROM 两层派生表，末分支直接查表。 |
| [v2_nested_insert_overwrite_union_all_last_branch_derived.sql](v2_nested_insert_overwrite_union_all_last_branch_derived.sql) | 嵌套查询 | 直接 INSERT OVERWRITE 接 UNION ALL，末分支 FROM 派生表，别名按位置对齐。 |
| [v2_nested_intersect_inside_derived.sql](v2_nested_intersect_inside_derived.sql) | 嵌套查询 | 派生表内 INTERSECT，两分支同位置字段均保留血缘。 |
| [v2_nested_join_on_exists.sql](v2_nested_join_on_exists.sql) | 嵌套查询 | JOIN ON 中 EXISTS 再包派生表，并关联外层 users；筛选来源进入表血缘。 |
| [v2_nested_lateral_view_outer_chain.sql](v2_nested_lateral_view_outer_chain.sql) | 嵌套查询 | 派生表中的 LATERAL VIEW OUTER 再包一层投影，生成字段回溯 tags 参数。 |
| [v2_nested_mixed_cte_join_union_exists.sql](v2_nested_mixed_cte_join_union_exists.sql) | 嵌套查询 | CTE 集合查询、聚合 CTE、两个 JOIN、EXISTS 混合；物理来源五张表。 |
| [v2_nested_scalar_between_bounds.sql](v2_nested_scalar_between_bounds.sql) | 嵌套查询 | CASE 条件 BETWEEN 的上下界均是 scalar；TABLE_ONLY 也必须遍历这些子查询。 |
| [v2_nested_scalar_case_cast_function.sql](v2_nested_scalar_case_cast_function.sql) | 嵌套查询 | scalar 分别嵌入 CASE 条件、CAST、COALESCE，保留条件与值来源。 |
| [v2_nested_scalar_derived_aggregate.sql](v2_nested_scalar_derived_aggregate.sql) | 嵌套查询 | SELECT scalar 内再嵌两层派生表与聚合，只有 orders.amount 作为金额值源。 |
| [v2_nested_scalar_in_list.sql](v2_nested_scalar_in_list.sql) | 嵌套查询 | IN 常量列表含 scalar 子查询，外层 CASE 的条件来源也属于字段血缘。 |
| [v2_nested_scalar_select_correlated.sql](v2_nested_scalar_select_correlated.sql) | 嵌套查询 | SELECT scalar 子查询按外层用户关联；内层筛选影响行集，值来自 orders.amount。 |
| [v2_nested_scalar_select_uncorrelated.sql](v2_nested_scalar_select_uncorrelated.sql) | 嵌套查询 | SELECT 非关联 scalar 聚合子查询；金额来源 orders，不串到外层 users。 |
| [v2_nested_scalar_subscript.sql](v2_nested_scalar_subscript.sql) | 嵌套查询 | scalar 返回数组后下标取值，events.kind 来源不能丢失。 |
| [v2_nested_union_inside_cte.sql](v2_nested_union_inside_cte.sql) | 嵌套查询 | CTE 内 UNION DISTINCT，派生表再连接部门表。 |
| [v2_nested_union_inside_derived.sql](v2_nested_union_inside_derived.sql) | 嵌套查询 | 派生表内 UNION ALL，外层按位置接收两分支字段后过滤。 |
| [v2_nested_window_derived_filter.sql](v2_nested_window_derived_filter.sql) | 嵌套查询 | 内层窗口函数，外层以窗口别名过滤；rn 依赖 partition/order 字段。 |
| [v2_query_distinct_star_mixed_constants.sql](v2_query_distinct_star_mixed_constants.sql) | 关系与查询子句 | Qualified star expands a derived output schema between DISTINCT and constant outputs. |
| [v2_query_group_cube_derived.sql](v2_query_group_cube_derived.sql) | 关系与查询子句 | CUBE over a derived query. |
| [v2_query_group_grouping_sets_cte.sql](v2_query_group_grouping_sets_cte.sql) | 关系与查询子句 | GROUPING SETS includes multiple key combinations and the empty grouping set. |
| [v2_query_group_having_nested.sql](v2_query_group_having_nested.sql) | 关系与查询子句 | Aggregation over a derived query, DISTINCT aggregate argument, and HAVING. |
| [v2_query_group_rollup_cte.sql](v2_query_group_rollup_cte.sql) | 关系与查询子句 | ROLLUP over a CTE; grouping keys and aggregate inputs map independently. |
| [v2_query_join_comma_derived_where.sql](v2_query_join_comma_derived_where.sql) | 关系与查询子句 | Comma join with a WHERE join predicate and independent derived-query scopes. |
| [v2_query_join_cross_derived.sql](v2_query_join_cross_derived.sql) | 关系与查询子句 | CROSS JOIN still keeps each projected value bound to its own source. |
| [v2_query_join_full_cte.sql](v2_query_join_full_cte.sql) | 关系与查询子句 | FULL OUTER JOIN with two coalesced output columns across a CTE boundary. |
| [v2_query_join_inner_derived.sql](v2_query_join_inner_derived.sql) | 关系与查询子句 | INNER JOIN with an independently filtered derived relation. |
| [v2_query_join_left_anti_rejected_by_frontend.sql](v2_query_join_left_anti_rejected_by_frontend.sql) | 关系与查询子句 | Boundary: Hive 4.0.1 lexer rejects LEFT ANTI JOIN although native IR models it. |
| [v2_query_join_left_cte_aggregate.sql](v2_query_join_left_cte_aggregate.sql) | 关系与查询子句 | LEFT OUTER JOIN against a grouped CTE: unrelated join keys must not enter output values. |
| [v2_query_join_left_semi_derived.sql](v2_query_join_left_semi_derived.sql) | 关系与查询子句 | LEFT SEMI JOIN: the right relation affects membership, while output values stay on the left. |
| [v2_query_join_right_derived.sql](v2_query_join_right_derived.sql) | 关系与查询子句 | RIGHT OUTER JOIN and a value expression using both sides. |
| [v2_query_lateral_nested_generators_cte.sql](v2_query_lateral_nested_generators_cte.sql) | 关系与查询子句 | A second generator consumes the first generator output through a CTE query. |
| [v2_query_lateral_outer_posexplode_derived.sql](v2_query_lateral_outer_posexplode_derived.sql) | 关系与查询子句 | OUTER posexplode returns multiple generated columns with the same physical input. |
| [v2_query_set_except_all_derived.sql](v2_query_set_except_all_derived.sql) | 关系与查询子句 | EXCEPT ALL binds output positions across two derived-query branches. |
| [v2_query_set_except_distinct_derived.sql](v2_query_set_except_distinct_derived.sql) | 关系与查询子句 | EXCEPT DISTINCT binds output positions across two derived-query branches. |
| [v2_query_set_intersect_all_derived.sql](v2_query_set_intersect_all_derived.sql) | 关系与查询子句 | INTERSECT ALL binds output positions across two derived-query branches. |
| [v2_query_set_intersect_distinct_derived.sql](v2_query_set_intersect_distinct_derived.sql) | 关系与查询子句 | INTERSECT DISTINCT binds output positions across two derived-query branches. |
| [v2_query_set_union_all_derived.sql](v2_query_set_union_all_derived.sql) | 关系与查询子句 | UNION ALL binds output positions across two derived-query branches. |
| [v2_query_set_union_distinct_derived.sql](v2_query_set_union_distinct_derived.sql) | 关系与查询子句 | UNION DISTINCT binds output positions across two derived-query branches. |
| [v2_query_tails_samples_sort_limit.sql](v2_query_tails_samples_sort_limit.sql) | 关系与查询子句 | Query tails and sample forms preserve output values; these fixtures do not execute SQL. |
| [v2_query_window_inline_rows_nested.sql](v2_query_window_inline_rows_nested.sql) | 关系与查询子句 | Inline windows retain partition and ordering dependencies across a derived query. |
| [v2_query_window_named_range_cte.sql](v2_query_window_named_range_cte.sql) | 关系与查询子句 | Named RANGE window reused by both aggregate and ranking outputs. |
| [v2_statement_catalog_add_replace_rename_drop.sql](v2_statement_catalog_add_replace_rename_drop.sql) | 语句与会话 | 开启 CATALOG_SIMULATION 后，建表/ADD/REPLACE/RENAME 的 schema 和名称依次生效，DROP 产生删除效果。 |
| [v2_statement_catalog_properties_location_truncate_boundary.sql](v2_statement_catalog_properties_location_truncate_boundary.sql) | 语句与会话 | ALTER properties/location 和 TRUNCATE 只保存不确定 catalog 效果，不宣称有数据字段血缘。 |
| [v2_statement_create_alter_view_nested_chain.sql](v2_statement_create_alter_view_nested_chain.sql) | 语句与会话 | CREATE VIEW 后 ALTER VIEW 替换定义，再建第二层视图；下游必须使用新定义。 |
| [v2_statement_ctas_nested_aggregate_chain.sql](v2_statement_ctas_nested_aggregate_chain.sql) | 语句与会话 | CTAS 从嵌套聚合建表，随后读取已注册定义；不依赖 metastore 预置新表。 |
| [v2_statement_delete_predicate_without_column_assignments.sql](v2_statement_delete_predicate_without_column_assignments.sql) | 语句与会话 | DELETE 保留目标行集和 WHERE 条件，不伪造被删除字段的赋值映射。 |
| [v2_statement_external_table_storage_and_like_ddl.sql](v2_statement_external_table_storage_and_like_ddl.sql) | 语句与会话 | 外部表 DDL 保留复杂类型/分区/存储选项；CREATE LIKE 只保留语法，未推导来源 schema。 |
| [v2_statement_insert_explicit_column_order.sql](v2_statement_insert_explicit_column_order.sql) | 语句与会话 | INSERT INTO 和 OVERWRITE 的显式目标列按声明顺序映射，而不是目标元数据顺序。 |
| [v2_statement_load_data_local_and_distributed.sql](v2_statement_load_data_local_and_distributed.sql) | 语句与会话 | LOAD DATA 需显式 EXTERNAL_DATA_LINEAGE；本地/分布式路径与 overwrite/append、静态分区分别保存。 |
| [v2_statement_merge_derived_source_three_actions.sql](v2_statement_merge_derived_source_three_actions.sql) | 语句与会话 | MERGE 来源是嵌套查询；matched UPDATE/DELETE 与 not matched INSERT 保留三个贡献和条件。 |
| [v2_statement_multi_insert_shared_nested_union.sql](v2_statement_multi_insert_shared_nested_union.sql) | 语句与会话 | 共享两层嵌套 UNION FROM 的两个 INSERT 分支，分别保存目标、写入模式和字段映射。 |
| [v2_statement_partition_dynamic_nested_source.sql](v2_statement_partition_dynamic_nested_source.sql) | 语句与会话 | 动态分区取嵌套查询最后一个输出，业务字段别名和分区字段分别回溯。 |
| [v2_statement_partition_mixed_static_dynamic.sql](v2_statement_partition_mixed_static_dynamic.sql) | 语句与会话 | 混合分区；静态 dt 是常量，动态 tag 取最后一个输出并回溯数组下标表达式。 |
| [v2_statement_partition_static_explicit_columns.sql](v2_statement_partition_static_explicit_columns.sql) | 语句与会话 | 静态分区由常量赋值，显式业务字段映射只消耗 SELECT 的业务输出。 |
| [v2_statement_pure_select_cte_session_reuse.sql](v2_statement_pure_select_cte_session_reuse.sql) | 语句与会话 | 兼容口径下前序纯 SELECT 忽略正式输出，但 CTE 定义可供后续语句复用。 |
| [v2_statement_resource_function_source_commands.sql](v2_statement_resource_function_source_commands.sql) | 语句与会话 | 资源、SOURCE、函数命令保存审计类型但不加载/执行外部资源，末尾普通 INSERT 继续解析。 |
| [v2_statement_session_set_use_variable_substitution.sql](v2_statement_session_set_use_variable_substitution.sql) | 语句与会话 | SET 的 hivevar/hiveconf 会话变量、USE 默认库以及变量值在后续 SELECT 中生效。 |
| [v2_statement_temporary_ctas_two_stage_chain.sql](v2_statement_temporary_ctas_two_stage_chain.sql) | 语句与会话 | 两个 temporary CTAS 串联，默认不发布临时血缘，最终字段仍展开到原表。 |
| [v2_statement_update_multiple_assignments.sql](v2_statement_update_multiple_assignments.sql) | 语句与会话 | UPDATE 的每个 SET 产生独立字段赋值，保留更新前自身字段依赖和 WHERE 条件。 |
| [v2_statement_values_directory_ignored_then_insert.sql](v2_statement_values_directory_ignored_then_insert.sql) | 语句与会话 | VALUES 与 DIRECTORY/LOCAL DIRECTORY 只做语法审计，均不产生正式表/列血缘；后续 INSERT 正常执行。 |
| [v2_lineage_compat_alias_literal_static_partition.sql](v2_lineage_compat_alias_literal_static_partition.sql) | 字段类型兼容输出 | alias literal static partition |
| [v2_lineage_compat_computed_union_branch.sql](v2_lineage_compat_computed_union_branch.sql) | 字段类型兼容输出 | computed union branch |
| [v2_lineage_compat_constant_argument_computations.sql](v2_lineage_compat_constant_argument_computations.sql) | 字段类型兼容输出 | constant argument computations |
| [v2_lineage_compat_ctas_direct_computed_expansion.sql](v2_lineage_compat_ctas_direct_computed_expansion.sql) | 字段类型兼容输出 | ctas direct computed expansion |
| [v2_lineage_compat_derived_cte_expression_shape.sql](v2_lineage_compat_derived_cte_expression_shape.sql) | 字段类型兼容输出 | derived cte expression shape |
| [v2_lineage_compat_literal_union_branches.sql](v2_lineage_compat_literal_union_branches.sql) | 字段类型兼容输出 | literal union branches |
| [v2_lineage_compat_merge_literal_condition.sql](v2_lineage_compat_merge_literal_condition.sql) | 字段类型兼容输出 | merge literal condition |
| [v2_lineage_compat_nested_functions_opaque_udf.sql](v2_lineage_compat_nested_functions_opaque_udf.sql) | 字段类型兼容输出 | nested functions opaque udf |
| [v2_lineage_compat_same_source_field_union.sql](v2_lineage_compat_same_source_field_union.sql) | 字段类型兼容输出 | same source field union |
| [v2_lineage_compat_unknown_column_source.sql](v2_lineage_compat_unknown_column_source.sql) | 字段类型兼容输出 | unknown column source |
| [v2_lineage_compat_view_count_star_constant_aggregate.sql](v2_lineage_compat_view_count_star_constant_aggregate.sql) | 字段类型兼容输出 | view count star constant aggregate |
| [v2_lineage_compat_view_literal_boundary.sql](v2_lineage_compat_view_literal_boundary.sql) | 字段类型兼容输出 | view literal boundary |
| [v2_lineage_compat_view_literal_union_boundary.sql](v2_lineage_compat_view_literal_union_boundary.sql) | 字段类型兼容输出 | view literal union boundary |
| [v2_lineage_compat_view_nested_function_boundary.sql](v2_lineage_compat_view_nested_function_boundary.sql) | 字段类型兼容输出 | view nested function boundary |
| [v2_lineage_export_ambiguous_unqualified_column.sql](v2_lineage_export_ambiguous_unqualified_column.sql) | 原生字段类型与函数 | ambiguous unqualified column |
| [v2_lineage_export_constant_argument_functions_arithmetic_cast_case.sql](v2_lineage_export_constant_argument_functions_arithmetic_cast_case.sql) | 原生字段类型与函数 | constant argument functions arithmetic cast case |
| [v2_lineage_export_ctas_field_copy_proof_boundaries.sql](v2_lineage_export_ctas_field_copy_proof_boundaries.sql) | 原生字段类型与函数 | ctas field copy proof boundaries |
| [v2_lineage_export_cte_derived_alias_origins.sql](v2_lineage_export_cte_derived_alias_origins.sql) | 原生字段类型与函数 | cte derived alias origins |
| [v2_lineage_export_expanded_view_shape_classification.sql](v2_lineage_export_expanded_view_shape_classification.sql) | 原生字段类型与函数 | expanded view shape classification |
| [v2_lineage_export_lateral_view_literal_collection.sql](v2_lineage_export_lateral_view_literal_collection.sql) | 原生字段类型与函数 | lateral view literal collection |
| [v2_lineage_export_literal_static_partition_constants.sql](v2_lineage_export_literal_static_partition_constants.sql) | 原生字段类型与函数 | literal static partition constants |
| [v2_lineage_export_merge_literal_direct_with_condition.sql](v2_lineage_export_merge_literal_direct_with_condition.sql) | 原生字段类型与函数 | merge literal direct with condition |
| [v2_lineage_export_opaque_functions_literal_zero_and_field_arguments.sql](v2_lineage_export_opaque_functions_literal_zero_and_field_arguments.sql) | 原生字段类型与函数 | opaque functions literal zero and field arguments |
| [v2_lineage_export_union_all_literal_and_function_branches.sql](v2_lineage_export_union_all_literal_and_function_branches.sql) | 原生字段类型与函数 | union all literal and function branches |
| [v2_lineage_export_union_all_unknown_literal_function_branch.sql](v2_lineage_export_union_all_unknown_literal_function_branch.sql) | 原生字段类型与函数 | union all unknown literal function branch |
| [v2_lineage_export_update_literal_direct_with_condition.sql](v2_lineage_export_update_literal_direct_with_condition.sql) | 原生字段类型与函数 | update literal direct with condition |
| [v2_lineage_export_window_functions_shape_and_names.sql](v2_lineage_export_window_functions_shape_and_names.sql) | 原生字段类型与函数 | window functions shape and names |
| [v2_lineage_export_zero_argument_functions_count_star.sql](v2_lineage_export_zero_argument_functions_count_star.sql) | 原生字段类型与函数 | zero argument functions count star |
| [v2_lineage_export_window_nested_key_functions.sql](v2_lineage_export_window_nested_key_functions.sql) | 原生字段类型与函数 | inline / derived / named windows retain LOWER and ABS from their own partition and order expressions |
