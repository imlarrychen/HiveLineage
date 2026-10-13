# Coverage: 既有需求追踪与 V2 验收范围

## 1. 状态与使用方式

方案已批准，V2 已实现独立前端、按需绑定、验证、纯 IR 推导、Metadata/Session 编排和兼容输出。本表逐行记录实际语义断言，不以解析成功率代替血缘正确性。2026-10-12 的独立 javac/JUnit 验证中，OpenSpecConformanceTest 38 个测试、Binder 19 个测试、原 JSON 示例 2 个测试、既有目标映射回归 6 个测试共 65 个定向测试通过；测试数与下表 77 个规范追踪行是不同口径。全仓统一测试、184 个既有断言对照及环境集成记录见 [validation.md](validation.md)。

下表覆盖原两份 spec 的全部“场景”及“示例”标题，保留源行定位。来源文档不作修改。简称 engine、compat 分别指本变更的内核需求和兼容需求。

## 2. 既有规范与当前行为分歧

这些分歧已经存在于仓库，不是新语义架构要求更改旧需求。按如下命名契约承接；每一项有单独断言，既有矛盾不由同一个默认策略掩盖：

| 编号 | 现存差异 | 拟采用的兼容与验收方式 |
|---|---|---|
| D-01 | 原 spec 对未知变量保留原文并告警；当前测试要求残留变量统一兜底且无 warning | 既有入口使用 CURRENT_API_FALLBACK；PRESERVE_UNKNOWN 保留所有未解析变量；新增 SPEC_VARIABLES 同时提供明确内置白名单兜底、未知变量保留告警与环境模板兜底。联合策略已由同一 SQL 的三种占位符断言验收，内部保留展开来源 |
| D-02 | spec 的星号场景允许忽略源独有列；design、README 和位置映射规则要求已知数量一致；V1 对普通投影还支持缺目标结构时按输出名推断及目标列较多时取前 N 列 | 原生 POSITIONAL_EXACT 保持位置及数量校验；兼容入口 CURRENT_API_TARGET_MAPPING 显式承接旧目标推断、普通无显式列的前 N 位置及显式星号按名匹配。EXPLICIT_LEGACY_TARGET_MAPPING 只保留显式星号按名匹配；SPEC_TARGET_SCHEMA_MAPPING 单独复现原 L-13 无显式列的目标元数据匹配。假设均记录策略诊断、PARTIAL 映射与 UNDETERMINED 有效性，不声称 SQL 执行合法 |
| D-03 | Join code 7 在枚举为 CROSS_JOIN，在逗号场景要求 IMPLICIT_JOIN | 内部类型分别表示；兼容输出保留数字及可读名称组合，原场景分别验收 |
| D-04 | Join spec 示例要求直接字段保留 a.id；当前 V1 测试常要求解析后完整物理字段名 | 内部同时保存 lexicalReference 和 resolvedReference；旧 DTO 投影和规范独立导出分别验证 |
| D-05 | 旧 tasks、spec 和代码可能不同步，例如 failFast、对象级轻量读取、独立 Join DTO 身份 | 按规范验收新增能力；不由勾选框推定已实现，也不把原实现缺失当作需求取消 |

兼容策略集中于前端、绑定、映射和投影契约，不按 SQL 文件名或完整 SQL 字符串写生产特例。V1 真正的错误字段来源和作用域缺陷按已确认改进登记，不作为兼容标准复制。

## 3. 主血缘需求逐场景表

