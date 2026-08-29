# Session 数据库与默认库解析缺陷

## 问题现象

过去分析器未统一使用请求中的 `defaultDatabase`，也没有将脚本中的 `USE` 当作同一
Session 内的状态转换。物理裸表可能被输出为无库名对象，表血缘、字段血缘、Join
和元数据查询之间可能使用不同的数据库归属。

其他关联风险包括：

- 多次 `USE` 不能只影响后续语句。
- 无默认库时对裸物理表进行猜测解析。
- 视图定义中的裸表误用调用语句的 Session 库。
- 跨 `USE` 引用 Session 临时表时，被误当成新库下的永久表。
- 嵌套 Join 的别名、AST 路径和最终物理对象可能串扰或重复。

## 用户场景与期望结果

```text
defaultDatabase = db1
```

```sql
select * from t1 left join db2.t2 on t1.id = db2.t2.id;
use db3;
insert into t4 select * from db1.t1;
```

确定结果：

- 第一句的 `t1` 是 `db1.t1`，显式 `db2.t2` 保持不变。
- Join 表为 `db1.t1`、`db2.t2`，Join 字段为 `db1.t1.id`、`db2.t2.id`。
- `USE db3` 仅更新后续语句的当前库，自身不生成正式血缘。
- 第三句的输出是 `db3.t4`，显式输入仍是 `db1.t1`。
- 元数据 Provider 收到的 database/table 与对外血缘的完整物理标识一致。

## 根因链路

1. 请求的默认库没有成为脚本分析的 Session 初始状态。
2. `USE` 只被当作忽略语句，未实现有序状态转换。
3. parser、表/字段生成、Join 解析和元数据查询缺少共享的物理名规范化边界。
4. 逻辑对象（CTE、子查询、临时表）和物理表没有在补库前明确分层。
5. 视图 SQL 被单独解析时未按视图元数据所属库绑定。
6. Join 嵌套查询曾存在跨作用域别名复用和同一 AST 查询块重复遍历。

## 数据库优先级与 Session 状态转换

物理表归属优先级：

1. SQL 显式 `database.table`。
2. 当前 Session 库：请求初始为 `defaultDatabase`，成功 `USE` 后为新库。
3. 两者都不存在时，裸物理表失败，不猜测。

补库前优先识别逻辑名：CTE、子查询投影和 Session 临时表保留逻辑标识；展开后的
真实来源必须使用完整 `database.table` 或 `database.table.column`。视图定义使用视图所属库，
不继承调用者 Session 库。

## 实现方案

- `ScriptAnalysisState` 保存请求局部的当前库、临时表来源和临时表字段投影。
- `HiveScriptPreprocessor` 在变量替换后解析 `USE`，支持反引号和 Session 变量。
- `HivePhysicalNameResolver` 统一校验库/表标识，生成完整物理名和严格元数据
  `TableIdentifier`。
- `HiveStatementDatabaseBinder` 在语句级同时绑定输入、输出、别名、字段射射和 Join direct 对象。
- 表血缘、字段血缘、Join 和元数据查询共用同一绑定结果。
- Join 别名按 query-block path 隔离，AST 查询块按节点身份每次 extract 仅遍历一次。
- 视图定义通过 `parseBoundView` 按视图所属库绑定；临时表在永久表补库前匹配。

## 失败与恢复语义

- 无当前库且正式血缘语句包含裸物理表：该语句返回 `DATABASE_NOT_RESOLVED`。
- 纯 `SELECT` 且关闭 Join 解析：继续按 `PURE_QUERY` 忽略，不额外要求数据库。
- 开启 Join 解析的物理裸表 SELECT/Join：没有库时明确失败。
- 无效 `USE` 或未解析变量：该语句返回 `INVALID_USE_DATABASE`，当前库不变，后续语句继续。
- 合法 `USE`：更新当前库；开启 `includeIgnoredStatements` 时可返回忽略记录。
- 请求之间不共享 Session 库、变量或临时表状态。

## 测试索引

### HivePhysicalNameResolverTest

