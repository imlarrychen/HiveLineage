# v2 关键字与 DDL 覆盖审计

查询、表达式、写入、DDL、命令和脚本输入的用户说明见 [Hive SQL Lineage Reference](../../../../../docs/hive-sql-lineage-reference.md)；逐项“已覆盖 / 未覆盖”总清单保留在 [开发验证矩阵附录](../../../../../docs/v2-sql-syntax-coverage-matrix.md)。本文保留 DDL 结构、catalog 效果和边界的详细审计证据。

本轮以仓库使用的 Hive 4.0.1 lexer/parser、实际 SQL 资源、归一化结构、catalog 效果和下游血缘分别核对。结论是：本项目已建模的七种 DDL family 均有文件型集成场景；仍有只保留语法、效果未知、仅审计和明确不支持的语句。不能把“出现了关键字”“parser 接受”或“测试通过”解释成全部 Hive 词库及所有语法组合 100% 支持。

本清单区分四层证据：SQL 是否实际被前端接受；选项是否在 `Syntax*` getters 中保留；绑定是否产生确定/条件/未知效果；最终是否产生正式数据写入血缘。普通声明式 DDL 为 `IGNORED` 可以符合设计，但不代表已完整模拟 Hive DDL 执行。

## 验证入口与完整性范围

- **S**：[HiveDdlSyntaxIntegrationTest](../../../java/com/panfan/hivelineage/v2/dialect/hive/HiveDdlSyntaxIntegrationTest.java)，20 份资源，每个测试遍历三种解析模式，检查具体结构与状态。
- **E**：[DdlCatalogEffectsIntegrationTest](../../../java/com/panfan/hivelineage/v2/session/DdlCatalogEffectsIntegrationTest.java)，16 份资源、48 项三模式测试，检查对象身份、schema、分区角色、临时重命名别名、分区重名拒绝、条件/未知效果和后续语句。
- **K**：[HiveKeywordSyntaxIntegrationTest](../../../java/com/panfan/hivelineage/v2/dialect/hive/HiveKeywordSyntaxIntegrationTest.java)，其 `scenarioFiles()` 声明实际关键字资源，检查真实前端、IR、来源和边界。
- **原语句组**：[StatementLineageIntegrationTest](../../../java/com/panfan/hivelineage/v2/api/StatementLineageIntegrationTest.java)，保留 CTAS、临时 CTAS、视图定义链及原 catalog 场景。

[DdlSyntaxCoverageContractTest](../../../java/com/panfan/hivelineage/v2/contracts/DdlSyntaxCoverageContractTest.java) 从已注册 SQL 的真实前端结果收集 `DefinitionKindSyntax`，与全部 enum 值比较。目前七种 family 为 `CREATE_TABLE`、`CREATE_VIEW`、`ALTER_VIEW`、`DROP_TABLE`、`DROP_VIEW`、`ALTER_TABLE`、`TRUNCATE_TABLE`。新增已建模 family 而未增加资源会失败；这个检查不保证每种 family 的所有 option 或组合均有语义实现。

[GeneratedSqlResourceContractTest](../../../java/com/panfan/hivelineage/v2/contracts/GeneratedSqlResourceContractTest.java) 检查七个 suite 的联合注册表与全部 `v2_*.sql`：每份 SQL 恰好归属一个 suite，缺文件、漏注册、重复注册都失败。资源数量与 JUnit 数量分开记录。完整执行结果以本轮验证报告为准，历史运行记录见 [v2_syntax_coverage.md](v2_syntax_coverage.md)。

[GeneratedSqlSupport.syntax()](../../../java/com/panfan/hivelineage/v2/testing/GeneratedSqlSupport.java) 使用真实脚本拆分、变量展开与前端；`analyzeWithCatalog()` 显式开启 `CATALOG_SIMULATION`。SQL 不在测试方法中另写一份。原生结构断言用于发现“血缘看起来相同，但 DDL 选项已经丢失”的错误。