| ID | 原场景或示例 | V2 实现机制 | 目标契约 | 实现 / 测试 / 结论 |
|---|---|---|---|---|
| L-01 | [使用 Hive parser adapter 隔离解析依赖](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L9) | engine: 独立内核 / 方言隔离 | Java 8 + Maven + Hive 4.0.1；无直接持久化 | 已通过；`A.typedCoreCannotDependOnHiveOrExistingDto`、`A.frontendDoesNotAccessSessionCatalogOrMetadata`，Java 8 编译、Hive 4.0.1 方言工厂 |
| L-02 | [第一版不实现持久化](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L17) | engine: 独立内核 / 方言隔离 | Java 8 + Maven + Hive 4.0.1；无直接持久化 | 已通过；内核无持久化依赖；`A.typedCoreCannotDependOnHiveOrExistingDto`；导出由调用方保存 |
| L-03 | [解析包含多条语句的任务脚本](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L29) | engine: Session 生命周期；compat: 源码与身份 | 顺序、业务身份、原始 SQL | 已通过；`C.statementPublicationCountsRecoveryAndRawIdentity` |
| L-04 | [成功且不产生血缘的语句默认不返回结果](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L45) | compat: 公开资格 / 结果恢复 | 旧 DTO、审计选项、失败记录、统计 | 已通过；`C.statementPublicationCountsRecoveryAndRawIdentity` |
| L-05 | [开启忽略语句审计后返回被忽略语句](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L54) | compat: 公开资格 / 结果恢复 | 旧 DTO、审计选项、失败记录、统计 | 已通过；`C.auditedIgnoredStatementsKeepNoWritesAndTaskVersionsStayDistinct` |
| L-06 | [语句结果记录原始 SQL 和标准化 SQL](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L63) | compat: 公开资格 / 结果恢复 | 旧 DTO、审计选项、失败记录、统计 | 已通过；`C.statementPublicationCountsRecoveryAndRawIdentity` |
| L-07 | [语句级结果作为持久化单元](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L71) | compat: 公开资格 / 结果恢复 | 旧 DTO、审计选项、失败记录、统计 | 已通过；`P.fullExportsReuseSinkFactsAndRetainStatementIdentity`；每条记录含父语句身份 |
| L-08 | [解析失败语句必须返回结果](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L80) | compat: 公开资格 / 结果恢复 | 旧 DTO、审计选项、失败记录、统计 | 已通过；`C.statementPublicationCountsRecoveryAndRawIdentity` |
| L-09 | [脚本级包装对象不是持久化单元](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L90) | compat: 公开资格 / 结果恢复 | 旧 DTO、审计选项、失败记录、统计 | 已通过；`P.fullExportsReuseSinkFactsAndRetainStatementIdentity`；每条记录含父语句身份 |
| L-10 | [同一任务存在不同版本](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L102) | compat: 结果身份 | taskVersion 为业务版本，算法版本另记 | 已通过；`C.auditedIgnoredStatementsKeepNoWritesAndTaskVersionsStayDistinct` |
| L-11 | [纯查询语句完全忽略](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L115) | compat: 公开资格；engine: SinkPlanner | 旧资格；星号差异见 D-02 | 已通过；`C.auditedIgnoredStatementsKeepNoWritesAndTaskVersionsStayDistinct` |
| L-12 | [Insert Select 产生正式血缘](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L125) | compat: 公开资格；engine: SinkPlanner | 旧资格；星号差异见 D-02 | 已通过；`C.formalInsertAndModesKeepTableAndColumnCapabilitiesOrthogonal` |
| L-13 | [Select Star 按目标元数据对齐字段](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L132) | compat: 公开资格；engine: SinkPlanner | 旧资格；星号差异见 D-02 | 命名策略兼容；`C.specWildcardMappingIsNamedAndNeverSilentlyAppliedToPositionalRequests`；原示例保留字 user 使用反引号，同一对象名；默认精确位置策略仍报数量不符 |
| L-14 | [Insert Values 完全忽略](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L142) | compat: 公开资格；engine: SinkPlanner | 旧资格；星号差异见 D-02 | 已通过；`C.auditedIgnoredStatementsKeepNoWritesAndTaskVersionsStayDistinct` |
| L-15 | [只解析表血缘](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L155) | engine: 渐进绑定；compat: 正交能力 | 执行前规划；字段访问按需 | 已通过；`C.formalInsertAndModesKeepTableAndColumnCapabilitiesOrthogonal` |
| L-16 | [只解析字段血缘](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L165) | engine: 渐进绑定；compat: 正交能力 | 执行前规划；字段访问按需 | 已通过；`C.formalInsertAndModesKeepTableAndColumnCapabilitiesOrthogonal` |
| L-17 | [同时解析表血缘和字段血缘](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L174) | engine: 渐进绑定；compat: 正交能力 | 执行前规划；字段访问按需 | 已通过；`C.formalInsertAndModesKeepTableAndColumnCapabilitiesOrthogonal` |
| L-18 | [表血缘解析不依赖字段元数据](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L182) | engine: 渐进绑定；compat: 正交能力 | 执行前规划；字段访问按需 | 已通过；`C.tableOnlyMissingMetadataNeverLoadsColumnsOrWarns`；轻量视图判定见 `V2ApiIntegrationTest.tableOnlyStarAndSubqueryTraverseObjectsWithoutSchema`；V1 bundled Provider 的对象读取可能附带字段，能力声明显式说明 |
| L-19 | [计算表达式字段血缘](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L194) | engine: 表达式 / 依赖角色；compat: 字段投影 | 旧表达式、分类、窗口与分区 | 已通过；`C.arithmeticFunctionConstantAndUnknownUdfKeepExactDependencies` |
| L-20 | [函数表达式字段血缘](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L213) | engine: 表达式 / 依赖角色；compat: 字段投影 | 旧表达式、分类、窗口与分区 | 已通过；`C.arithmeticFunctionConstantAndUnknownUdfKeepExactDependencies` |
| L-21 | [常量字段血缘](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L230) | engine: 表达式 / 依赖角色；compat: 字段投影 | 旧表达式、分类、窗口与分区 | 已通过；`C.arithmeticFunctionConstantAndUnknownUdfKeepExactDependencies` |
| L-22 | [静态分区和动态分区字段对齐](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L246) | engine: 表达式 / 依赖角色；compat: 字段投影 | 旧表达式、分类、窗口与分区 | 已通过；`C.partitionsKeepStaticAndNoFieldUdfOutputsFreeOfFakeSources`；`B.staticAndDynamicPartitionsMapSeparately` |
| L-23 | [无源动态分区表达式不产生字段血缘边](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L265) | engine: 表达式 / 依赖角色；compat: 字段投影 | 旧表达式、分类、窗口与分区 | 已通过；`C.partitionsKeepStaticAndNoFieldUdfOutputsFreeOfFakeSources`；`B.staticAndDynamicPartitionsMapSeparately` |
| L-24 | [Union 分支字段表达式](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L283) | engine: 表达式 / 依赖角色；compat: 字段投影 | 旧表达式、分类、窗口与分区 | 已通过；`C.unionAllDistinctAndConstantsHaveBranchExpressions`；原生部分未知分支见 `B.partialSetRetainsKnownBranchesAndExplicitUnknownWidth` |
| L-25 | [Plain Union 分支字段表达式](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L299) | engine: 表达式 / 依赖角色；compat: 字段投影 | 旧表达式、分类、窗口与分区 | 已通过；`C.unionAllDistinctAndConstantsHaveBranchExpressions`；原生部分未知分支见 `B.partialSetRetainsKnownBranchesAndExplicitUnknownWidth` |
| L-26 | [Union 分支常量表达式](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L315) | engine: 表达式 / 依赖角色；compat: 字段投影 | 旧表达式、分类、窗口与分区 | 已通过；`C.unionAllDistinctAndConstantsHaveBranchExpressions`；原生部分未知分支见 `B.partialSetRetainsKnownBranchesAndExplicitUnknownWidth` |
| L-27 | [聚合表达式字段血缘](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L331) | engine: 表达式 / 依赖角色；compat: 字段投影 | 旧表达式、分类、窗口与分区 | 已通过；`C.aggregatesAndWindowsExposeOnlyRealFunctions` |
| L-28 | [窗口函数表达式字段血缘](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L349) | engine: 表达式 / 依赖角色；compat: 字段投影 | 旧表达式、分类、窗口与分区 | 已通过；`C.aggregatesAndWindowsExposeOnlyRealFunctions` |
| L-29 | [未知 UDF 降级](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L366) | engine: 表达式 / 依赖角色；compat: 字段投影 | 旧表达式、分类、窗口与分区 | 已通过；`C.arithmeticFunctionConstantAndUnknownUdfKeepExactDependencies` |
| L-30 | [表达式无法稳定提取时不阻断字段血缘](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L384) | engine: 表达式 / 依赖角色；compat: 字段投影 | 旧表达式、分类、窗口与分区 | 已通过；`C.unavailableExpressionTextDoesNotChangeProvenDependenciesOrClaimExtraction`；同一已分析 IR 移除源码附件，来源不变、expressionExtracted=false |
| L-31 | [Set 变量被后续 Insert 使用](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L404) | compat: 变量与源码；engine: Session | 当前 API 命名空间、优先级及逐句展开 | 已通过；`C.variableNamespaceFallbackRawTextAndEnvironmentPlaceholder`；当前 API 行为；原规范同一策略联合断言见 `C.specVariablePolicyFulfillsBuiltinUnknownAndEnvironmentRulesTogether` |
| L-32 | [Hivevar 命名空间变量被后续语句使用](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L411) | compat: 变量与源码；engine: Session | 当前 API 命名空间、优先级及逐句展开 | 已通过；`C.variableNamespaceFallbackRawTextAndEnvironmentPlaceholder`；当前 API 行为；原规范同一策略联合断言见 `C.specVariablePolicyFulfillsBuiltinUnknownAndEnvironmentRulesTogether` |
| L-33 | [内置日期变量未解析](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L423) | compat: 变量策略 | CURRENT_API_FALLBACK、PRESERVE_UNKNOWN、SPEC_VARIABLES 分开；见 D-01 | 已通过；`C.variableNamespaceFallbackRawTextAndEnvironmentPlaceholder`；当前 API 行为；原规范同一策略联合断言见 `C.specVariablePolicyFulfillsBuiltinUnknownAndEnvironmentRulesTogether` |
| L-34 | [未知变量无法解析](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L431) | compat: 变量策略 | CURRENT_API_FALLBACK、PRESERVE_UNKNOWN、SPEC_VARIABLES 分开；见 D-01 | 命名策略兼容；`F.preservationAndFallbackHaveDifferentEvidenceAndWarnings`；`C.specVariablePolicyFulfillsBuiltinUnknownAndEnvironmentRulesTogether` 已过；同一 contract 同时验收 builtin 兜底、unknown 保留告警和环境兜底 |
| L-35 | [环境变量兜底替换](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L439) | compat: 变量策略 | CURRENT_API_FALLBACK、PRESERVE_UNKNOWN、SPEC_VARIABLES 分开；见 D-01 | 已通过；`C.variableNamespaceFallbackRawTextAndEnvironmentPlaceholder`；当前 API 行为；原规范同一策略联合断言见 `C.specVariablePolicyFulfillsBuiltinUnknownAndEnvironmentRulesTogether` |
| L-36 | [元数据接口先使用 Mock 实现](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L453) | compat: Provider / 缓存；engine: 生命周期 | 单源作用域、批次固定、版本无关 | 已通过；`C.arithmeticFunctionConstantAndUnknownUdfKeepExactDependencies`；仅 mock V1 Provider 经新适配层；`M.adapterPreservesOrderedColumnsAndViewContext` |
| L-37 | [生产元数据可由 Metastore MySQL 适配](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L460) | compat: Provider / 缓存；engine: 生命周期 | 单源作用域、批次固定、版本无关 | 已通过（接口适配）；`M.adapterPreservesOrderedColumnsAndViewContext`；既有 MySQL Provider 可经同一 V1 adapter 使用，真实 MySQL/JDBC 集成待环境启用，未声称本轮连接生产库 |
| L-38 | [同一张表出现在多个任务中](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L467) | compat: Provider / 缓存；engine: 生命周期 | 单源作用域、批次固定、版本无关 | 已通过；`C.metadataAndDefinitionSyntaxAreReusedAcrossTasksAndVersions`；实际 metadata 加载与 definition parse 各一次 |
| L-39 | [任务版本不影响元数据缓存](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L474) | compat: Provider / 缓存；engine: 生命周期 | 单源作用域、批次固定、版本无关 | 已通过；`C.metadataAndDefinitionSyntaxAreReusedAcrossTasksAndVersions`；实际 metadata 加载与 definition parse 各一次 |
| L-40 | [只解析表血缘不触发字段元数据加载](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L482) | compat: Provider / 缓存；engine: 生命周期 | 单源作用域、批次固定、版本无关 | 已通过；`C.tableOnlyMissingMetadataNeverLoadsColumnsOrWarns`；轻量视图判定见 `V2ApiIntegrationTest.tableOnlyStarAndSubqueryTraverseObjectsWithoutSchema`；V1 bundled Provider 的对象读取可能附带字段，能力声明显式说明 |
| L-41 | [临时表作为最终目标的上游](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L494) | compat: 临时对象；engine: Slot / Session | 旧显示名、默认穿透和可选边界 | 已通过；`C.temporaryObjectsNormalizeNamesAndTakePriorityOverMetadata`；Session 遮蔽、限定与不限定临时名匹配 |
| L-42 | [带库名的临时表使用统一临时库](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L502) | compat: 临时对象；engine: Slot / Session | 旧显示名、默认穿透和可选边界 | 已通过；`C.temporaryObjectsNormalizeNamesAndTakePriorityOverMetadata`；Session 遮蔽、限定与不限定临时名匹配 |
| L-43 | [CTE 不作为临时表节点输出](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L516) | compat: ScriptDefinitionStore；engine: Scope | 本语句 CTE + 前序来源兼容目录 | 已通过；`C.ctasCteExpandsFieldsWithoutPhysicalOrTemporaryCteObject` |
| L-44 | [兼容后续语句引用前序纯查询 CTE](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L534) | compat: ScriptDefinitionStore；engine: Scope | 本语句 CTE + 前序来源兼容目录 | 已通过；`C.pureQueryCteCanBeReusedWithoutCreatingPhysicalCteObjects`；只在 CURRENT_API_CTE_REUSE 下跨句保存结构模板 |
| L-45 | [通过元数据判断输入对象是否为视图](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L549) | engine: 视图模板；compat: 视图边界 | Provider 识别、边界、缓存及 VIEW_* | 已通过；`C.viewsKeepBoundaryEdgesAndExpandUnderlyingSourcesWhenEnabled`；`V2ApiIntegrationTest.metadataViewUsesCreationDatabaseAndFailuresRecover` |
| L-46 | [临时表优先于 Hive 元数据](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L558) | engine: 视图模板；compat: 视图边界 | Provider 识别、边界、缓存及 VIEW_* | 已通过；`C.temporaryObjectsNormalizeNamesAndTakePriorityOverMetadata`；Session 遮蔽、限定与不限定临时名匹配 |
| L-47 | [读取视图时展开到真实上游表](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L566) | engine: 视图模板；compat: 视图边界 | Provider 识别、边界、缓存及 VIEW_* | 已通过；`C.viewsKeepBoundaryEdgesAndExpandUnderlyingSourcesWhenEnabled`；`V2ApiIntegrationTest.metadataViewUsesCreationDatabaseAndFailuresRecover` |
| L-48 | [重复读取同一视图时复用展开缓存](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L580) | engine: 视图模板；compat: 视图边界 | Provider 识别、边界、缓存及 VIEW_* | 已通过；`C.metadataAndDefinitionSyntaxAreReusedAcrossTasksAndVersions`；实际 metadata 加载与 definition parse 各一次 |
| L-49 | [视图定义缺失时降级](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L588) | engine: 视图模板；compat: 视图边界 | Provider 识别、边界、缓存及 VIEW_* | 已通过；`C.missingViewDefinitionIsRecoverableWithSpecificWarning` |
| L-50 | [视图递归展开深度限制](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L596) | engine: 视图模板；compat: 视图边界 | Provider 识别、边界、缓存及 VIEW_* | 已通过；`C.viewCycleAndDepthAreRecoverableWithSpecificWarnings` |
| L-51 | [视图循环依赖](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L604) | engine: 视图模板；compat: 视图边界 | Provider 识别、边界、缓存及 VIEW_* | 已通过；`C.viewCycleAndDepthAreRecoverableWithSpecificWarnings` |
| L-52 | [复用已有预处理逻辑](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L615) | compat: ScriptPreprocessor；engine: 前端 | 可注入；保留位置和 rawSql | 已通过；`C.customPreprocessorIsInvokedForScriptAndExternalDefinition`；四参数编排构造器实际注入扩展点 |
| L-53 | [元数据缺失](../add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md#L626) | compat: 状态与诊断；engine: 未知质量 | 保留表依赖和可证明字段 | 已通过；`C.missingMetadataWarnsButKeepsKnownTableLineage` |

## 4. Join 需求逐场景表

| ID | 原场景或示例 | V2 实现机制 | 目标契约 | 实现 / 测试 / 结论 |
|---|---|---|---|---|
| J-01 | [Join 条件不计入正式血缘](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L9) | compat: 独立 Join / 独立导出 | 无正式边；独立记录补齐身份 | 已通过；`C.joinDefaultsAndJoinOnlyPublicationAreIndependentOfFormalLineage`；`V2ApiIntegrationTest.qualifiedJoinOnlyNeedsNoColumnMetadataIncludingUnrelatedStars` |
| J-02 | [Join 条件解析结果可单独持久化](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L20) | compat: 独立 Join / 独立导出 | 无正式边；独立记录补齐身份 | 已通过；`C.joinDefaultsAndJoinOnlyPublicationAreIndependentOfFormalLineage`；`C.standaloneJoinRecordsActuallyCarryReadableDirectAndPhysicalPresentation`：真实 exporter 填独立身份、可读类型、direct 别名、physical 全名及完整条件 |
| J-03 | [默认不解析 Join 条件](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L32) | compat: 正交能力；engine: DemandPlanner | 默认关；原生 JOIN_CONDITION 单选 | 已通过；`C.joinDefaultsAndJoinOnlyPublicationAreIndependentOfFormalLineage`；`V2ApiIntegrationTest.qualifiedJoinOnlyNeedsNoColumnMetadataIncludingUnrelatedStars` |
| J-04 | [开启 Join 条件解析](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L40) | compat: 正交能力；engine: DemandPlanner | 默认关；原生 JOIN_CONDITION 单选 | 已通过；`C.joinDefaultsAndJoinOnlyPublicationAreIndependentOfFormalLineage`；`V2ApiIntegrationTest.qualifiedJoinOnlyNeedsNoColumnMetadataIncludingUnrelatedStars` |
| J-05 | [只解析 Join 条件](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L48) | compat: 正交能力；engine: DemandPlanner | 默认关；原生 JOIN_CONDITION 单选 | 已通过；`C.joinDefaultsAndJoinOnlyPublicationAreIndependentOfFormalLineage`；`V2ApiIntegrationTest.qualifiedJoinOnlyNeedsNoColumnMetadataIncludingUnrelatedStars` |
| J-06 | [单条 Join 子句](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L79) | compat: Join 完整输出；engine: Scope | 全局序号、查询块、直接/物理引用；见 D-04 | 已通过；`C.fullOnFunctionsSingleSidedAndRepeatedFieldsStayInOneRecord` |
| J-07 | [连续 Join 子句](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L104) | compat: Join 完整输出；engine: Scope | 全局序号、查询块、直接/物理引用；见 D-04 | 已通过；`C.sequentialJoinsKeepInnerToOuterOrderAndOneBasedIndices` |
| J-08 | [多个 CTE 中存在不连续 Join](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L128) | compat: Join 完整输出；engine: Scope | 全局序号、查询块、直接/物理引用；见 D-04 | 已通过；`C.cteDerivedAndExistsJoinsHaveStableGlobalIndicesAndDeclaredPaths`；CTE→root→子块排序、共享子查询序号；原 JSON 逐字段断言见 J-12 |
| J-09 | [子查询中存在不连续 Join](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L160) | compat: Join 完整输出；engine: Scope | 全局序号、查询块、直接/物理引用；见 D-04 | 已通过；`C.cteDerivedAndExistsJoinsHaveStableGlobalIndicesAndDeclaredPaths`；CTE→root→子块排序、共享子查询序号；原 JSON 逐字段断言见 J-13 |
| J-10 | [Join 直接对象是前序临时表](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L186) | compat: Join 完整输出；engine: Scope | 全局序号、查询块、直接/物理引用；见 D-04 | 已通过；`C.temporaryJoinRetainsDirectBoundaryAndDoesNotReplayHistoricalJoins` |
| J-11 | [临时表字段无法唯一追溯时保留直接字段](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L216) | compat: Join 完整输出；engine: Scope | 全局序号、查询块、直接/物理引用；见 D-04 | 已通过；`C.unknownTemporaryFieldRetainsOnlyProvablePhysicalJoinSource`；候选集合不当成已证明来源 |
| J-12 | [CTE 中不连续 Join 的解析输出](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L246) | compat: Join 完整输出；engine: Scope | 全局序号、查询块、直接/物理引用；见 D-04 | 已通过；`OpenSpecJoinExamplesTest.cteJsonExampleMatchesEveryDeclaredFieldAcrossNativeAndLegacyViews`；原 JSON 每个身份、序号、路径、类型、完整 ON、direct / physical 列表直接对 native 记录比较；旧 DTO 差异另行验证 |
| J-13 | [子查询中不连续 Join 的解析输出](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L371) | compat: Join 完整输出；engine: Scope | 全局序号、查询块、直接/物理引用；见 D-04 | 已通过；`OpenSpecJoinExamplesTest.existsJsonExampleMatchesEveryDeclaredFieldAcrossNativeAndLegacyViews`；原 JSON 逐字段比较，关联子查询 WHERE 字段不混入 ON |
| J-14 | [保留完整 ON 条件表达式](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L467) | compat: Join 完整输出；engine: 源码引用 | 完整 ON、单侧条件、不混 WHERE 过滤 | 已通过；`C.fullOnFunctionsSingleSidedAndRepeatedFieldsStayInOneRecord`；直接 lexical 与旧解析后物理名称见 D-04 |
| J-15 | [函数表达式 Join 条件](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L484) | compat: Join 完整输出；engine: 源码引用 | 完整 ON、单侧条件、不混 WHERE 过滤 | 已通过；`C.fullOnFunctionsSingleSidedAndRepeatedFieldsStayInOneRecord`；直接 lexical 与旧解析后物理名称见 D-04 |
| J-16 | [ON 条件中只有单侧字段](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L507) | compat: Join 完整输出；engine: 源码引用 | 完整 ON、单侧条件、不混 WHERE 过滤 | 已通过；`C.fullOnFunctionsSingleSidedAndRepeatedFieldsStayInOneRecord`；直接 lexical 与旧解析后物理名称见 D-04 |
| J-17 | [重复字段只输出一次](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L531) | compat: Join 完整输出 | 完整名去重、首次出现顺序 | 已通过；`C.fullOnFunctionsSingleSidedAndRepeatedFieldsStayInOneRecord`；直接 lexical 与旧解析后物理名称见 D-04 |
| J-18 | [Join 类型枚举值](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L553) | compat: Join 编码映射 | 内部明确类型；旧编码见 D-03 | 已通过；`C.joinTypesNormalizeWithoutInventingCrossConditions`；Hive 可解析拼法已过；`C.reservedAntiUnknownAndCrossKindsRetainTheirNumericExportContract` 验收保留类型码；Hive 4.0.1 不支持 LEFT ANTI JOIN SQL 拼法，不静默改成 SEMI |
| J-19 | [带别名字段不依赖字段元数据](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L578) | engine: 条件字段绑定；compat: 元数据访问 | 限定字段不读 schema；歧义按需查 | 已通过；`B.joinOnlyDoesNotExpandUnrelatedProjectionOrLoadQualifiedPhysicalFields`；`V2ApiIntegrationTest.qualifiedJoinOnlyNeedsNoColumnMetadataIncludingUnrelatedStars` 检查 schema=0 |
| J-20 | [未限定字段可按需依赖字段元数据](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L586) | engine: 条件字段绑定；compat: 元数据访问 | 限定字段不读 schema；歧义按需查 | 已通过；`C.unqualifiedAmbiguousJoinUsesMetadataAndKeepsTheConditionText` |
| J-21 | [CTE 字段无法唯一追溯时保留直接字段](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L595) | engine: 条件字段绑定；compat: 元数据访问 | 限定字段不读 schema；歧义按需查 | 已通过；`C.ambiguousCteRetainsDirectReferenceWithoutInventingPhysicalField`；x.id 显示引用保留，仅 y 的可证明物理来源传播 |
| J-22 | [逗号连接的 Where 关联条件必须输出](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L631) | compat: 隐式 Join / 能力登记 | 隐式 WHERE 要支持；无 ON CROSS 可不输出 | 已通过；`C.commaWhereEmitsOnlyCrossSourceConjunctsAndPreservesFullOn`；逗号 code7/IMPLICIT 与无 ON CROSS 分开 |
| J-23 | [逗号连接中的普通过滤条件不单独输出](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L647) | compat: 隐式 Join / 能力登记 | 隐式 WHERE 要支持；无 ON CROSS 可不输出 | 已通过；`C.commaWhereEmitsOnlyCrossSourceConjunctsAndPreservesFullOn`；逗号 code7/IMPLICIT 与无 ON CROSS 分开 |
| J-24 | [第一版不要求拆分 Join 条件字段对](../add-hive-sql-lineage-analyzer/specs/hive-sql-join-condition/spec.md#L655) | compat: 隐式 Join / 能力登记 | 隐式 WHERE 要支持；无 ON CROSS 可不输出 | 已通过；`C.commaWhereEmitsOnlyCrossSourceConjunctsAndPreservesFullOn`；逗号 code7/IMPLICIT 与无 ON CROSS 分开 |

逐行缩写对应下列实际文件；表中方法名是稳定证据标识，不以测试类名概括所有场景：

| 缩写 | 文件 |
|---|---|
| C | [OpenSpecConformanceTest](../../../src/test/java/com/panfan/hivelineage/v2/conformance/OpenSpecConformanceTest.java) |
| B | [DefaultSemanticBinderTest](../../../src/test/java/com/panfan/hivelineage/v2/semantic/DefaultSemanticBinderTest.java) |
| A | [V2ArchitectureContractTest](../../../src/test/java/com/panfan/hivelineage/v2/V2ArchitectureContractTest.java) |
| M | [MetadataImplementationsTest](../../../src/test/java/com/panfan/hivelineage/v2/metadata/MetadataImplementationsTest.java) |
| F | [DefaultFrontendTest](../../../src/test/java/com/panfan/hivelineage/v2/frontend/DefaultFrontendTest.java) |
| O | [V2OutputContractTest](../../../src/test/java/com/panfan/hivelineage/v2/V2OutputContractTest.java) |
| J | [DefaultJoinConditionAnalysisTest](../../../src/test/java/com/panfan/hivelineage/v2/analysis/DefaultJoinConditionAnalysisTest.java) |
| P | [LegacyProjectionExecutionTest](../../../src/test/java/com/panfan/hivelineage/v2/compat/LegacyProjectionExecutionTest.java) |

原 J-12 / J-13 的 SQL 与 JSON 原文保存在[独立 fixture](../../../src/test/resources/v2/openspec/README.md)，[OpenSpecJoinExamplesTest](../../../src/test/java/com/panfan/hivelineage/v2/conformance/OpenSpecJoinExamplesTest.java) 直接逐字段比较 native 记录。示例 rawSql 的省略号保留，实际 rawSql 另与完整原 SQL 比较；前置忽略的 USE 语句使零基 statementIndex 为示例声明的 1，不改预期身份。native 的 directObjects 对物理表保存完整对象名、对 CTE / 派生保存逻辑名；directColumns 保留别名，旧 DTO 的解析后字段名称另有明确期望。

## 5. OpenSpec 之外的 V1 回归与产品能力

| 范围 | 现有证据 | V2 验收 |
|---|---|---|
| 默认库、显式库、USE 变量和跨请求隔离 | HiveSessionDatabaseResolutionTest；`C.defaultDatabaseNormalizesOnPhysicalResolutionAndQualifiedObjectsBypassInvalidDefaults`；`B.structuredMultiPartDefaultDatabaseCannotBecomeAPhysicalNamespace` | 所有物理表按当前语句上下文解析，无效 USE 不提交；默认库去完整反引号、小写并验证唯一标识，含点、多部分、内部空白或畸形引用不会生成物理命名空间；完整表名绕过无效默认库，纯查询默认不强制数据库 |
| qualified owner、反引号及关键字别名 | HiveQualifiedJoinColumnTest、HiveQualifiedProjectionHardeningTest | 不猜歧义 owner，不从复杂 token 伪造字段 |
| 自连接、星号、目标位置与集合宽度 | HivePositionalStarInsertLineageTest、HivePositionalStarScopeTest、HiveQualifiedStarScopeTest | 输出位置和关系实例独立，未知宽度和已知不匹配分开 |
| UNION / INTERSECT / EXCEPT 分支及未知传播 | HiveQualifiedProjectionHardeningTest、HiveScopedCteProjectionWarningTest | 兄弟分支别名隔离，完整 V2 保留已知/未知，兼容原子字段投影按契约 |
| 标量子查询和 COUNT 行依赖 | HiveScalarSubqueryLineageTest、HiveNestedSelectPseudoColumnRegressionTest | 保留对象依赖、正确内层字段，无伪默认字段 |
| 窗口、CASE、DISTINCT、EXPLODE、过滤 | HiveColumnLineageTest、HiveCaseJoinFilterLineageTest、HiveAdvancedColumnLineageTest、HiveInsertIntoDistinctLineageTest | 角色、函数名、表达式及旧字段输出有定向断言 |
| Join 嵌套查询块与重复文本节点 | HiveJoinLineageTest | 查询块、全局序号、直接/物理字段及顺序稳定 |
| 临时遮蔽、视图边界、默认库、展开失败 | HiveViewAndTemporaryLineageTest、HiveScopedViewOwnerResolutionTest | Session 优先、正确定义上下文和诊断，缓存不跨 Session 错绑 |
| 残留变量与字符串字面量 | HiveScriptProcessingTest、历史问题 SQL 测试 | 同策略结果可复现，日期/字符串不当字段，策略差异明示 |
| 元数据缓存、空值、TTL 与 JDBC | HiveMetadataLineageTest、JdbcMetadataColumnV2ProviderIntegrationTest | 复用 Provider 及 fixture；单元测试无需真实 MySQL，集成测试显式启用 |
| CLI、退出码、Markdown 表字段汇总 | cli/LineageAnalyzeCliTest、LineageMarkdownRendererTest | 原 CLI 不变，新增 V2 对应报告；兼容失败退出码和人工核验所需信息 |
| 生成 SQL 及现有快照 | GeneratedSqlTestSetTest、GeneratedSqlLineageSnapshot | 动态全部发现、相同输入对比、case_11 预期失败及恢复、旧快照保留 |
| 生产样例和补充问题 SQL | samplingHql01 至 04、HiveReportedSql*Test | 保存原输入并关联 V2，结果差异有原因与标准答案 |
| Java 工程约束 | JavaImportAndBraceStyleTest、JavaRegexConstantTest、JavaSourceSizeTest | Java 8、现有样式、正则常量、单文件行数限制 |

以上保留已有测试来源索引；本轮方法级验收见上方 77 行追踪表，实际全仓与旧断言执行结果见 [validation.md](validation.md)。实时 MySQL 的显式跳过项不能计作已连接验证。

## 6. generated 集复用契约

- 路径：`src/test/resources/hql/generated/**/*.sql`，当前 18 个文件，新增文件自动进入。
- 复用源：`GeneratedSqlTestSetTest.generatedMetadataProvider()`，通过新增同包桥访问，不改旧测试、不复制元数据。
- 基线配置：TABLE_AND_COLUMN、expandViewLineage=true、includeTemporaryLineage=true、fallbackVariableValue=fallback_value；其他默认值保持原配置。
- 业务身份、数据库、变量和元数据一致；引擎版本另记；每文件 Session 独立。
- `lineage_case_11.sql`：既有配置预期一条失败、后续成功，比较状态、位置、目标及返回记录。
- 其他用例先比较原结果，再按经确认语义验收；不能以“零失败”证明全部血缘正确。
- 三模式全量矩阵见 `GeneratedV2EngineRegressionTest`；Join-only、临时/忽略/视图组合与元数据调用见专项测试。原配置逐例完整 sink 来源比对见 `GeneratedV2OriginalConfigurationTest`，完整旧 DTO 差异见 validation.md。
- V2 报告在 `target/lineage-v2/`，原快照不覆盖；标准答案人工确认后独立维护。

## 7. 新模型专项验收

| 不变量 | 验证内容 |
|---|---|
| 按需分析 | 未请求的信息不求值；TABLE_ONLY / Join-only 不被完整投影绑定阻断 |
| 分层模型 | LexedUnit、Syntax IR、Bound IR、LineageFacts 分开；V1 字符串/Map 不作基类或内核输入 |
| 禁止依赖 | Lexer/Parser/Normalizer 不查元数据；Binder 不生成血缘；血缘不读 SQL、Token、AST 或 Provider |
| 绑定状态 | SlotRef、QualifiedFieldHandle、Unknown、NotRequested 明确区分；没有解析/Provider 惰性回调 |
| IR 验证 | 节点身份、引用可达、关联作用域、输出映射及未知宽度可独立校验 |
| 独立推导 | 手工 IR 在无 SQL/Parser/元数据条件下可分析，改变源码附件不改变依赖 |
| 输入事实 | null、异常和权威不存在不同；未请求 schema 与未知 schema 不同 |
| 同源缓存 | 旧 cache 只在匹配数据源和读取上下文内复用，不串用多 Provider；V2 自有键包含来源身份 |
| 任务隔离 | 变量、临时表、目录覆盖和数据版本不通过缓存跨任务传播 |
| 效果提交 | 失败语句不提交；旧入口不默认应用新 DDL/VALUES 版本效果 |
| 证据质量 | 常量空依赖、已知依赖加未知、条件和行集影响分别表达 |
| 视图模板 | 共享模板但按任务实例化，键包含相关定义和绑定上下文 |
| 格式与身份 | 算法版本不改 taskVersion，稳定序号，独立 Join 导出补齐父身份 |
| 可复现 | 相同输入/规则/快照结果稳定，比较不抹除有语义顺序 |
| 资源边界 | Parser 不共享可变状态，循环/超限明确返回诊断 |
| 扩展演练 | 新增局部生成/写入/来源规则不改脚本主循环和生命周期；必要模型升级显式版本化 |

## 8. 后续能力与仍需核验的边界

- `B.loadRequiresExplicitCapabilityAndDoesNotInventExternalFileColumns`：EXTERNAL_DATA_LINEAGE 显式启用后建模 LOCAL / DISTRIBUTED 路径存储来源、APPEND / OVERWRITE、静态分区和未知文件字段映射；V2 原生与兼容入口默认返回显式 UNSUPPORTED，不读取文件。旧 V1 忽略 LOAD DATA 是既有行为差异，V2 不据此静默忽略尚未支持的数据语句。
- `B.mergeKeepsEveryMatchedAndUnmatchedActionAsASeparateContribution`：同一 MERGE 的 UPDATE、DELETE、INSERT 分别保留条件、写入身份和目标字段；多目标 INSERT 见 `B.updateDeleteMergeAndMultipleInsertProduceIndividualContributions`。
- CATALOG_SIMULATION / DATASET_VERSION_SIMULATION 才允许目录与数据版本推演；IF NOT EXISTS 的存在性不明时 Create / Definition 效果使用 CONDITIONAL + OBJECT_ABSENT。Session 真正提交的证据见 SessionLifecycleTest。
- `B.requestedDatasetVersionsDeclarePhysicalScriptEntryAndLogicalUnknowns` 验收数据版本按 Session 隔离：同一物理对象在两个独立 Session 中的静态入口版本与后续写版本不同；已有 Session 版本复用。CTE / 派生关系及非物化 VIEW 不声明已知物化版本，未请求时保持 NOT_REQUESTED。
- `B.externalDefinitionRequestsStayOutsideTheBinderAndFailuresAreNotRetried` 同时验收内联文本与无文本的外部定义引用：Binder 统一返回 NEEDS_DEFINITION，由编排准备；引用缺少内联 SQL 不会提前判为定义缺失，已失败准备不再重试。
- LEFT ANTI 是 IR / 导出保留类型，原生 Hive 4.0.1 不接受 `LEFT ANTI JOIN` SQL 拼法。未知函数不执行；外部系统、运行环境和文件内容不会被猜测。
- `LegacyTargetMappingRegressionTest` 使用真实既有 SQL 验证普通目标前 N 映射、缺目标结构的命名推断、未知星号的目标声明、静态 CTAS / VIEW 条件创建、未知结构下的生成字段与未知结构路径；每项独立比对 native 严格与命名兼容策略。
- 已通过的结构与依赖断言不替代实时 Metastore/JDBC 集成和生成样例人工标准答案；已执行的历史样例差异分类见 [validation.md](validation.md)。

## 9. 发布验收登记

每个追踪行及历史差异使用：已通过、命名策略兼容、确认改进、回归、未支持、待核验。覆盖和准确性分别统计，未知、未支持及失败样本保留在分母中。

本轮实现保留 V1 默认入口。以上实际通过的场景与已明确策略分歧分别登记；全仓与旧断言执行结果在 validation.md 统一记录。实时环境和人工标准答案另需各自证据，不能由表内单元验证替代。