`bareTableUsesStatementDatabase`、`explicitDatabaseWins`、`identifiersAreNormalized`、
`bareTableWithoutDatabaseFails`、`qualifiedTableWithTrailingDotFails`、`bareTableWithTrailingDotFails`、
`databaseWithTrailingDotFails`、`databaseWithMultiplePartsFails`、`databaseWithInternalWhitespaceFails`、
`databaseWithUnpairedBacktickFails`、`databaseWithOnlyTrailingBacktickFails`、`tableWithOnlyTrailingBacktickFails`、
`databaseWithInternalBacktickFails`、`tableWithInternalBacktickFails`、`databaseWithNonBreakingSpaceFails`、
`tableWithNonBreakingSpaceFails`、`onlyNonBreakingSpaceDatabaseMeansNoDatabase`、`emptyDatabaseVariantsFailDatabaseResolution`、
`nullTableNameFailsDatabaseResolution`、`emptyTableNameFailsDatabaseResolution`、
`qualifiedTableCreatesStrictMetadataIdentifier`、`bareTableCannotCreateMetadataIdentifier`、
`malformedTableCannotCreateMetadataIdentifier`。

### HiveSessionDatabaseResolutionTest

`defaultDatabaseQualifiesBareTablesAndColumns`、`explicitDatabaseWinsOverSessionDatabase`、
`fullyQualifiedTablesDoNotRequireDefaultDatabase`、`backtickAliasQualifiesSourceColumn`、
`quotedKeywordAliasQualifiesSourceColumn`、`multipleUseStatementsAffectOnlyFollowingStatements`、
`useSupportsVariableBackticksAndIgnoredRecord`、`statementVariableCanResolveToUseDatabase`、
`bareTableWithoutDatabaseFailsButLaterUseRecovers`、`invalidUseFailsAndKeepsPreviousDatabase`、
`unresolvedUseVariableFailsAndKeepsPreviousDatabase`、`analyzerDoesNotShareUseDatabaseAcrossRequests`、
`pureSelectJoinWithoutDatabaseFailsWhenJoinParsingIsOn`、`fallbackJoinWithoutDatabaseFailsWhenTablesAreBare`、
`fallbackJoinWithQualifiedTablesDoesNotRequireDatabase`、`fallbackJoinWithQuotedQualifiedTablesDoesNotRequireDatabase`、
`fallbackJoinDistinguishesScopedAliasFromSameNamedPhysicalTable`、`fallbackJoinDoesNotTreatExtractFromAsPhysicalSource`、
`selectJoinAcceptsCteColumnAliasList`、`fallbackJoinAcceptsCteColumnAliasListWithQualifiedPhysicalSources`、
`fallbackJoinIgnoresSourceKeywordsInsideStringLiterals`、`fallbackJoinIgnoresSourceKeywordsInsideComments`、
`fallbackJoinValidatesBarePhysicalTableInsideCte`、`fallbackJoinDoesNotTreatCteAsPhysicalTable`、
`fallbackJoinValidatesBarePhysicalTableInsideSubquery`、`fallbackJoinDoesNotTreatTemporaryTableAsPhysicalTable`、
`fallbackJoinValidatesUnreferencedRightTable`、`fallbackJoinValidatesUnreferencedLeftTable`、
`fallbackJoinValidatesAllTablesForConstantCondition`、`fallbackJoinValidatesBareTableInCommaSourceList`、
`fallbackJoinPreservesBareAndQualifiedOccurrencesWithSameSimpleName`、`fallbackJoinAllowsBlockCommentBeforeBareFromSource`、
`fallbackJoinAllowsBlockCommentBeforeBareJoinSource`、`fallbackJoinAllowsLineCommentBeforeBareSource`、
`fallbackJoinWithQualifiedCommaSourcesDoesNotRequireDatabase`、`pureSelectWithoutDatabaseRemainsIgnoredWhenJoinParsingIsOff`、
`joinAndMetadataFollowDefaultAndUseDatabaseInOneSession`。

### HiveJoinLineageTest