2026-10-15 DDL 扩充阶段离线编译及全量验证通过：1131 项测试，失败、跳过、忽略均为 0。该阶段新增 52 份 SQL 和 158 项测试；字段类型与函数输出补充后，最新全量为 1176 项通过，具体记录及 JDBC stub 范围见 [验证记录](v2_syntax_coverage.md#运行方法)。

## 已建模 DDL family

| Family / 关键字 | SQL 接受与结构保留 | 语义效果 / 正式血缘 | 资源与具体断言 |
| --- | --- | --- | --- |
| `CREATE TABLE (columns)` | 接受；名称、列序、类型和已建模 option 保存 | catalog 能力开启时 `CreateObject`；声明本身无 `BoundWrite` / 正式血缘 | [primitive_parameterized_types](v2_ddl_syntax_primitive_parameterized_types.sql)；S.`primitiveTypesAndNumericParametersRemainStructural()` |
| `CREATE TEMPORARY TABLE (columns)` | 接受；temporary 标记与声明 schema 保存 | 临时对象进入当前脚本目录，遮蔽同名永久对象；普通声明无正式血缘 | [temporary_external_flags](v2_ddl_syntax_temporary_external_flags.sql)，S.`temporaryAndExternalDeclarationsKeepTheirIndependentFlags()`；[temporary_shadow_alter_drop](v2_ddl_effect_temporary_shadow_alter_drop.sql)，E.`temporaryAlterAndDropWinNameLookupThenRevealThePermanentTable()`；[temporary_rename_alter_drop_aliases](v2_ddl_effect_temporary_rename_alter_drop_aliases.sql)，E.`temporaryRenameUpdatesAliasesForLaterAlterAndDrop()` |
| `CREATE EXTERNAL TABLE` | 接受；external、location、声明列保存 | 保留当前脚本 schema；不读取外部文件，不从 location 发明源表 | [comments_partition_buckets_delimiters](v2_ddl_syntax_comments_partition_buckets_delimiters.sql)；S.`commentsPartitionBucketsAndEveryDelimiterSurviveNativeHiveWrappers()` |
| `CREATE TABLE AS SELECT` | 接受；查询可含 CTE/derived/聚合 | `CREATE_AS` 写入、建对象、注册定义；按能力发布血缘 | [ctas_nested_aggregate_chain](v2_statement_ctas_nested_aggregate_chain.sql)；原语句组 `nestedAggregateCtasRegistersDefinitionForLaterReads()` |
| `CREATE TEMPORARY TABLE AS SELECT` | 接受；查询与临时标记保存 | 定义用于下游；默认关闭临时血缘发布，创建语句为 `IGNORED` | [temporary_ctas_two_stage_chain](v2_statement_temporary_ctas_two_stage_chain.sql)；原语句组 `temporaryCtasChainIsUnpublishedButStillExpandsFinalSources()` |
| `CREATE TABLE LIKE` | 接受；来源名称保存，不复制其 schema | `CreateObject` 保留 `UNKNOWN` schema 与 `CREATE_LIKE_SCHEMA_UNKNOWN`；下游字段映射保留未知宽度 | [create_like_unknown_schema](v2_ddl_effect_create_like_unknown_schema.sql)；E.`createLikeRetainsUnknownWidthForKnownAndUnknownSources()` |
| `CREATE VIEW ... AS SELECT` | 接受；显式列名、列/视图 COMMENT、查询保存；定义模板保留声明列，重绑时按位置重命名输出 | 注册视图定义，按声明列位置映射并保留来源；代表文件先 DROP 确认不存在，使 IF NOT EXISTS 确定提交，再用 derived 下游读取新别名 | [view_explicit_columns_comments](v2_ddl_syntax_view_explicit_columns_comments.sql)；S.`explicitViewAliasesCommentsAndParenthesizedDerivedQueryProduceMappedLineage()` |
| `CREATE OR REPLACE VIEW` | 接受；`getOrReplace()` / `isOrReplace()` 保存标记；下游展开保留声明列别名 | 当前脚本同名定义由新来源和新列名替换，后续读取新别名回溯 archive；不执行 metastore DDL | [view_or_replace_definition](v2_ddl_syntax_view_or_replace_definition.sql)；S.`orReplaceViewOptionAndReplacementAliasesFlowToLaterReads()` |
| `ALTER VIEW ... AS SELECT` | 接受；新查询保存 | 替换当前脚本定义，后续嵌套视图读取新来源 | [create_alter_view_nested_chain](v2_statement_create_alter_view_nested_chain.sql)；原语句组 `alteringViewReplacesItsSourcesThroughoutTheNestedViewChain()` |
| `ALTER TABLE/VIEW RENAME TO` | 接受；`RenameActionSyntax` 保存新名 | 重命名复用对象身份，临时表的新名称更新 lookup aliases，继续支持 ALTER/DROP；视图定义随新名可读 | [rename_chain_identity_then_drop](v2_ddl_effect_rename_chain_identity_then_drop.sql)，E.`sequentialRenamesReuseIdentityForAnotherAlterAndDrop()`；[view_rename_drop_if_exists](v2_ddl_effect_view_rename_drop_if_exists.sql)，E.`viewRenameKeepsDefinitionAndDropIfExistsDeletesIt()` |
| `ALTER TABLE ADD/REPLACE COLUMNS` | 接受；列定义保存 | 已知 schema 时产生 `AlterObject`，保持分区列角色及末尾顺序；REPLACE 数据列与保留分区键重名时失败，schema 不变；schema 不确定时保留未知效果 | [replace_preserves_partition_roles](v2_ddl_effect_replace_preserves_partition_roles.sql)；E.`addAndReplaceKeepPartitionRolesAndDataColumnsBeforePartitionKeys()`；[replace_partition_name_collision](v2_ddl_effect_replace_partition_name_collision.sql)，E.`replaceRejectsPartitionNameCollisionsWithoutChangingSchema()` |
| `ALTER TABLE CHANGE [COLUMN] old new type [COMMENT] [FIRST/AFTER]` | 接受；旧名、新定义、类型、COMMENT 和位置保存 | 整表已知 schema 时确定更新；重复名、缺失列、非法分区修改/位置失败且不提交 | [change_column_first_after_comments](v2_ddl_effect_change_column_first_after_comments.sql)，E.`changeColumnKeepsTypeCommentAndFirstAfterOrderAndRejectsOldName()`；[change_column_invalid_keeps_schema](v2_ddl_effect_change_column_invalid_keeps_schema.sql)，E.`rejectedColumnChangesKeepTheCommittedSchema()` |
| `DROP TABLE / DROP VIEW [IF EXISTS]` | 接受；family/name/ifExists 保存 | catalog 开启时 `DropObject`；已删除对象下游不可读，重建后使用新 schema；无字段赋值血缘 | [view_rename_drop_if_exists](v2_ddl_effect_view_rename_drop_if_exists.sql)；E.`viewRenameKeepsDefinitionAndDropIfExistsDeletesIt()`；[if_not_exists_known_noop_and_recreate](v2_ddl_effect_if_not_exists_known_noop_and_recreate.sql) |
| `TRUNCATE TABLE [PARTITION(...)]` | 接受；表名可提取；分区条件尚无完整执行模型 | 无数据写入映射；`UnknownEffect(TRUNCATE_VERSION_UNKNOWN)` 影响目标对象，下游读取保留不确定性 | [truncate_partition_unknown](v2_ddl_effect_truncate_partition_unknown.sql)；E.`partitionTruncateHasUnknownVersionEffectWithoutADataWrite()` |

## 重命名、分区冲突与视图别名的下游证据

- [temporary_rename_alter_drop_aliases](v2_ddl_effect_temporary_rename_alter_drop_aliases.sql)：E.`temporaryRenameUpdatesAliasesForLaterAlterAndDrop()` 先建立同名永久对象不存在的删除标记，随后创建并重命名临时表。断言 Rename、Alter、Drop 使用相同 `ObjectId`；新名称具有普通及默认库限定的 lookup aliases，旧普通别名被移除；下游读取新增列成功，DROP 后读取以 `OBJECT_NOT_FOUND` 失败，不能回退到旧临时对象或外部元数据。
- [replace_partition_name_collision](v2_ddl_effect_replace_partition_name_collision.sql)：E.`replaceRejectsPartitionNameCollisionsWithoutChangingSchema()` 用大写 `DT` 与已有分区键 `dt` 制造大小写归一后的冲突。断言 REPLACE 以 `DUPLICATE_COLUMN` 失败；后续 `SELECT *` 仍读取原来的 `id, name, dt`，`name` 与 `dt` 的字段来源保持原表，不提交含重复列的新 schema。
- [view_or_replace_definition](v2_ddl_syntax_view_or_replace_definition.sql)：S.`orReplaceViewOptionAndReplacementAliasesFlowToLaterReads()` 以显式列名 `user_key, display_name` 替换视图定义，随后 INSERT 按这些名字读取。断言下游保留 `dwd.result.id ← dim.ddl_replace_view.user_key ← ods.users_archive.id`，使视图声明别名和替换后的来源同时保留；仅在 CREATE VIEW 当条写入中改列名不足以满足这个用例。完整运行结果以实际验证报告为准。

## CREATE / DROP 选项与类型

| 关键字 / 选项 | SQL 接受及结构证据 | 当前效果或边界 | 资源与断言 |
| --- | --- | --- | --- |
| `BOOLEAN/TINYINT/SMALLINT/INT/BIGINT/FLOAT/DOUBLE/STRING/BINARY/DATE/TIMESTAMP` | 接受；逐列断言类型名称 | 声明类型不代表 Hive 数据值已经验证 | [primitive_parameterized_types](v2_ddl_syntax_primitive_parameterized_types.sql)；S.`primitiveTypesAndNumericParametersRemainStructural()` |
| `DECIMAL(p,s)/CHAR(n)/VARCHAR(n)` | 接受；断言 numeric arguments 的具体参数值 | 不在分析阶段验证数据是否满足精度或长度 | 同上；另见 [cast_primitive_type_parameters](v2_keyword_cast_primitive_type_parameters.sql)，K.`casts()` |
| `ARRAY/MAP/STRUCT` 的递归声明 | 接受；类型参数、struct 字段名与嵌套字段保留；ARRAY 的原生 canonical 名仍为 `list` | 本轮补齐 `list` 到语义 ARRAY 的转换；真实 catalog `CreateObject` 深层断言 ARRAY、MAP 内 ARRAY/DECIMAL、STRUCT 内 labels ARRAY。另有 typed provider 的字段路径测试；v1 元数据不提供类型 | [nested_complex_types](v2_ddl_syntax_nested_complex_types.sql)；S.`recursiveArrayMapStructAndUnionDeclarationsKeepTheirChildren()` 同时检查语法及效果类型 |
| `UNIONTYPE` | parser 接受；语法仍保留 `uniontype/coltypelist` 原生类型容器 | 本轮在语义类型转换中展开 coltypelist，catalog 类型为 UNION，成员为 INTEGER、STRING、ARRAY，其中 ARRAY 内为 STRUCT。此处证明声明类型，不承诺所有联合值访问/执行语义 | 同上；S 同时断言原生语法三成员结构和真实 catalog 的 UNION 成员类型 |
| 列 / 分区列 / 表 / 视图 `COMMENT` | 接受；`getComment()` 断言具体字符串，约束 token 不作为列 COMMENT | 保存语法注释；catalog schema 不持久化或执行注释元数据变更 | [comments_partition_buckets_delimiters](v2_ddl_syntax_comments_partition_buckets_delimiters.sql)，[view_explicit_columns_comments](v2_ddl_syntax_view_explicit_columns_comments.sql)，[skew_constraints_audit_boundary](v2_ddl_syntax_skew_constraints_audit_boundary.sql)；S 对应 comments / explicitView / constraints 方法 |
| `PARTITIONED BY` | 接受；分区列名、类型、注释保存 | Create/Alter shape 保留 partition roles，ADD/REPLACE 不将分区列丢成 data 列 | [replace_preserves_partition_roles](v2_ddl_effect_replace_preserves_partition_roles.sql)；E.`addAndReplaceKeepPartitionRolesAndDataColumnsBeforePartitionKeys()`；[replace_partition_name_collision](v2_ddl_effect_replace_partition_name_collision.sql)，E.`replaceRejectsPartitionNameCollisionsWithoutChangingSchema()` |
| `IF NOT EXISTS` | 接受；option flag 保存 | 已知存在则不改 schema；已知不存在可确定创建；存在性未知为 `CONDITIONAL/OBJECT_ABSENT`，下游不能假设创建成功 | [if_not_exists_known_noop_and_recreate](v2_ddl_effect_if_not_exists_known_noop_and_recreate.sql)，[if_not_exists_unknown_precondition](v2_ddl_effect_if_not_exists_unknown_precondition.sql)；E 对应 known/unknown 方法 |
| `IF EXISTS` | 接受；option flag 保存 | `DropObject` 保存 `OBJECT_PRESENT` 前提；没有引擎执行保证 | [if_exists_like_options](v2_ddl_syntax_if_exists_like_options.sql)，S.`ifFlagsAndLikeSourceNameDoNotInventDataWrites()`；E view drop 方法 |
| `ROW FORMAT DELIMITED` 的 `FIELDS TERMINATED BY/ESCAPED BY/COLLECTION ITEMS/MAP KEYS/LINES/NULL DEFINED AS` | 全部接受；分别断言六个 delimiter getters | 修复 `TOK_SERDEPROPS` wrapper 丢失；保存字符串，不运行 SerDe 或全面解释 Hive 转义行为 | [comments_partition_buckets_delimiters](v2_ddl_syntax_comments_partition_buckets_delimiters.sql)；S.`commentsPartitionBucketsAndEveryDelimiterSurviveNativeHiveWrappers()` |
| `ROW FORMAT SERDE ... WITH SERDEPROPERTIES` | 接受；断言真实 class 名与两项属性 | 修复 `TOK_SERDENAME` wrapper；不加载 class；与表 TBLPROPERTIES 分开保存 | [serde_class_properties](v2_ddl_syntax_serde_class_properties.sql)；S.`serdeClassAndSerdePropertiesAreSeparateFromTableProperties()` |
| `STORED AS TEXTFILE/SEQUENCEFILE/RCFILE/ORC/PARQUET/AVRO/JSONFILE` | 接受；逐条断言 generic format 名称 | 不模拟格式读写，不从格式名制造输入对象 | [generic_storage_formats](v2_ddl_syntax_generic_storage_formats.sql)；S.`genericStoredAsFormatsAreNamesWithoutDataInputs()` |
| `CLUSTERED BY/SORTED BY ASC/DESC/INTO n BUCKETS` | 接受；断言列名、排序方向和桶数；另检查无 SORTED BY 的形态 | 修复 CREATE 使用 `TOK_ALTERTABLE_BUCKETS` 的差异；不模拟分桶执行 | [comments_partition_buckets_delimiters](v2_ddl_syntax_comments_partition_buckets_delimiters.sql)，[bucket_without_sorted_columns](v2_ddl_syntax_bucket_without_sorted_columns.sql)；S 对应 bucket 方法 |
| `LOCATION/TBLPROPERTIES` | 接受；location 值、表属性逐项断言 | 声明仅保存语法，ALTER 仍是未知效果；路径不当作数据文件来源 | [comments_partition_buckets_delimiters](v2_ddl_syntax_comments_partition_buckets_delimiters.sql)，[serde_class_properties](v2_ddl_syntax_serde_class_properties.sql) |
| `STORED AS INPUTFORMAT ... OUTPUTFORMAT` / `STORED BY` | parser 接受；仅保留声明列，现有 storage model 没有相应字段 | 自定义格式/handler 字符串仍可在原 SQL 审计；`getFileFormat()/getSerde()` 为 null，不能宣称结构化完整保留 | [custom_storage_audit_boundary](v2_ddl_syntax_custom_storage_audit_boundary.sql)；S.`customFormatsAndHandlersRemainExplicitlyUnmodeledStorageBoundaries()` |
| `SKEWED BY ... ON ... STORED AS DIRECTORIES` | parser 接受；列 schema 保存 | skew option 尚无结构化模型或执行效果 | [skew_constraints_audit_boundary](v2_ddl_syntax_skew_constraints_audit_boundary.sql)；S.`constraintsCannotBeMisclassifiedAsColumnComments()` 仅验证列结构/注释边界 |
| `PRIMARY KEY ... DISABLE NOVALIDATE/NOT NULL` | parser 接受；列名/类型仍保存，constraints 不误作 COMMENT | 未完整归一化约束、校验或依约束推断血缘 | 同上；S 对 id/name 的 COMMENT 明确断言 null，对真实 amount COMMENT 断言具体值 |
| `PURGE` / `CASCADE/RESTRICT` 的完整执行语义 | DROP PURGE、DB DROP CASCADE/RESTRICT 的代表 SQL 可接受 | 当前没有 PURGE option 字段；数据库 cascade 不实际删除目录；列 ALTER 的 cascade/restrict 分区传播未建模，也未宣称所有组合覆盖 | [if_exists_like_options](v2_ddl_syntax_if_exists_like_options.sql)，[database_schema_lifecycle_audit](v2_ddl_syntax_database_schema_lifecycle_audit.sql)；只证明代表 SQL 与审计状态 |

## ALTER 未绑定动作的保守效果

对已归一化的 `ALTER TABLE/VIEW`，尚未绑定的原生动作保存为 `UnknownAlterActionSyntax`。catalog 能力开启时产生 `UnknownEffect(CATALOG_ACTION_NOT_BOUND)`，包含受影响对象；后续读取被标记不确定，其他已知对象仍可正常分析。对应契约也要求已知 schema 经过未知动作后，不能仅因 RENAME 再次变成已证 schema；重命名需保留对象的不确定性。保留未知效果比丢掉动作后沿用旧 schema 更可靠，但不代表分区、存储或维护执行已实现。

| 动作 / 范围 | 结构及效果 | 文件型证据与检查 |
| --- | --- | --- |
| 整表 `SET TBLPROPERTIES/SET LOCATION` | 分别有 Properties/SetLocation syntax；效果未知，无写入贡献 | [catalog_properties_location_truncate_boundary](v2_statement_catalog_properties_location_truncate_boundary.sql)；原语句组 `propertiesLocationAndTruncateExplicitlyRetainUnknownCatalogEffects()` |
| `ADD IF NOT EXISTS PARTITION ... LOCATION` / `DROP IF EXISTS PARTITION ... PURGE` / 分区 `RENAME TO` | 原生动作名保存，尚无完整分区元数据模型；对象范围未知效果 | [partition_add_drop_rename_unknown](v2_ddl_effect_partition_add_drop_rename_unknown.sql)；E.`partitionAddDropAndRenameRetainUnknownEffectsInsteadOfEmptyActions()` |
| 分区作用域 `ADD/REPLACE/CHANGE COLUMNS` | root PARTSPEC 导致动作降为未知，不伪装成确定整表 schema 修改 | [partition_scoped_column_changes_unknown](v2_ddl_effect_partition_scoped_column_changes_unknown.sql)；E.`partitionScopedColumnsDoNotChangeTheWholeTableSchema()` |
| `SET SERDE/SET SERDEPROPERTIES/SET FILEFORMAT/CLUSTERED ... SORTED ... BUCKETS` | 未绑定动作名与目标对象保存；后续读取保留不确定性 | [serde_fileformat_bucket_unknown](v2_ddl_effect_serde_fileformat_bucket_unknown.sql)；E.`serdeFormatAndBucketingHaveExplicitObjectScopedBoundaries()` |
| `TOUCH/COMPACT/CONCATENATE/UPDATE STATISTICS` | 未绑定动作名和对象范围未知效果；不生成字段赋值 | [maintenance_touch_compact_stats_unknown](v2_ddl_effect_maintenance_touch_compact_stats_unknown.sql)；E.`maintenanceActionsHaveExplicitObjectScopedBoundaries()` |
| 其余 `TOK_ALTER*`，例如 UNSET properties、分区 SET LOCATION/FILEFORMAT、ARCHIVE/UNARCHIVE、owner/constraint/skew 变更 | 有统一 UnknownAlterAction fallback；不能推断具体 action 语义或结构全部保存 | 本轮没有为这些每一个组合提供独立资源/效果断言；列为剩余组合，不从 fallback 推导“完整支持” |

## 审计语句与明确失败边界

| Family / 关键字 | SQL 接受 / 结构 | 引擎状态及效果 | 资源与断言 |
| --- | --- | --- | --- |
| `CREATE/DROP FUNCTION [IF EXISTS] USING JAR/FILE/ARCHIVE` | 接受；FunctionCommand 保存名称、class、临时标记及资源种类/URI | `IGNORED`，无正式血缘和 catalog 效果，不加载外部资源 | [permanent_function_resources](v2_ddl_syntax_permanent_function_resources.sql)；S.`permanentFunctionCommandsPreserveAllResourcesWithoutLoadingThem()`；临时版本见 [resource_function_source_commands](v2_statement_resource_function_source_commands.sql) |
| `CREATE/DROP TEMPORARY MACRO` / `RELOAD FUNCTIONS` | 接受；OtherStatement 保留原生分类与原 SQL，未结构化宏表达式/参数 | `IGNORED`，无 bound unit/effects/正式血缘 | [macros_reload_functions_audit](v2_ddl_syntax_macros_reload_functions_audit.sql)；S.`macrosAndFunctionReloadAreAuditOnly()` |
| `CREATE DATABASE/SCHEMA IF NOT EXISTS COMMENT/LOCATION/DBPROPERTIES` | 接受；SCHEMA 归同一 native database family；Other 审计保留原 SQL | `IGNORED`；不模拟数据库目录、location 或权限 | [database_schema_lifecycle_audit](v2_ddl_syntax_database_schema_lifecycle_audit.sql)；S.`databaseAndSchemaLifecycleDoesNotSimulateNamespacesOrPermissions()` |
| `ALTER DATABASE SET DBPROPERTIES/OWNER USER/LOCATION` | 接受；Other 分类区分 properties/owner/location | `IGNORED`，无 bound unit；选项不是结构化数据库语义 | 同上；逐条检查 native classification 与无效果状态 |
| `DROP DATABASE/SCHEMA IF EXISTS CASCADE/RESTRICT` | 接受；Other database classification | `IGNORED`；不会模拟 cascade 删除表或目录 | 同上 |
| `CREATE MATERIALIZED VIEW AS SELECT` / `ALTER MATERIALIZED VIEW REBUILD` | parser/normalizer 接受；尚无对应定义/数据写入绑定 | 涉及未支持的数据写入，明确 `FAILED/UNSUPPORTED_STATEMENT`；无 bound unit/effects/正式血缘 | [materialized_view_lifecycle_audit](v2_ddl_syntax_materialized_view_lifecycle_audit.sql)；S.`materializedViewWritesFailExplicitlyWhileDropRemainsAuditOnly()` |
| `DROP MATERIALIZED VIEW IF EXISTS` | parser 接受；Other 审计 | `IGNORED`，无 bound unit/effects；没有物化视图目录模拟 | 同上，第三条语句独立状态断言 |
| `IMPORT TABLE ... FROM` / `EXPORT TABLE ... TO` | parser/normalizer 接受；没有数据搬运绑定 | 明确 `FAILED/UNSUPPORTED_STATEMENT`；失败不提交效果，后续普通 INSERT 继续 | [import_export_unsupported_boundary](v2_ddl_syntax_import_export_unsupported_boundary.sql)；S.`importExportFailExplicitlyAndLaterOrdinaryInsertContinues()` |
| `ANALYZE ... COMPUTE STATISTICS [FOR COLUMNS]` / `MSCK REPAIR TABLE` | parser 接受；Other 分类保留 | `IGNORED`，不执行数据读取、统计或 metastore 修复 | [statistics_msck_audit](v2_ddl_syntax_statistics_msck_audit.sql)；S.`statisticsAndMetastoreRepairAreAuditedWithoutExecution()` |
| `SHOW DATABASES/TABLES/VIEWS/FUNCTIONS/PARTITIONS/TBLPROPERTIES/CREATE TABLE` / `DESCRIBE FORMATTED/EXTENDED` / `EXPLAIN` | 接受代表组合；逐条检查 native classification | `IGNORED`，计划中的 SELECT 不发布为写入血缘 | [show_describe_explain_audit](v2_ddl_syntax_show_describe_explain_audit.sql)；S.`showDescribeAndExplainDoNotPublishPlanQueriesAsWrites()` |
| 无查询 malformed DDL，例如列缺失类型 | parser 出错，限于非查询 DDL 恢复为 Other；前端 `PARTIAL` + `HIVE_SYNTAX_ERROR/INVALID_DDL` | 仅错误审计，`IGNORED`，无 catalog 效果；随后合法 INSERT 正常分析 | [malformed_nonquery_recovery_then_insert](v2_ddl_syntax_malformed_nonquery_recovery_then_insert.sql)；S.`malformedNonqueryDdlRecoveryRetainsDiagnosticsAndContinuesTheScript()` |
| malformed CTAS / 含数据查询的错误语句 | 不用非查询 DDL 恢复伪造有效查询 | SQL 解析失败，失败语句不提交效果 | [HiveFrontendTest.malformedDdlIsAuditedButMalformedDataQueryStillFails()](../../../java/com/panfan/hivelineage/v2/dialect/hive/HiveFrontendTest.java)；已有 frontend 内联边界，不能算新增 SQL 文件型组合 |
| INDEX、旧 NO_DROP / RECOVER PARTITIONS 形式 | 本轮 parser probe 发现部分旧语句被拒绝或限于 INVALID_DDL 审计 | 不列入 SQL 支持承诺；本轮未建立每种旧语法的稳定文件型契约 | 仍需单独扩展拒绝矩阵，不能以 Hive 的其他版本行为替代本项目证据 |
| DATACONNECTOR、角色/授权、resource plan/pool/trigger、scheduled query、table branch/tag/convert/execute 等其他 Hive family | 本项目没有专门语义绑定；本轮未验证所有具体语句 | 若 parser 接受可能仅 Other 审计；不承诺其 namespace、权限、调度或数据变更行为 | 没有对应新增集成资源；明确列为未覆盖范围 |

## 查询关键字的补充核对

| 场景 | 本轮结构/血缘证据 | 边界 |
| --- | --- | --- |
| `GROUP BY/ORDER BY/SORT BY/DISTRIBUTE BY/CLUSTER BY` 中的标量子查询 | 五份 `v2_keyword_nested_*_scalar_subquery.sql`；K.`clauseSubquery()` 检查输入同时含 users/events、query block 路径和精确输出；TABLE_ONLY 另使用拒绝元数据查询的 provider 证明对象遍历不额外索取字段 | 补齐此前漏遍历子查询的五处；字段模式中分发/聚簇 key 作为隐藏计算保留依赖，随后隐藏其输出，不冒充业务值映射；不模拟分发/聚簇执行，scalar cardinality 仍未知 |
| named window 继承及 `ROWS/RANGE/FOLLOWING` | [window_named_inheritance_following_frames](v2_keyword_window_named_inheritance_following_frames.sql)；K.`windows()` 检查继承的分区、排序、frame 起止、offset 与继承分区里的 scalar 来源 | [window_unknown_and_recursive_boundaries](v2_keyword_window_unknown_and_recursive_boundaries.sql) 明确 `UNKNOWN_WINDOW/RECURSIVE_WINDOW_DEFINITION` 与部分结果 |
| `TABLESAMPLE BUCKET/PERCENT/ROWS/100B` | [sample_bucket_percent_rows_byte_length](v2_keyword_sample_bucket_percent_rows_byte_length.sql)；K.`samples()` 逐条检查 sample kind、参数数量、literal 拼写和来源 | ROWS/BYTE_LENGTH 单位标记不能被当成数值表达式；没有采样执行模型 |
| `SELECT ALL` / 默认 UNION/INTERSECT/EXCEPT quantifier | [select_all_default_set_quantifiers](v2_keyword_select_all_default_set_quantifiers.sql)；K.`quantifiers()` 检查 quantifier、set operator、duplicate policy、分支列来源 | 不证明 SQL 执行后的集合行数 |
| `NOT EXISTS`、剩余 unary/comparison、boolean/date/timestamp/相邻字符串、primitive CAST 参数 | [not_exists_correlated](v2_keyword_not_exists_correlated.sql)、[remaining_unary_comparison_operators](v2_keyword_remaining_unary_comparison_operators.sql)、[literal_boolean_datetime_adjacent_strings](v2_keyword_literal_boolean_datetime_adjacent_strings.sql)、[cast_primitive_type_parameters](v2_keyword_cast_primitive_type_parameters.sql)；K 对应 notExists/operators/literals/casts 方法检查结构与精确来源 | 不执行函数或类型转换；literal 为常量来源 |
| `INTERVAL` / `JOIN USING` | [interval_internal_function_boundary](v2_keyword_interval_internal_function_boundary.sql)，K.`intervals()` 检查 native internal_interval；[join_using_partial_boundary](v2_keyword_join_using_partial_boundary.sql)，K.`usingBoundary()` 检查 unknown ON 及已知投影 | interval 目前按 opaque function 保留 `OPAQUE_FUNCTION/PARTIAL`；USING 未完整归一化，保留 `UNSUPPORTED_EXPRESSION/OUTPUT_SCHEMA_UNKNOWN/PARTIAL_SUCCESS` |
| `SET` 查询形式、反引号 USE、资源 ADD/LIST/DELETE | [session_queries_quoted_use_resource_kinds](v2_keyword_session_queries_quoted_use_resource_kinds.sql)；K.`commands()` 逐条检查种类、namespace、资源数、quote kind 和后续默认库 | 13 条正常命令为 IGNORED；单引号数据库名为前端 PARTIAL、引擎 `FAILED/INVALID_USE_DATABASE`，不改变此前 ods 默认库；最后 INSERT 正常；资源不执行 |

## 后续扩展原则

新增支持时同时补资源、结构 getters、原生效果和需要的下游来源断言，并在相应 registry 中注册。只有关键字出现而没有精确断言的组合不能算语义覆盖。对未知作用域和未知 schema 保留诊断；对尚未绑定的数据写入明确失败，避免把数据遗漏伪装成正常审计。

此清单仍保留未完整模型化的 CREATE storage/constraint、ALTER 其余动作、database/权限/调度 family 和组合空间。覆盖合同确保本项目已声明 family 与已注册文件不被漏测，不给整个 Hive SQL 方言的穷尽支持保证。