`parseJoinConditionsDefaultOffKeepsPureQueryIgnored`、`pureQueryJoinConditionIsReturnedWithoutFormalLineage`、
`joinConditionKeepsExpressionAndDirectColumns`、`unqualifiedJoinColumnsUseMetadataToResolveOwners`、
`unqualifiedImplicitWhereJoinColumnsUseMetadataToResolveOwners`、`consecutiveJoinConditionsAreReturnedInOneStatement`、
`implicitWhereJoinIsReturnedAsJoinCondition`、`implicitWhereJoinDoesNotIncludeSingleTableFilters`、
`existsSubqueryJoinConditionIsReturnedWithQueryBlockPath`、`unresolvedCteJoinColumnDoesNotBecomePhysicalJoinColumn`、
`cteJoinConditionSeparatesDirectAndPhysicalObjects`、`temporaryTableJoinConditionExpandsToPhysicalColumns`、
`sessionDatabaseBindsPhysicalSourcesBehindCteJoinAlias`、`aliasesAreResolvedWithinTheirOwnCteAndRootQueryBlocks`、
`qualifiedPhysicalTableDoesNotCollideWithSameNamedCte`、`uniqueQualifiedSourceStillSupportsItsSimpleName`、
`ambiguousSimpleNameAcrossQualifiedSourcesIsNotArbitrarilyBound`、`explicitAliasWinsOverCollidingQualifiedSimpleName`、
`repeatedAliasesInSiblingExistsSubqueriesStayInTheirAstScopes`、`nestedExistsSubqueryUsesNestedAstScopePath`、
`inlineSubqueryAndExistsEachProduceOneScopedJoin`、`identicalSubqueryTextWithDistinctAstNodesIsPreserved`。

### HiveViewAndTemporaryLineageTest

`temporaryTableLineageIsExpandedToFinalTargetByDefault`、`includeTemporaryLineageReturnsTemporaryCreateStatement`、
`viewInputIsExpandedAndViewMetadataIsCached`、`createViewAsSelectProducesFormalLineage`、
`viewColumnLineageExpandsToBaseColumns`、`missingViewDefinitionEmitsWarningAndKeepsViewSource`、
`viewExpansionDepthExceededEmitsWarningAndKeepsBoundaryView`、`alterViewAsSelectProducesFormalLineage`、
`temporaryTableColumnLineageIsExpandedToFinalTarget`、`viewColumnLineageKeepsDerivedExpressionWhenExpanded`、
`temporaryTableShadowsSameNameMetastoreView`、`viewExpansionFailureEmitsWarningAndKeepsViewBoundary`、
`unqualifiedTableInViewUsesViewDatabase`、`temporaryTableIsExpandedBeforePermanentTableQualification`。

其他相关门禁包括 `HiveColumnLineageTest`、`HiveQualifiedJoinColumnTest`、
`HiveScopedCteProjectionWarningTest`、`HiveScopedViewOwnerResolutionTest`、
`HiveNestedSelectPseudoColumnRegressionTest`、`HiveFallbackJoinSourceResolverTest` 和 `HiveMetadataLineageTest`。

## 验证结果

- 问题 1 定向命令：
  `mvn -q -Dtest=HivePhysicalNameResolverTest,HiveSessionDatabaseResolutionTest,HiveColumnLineageTest,HiveJoinLineageTest,HiveQualifiedJoinColumnTest,HiveViewAndTemporaryLineageTest,HiveScopedCteProjectionWarningTest,HiveScopedViewOwnerResolutionTest,HiveNestedSelectPseudoColumnRegressionTest test`
  。结果：141 tests，0 failures，0 errors，0 skipped。
- 提交前完整命令：`mvn -q clean test`。结果：274 tests，0 failures，0 errors，
  5 skipped。跳过项为需要外部 JDBC 环境的已知集成测试。

## 范围边界

- 本修复不改变显式 `database.table` 的归属。
- 不将 CTE、子查询、临时表简名或未解析别名伪造为物理表。
- direct Join 层可保留逻辑证据和告警，最终物理 Join 层只输出完整可解析标识。
- 不实现跨请求 Session 持久化；每个请求是独立 Session。
- 不替代 Hive 运行时权限、catalog 存在性或外部元数据一致性校验。

## 关联专项记录

- `docs/bugfixes/2026-08-29-join-ast-duplicate-traversal.md`
- `docs/bugfixes/2026-08-29-view-database-scope.md`
- `docs/bugfixes/2026-08-29-cross-use-temporary-table-regression.md`
