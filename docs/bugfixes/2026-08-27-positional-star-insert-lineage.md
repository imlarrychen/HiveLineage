# 单来源 SELECT * 按位置生成字段血缘

- 日期：2026-08-27
- 状态：已修复
- 关联提交：本记录所在修复提交

## 问题现象

单来源 `INSERT ... SELECT *` 使用源、目标字段的同名交集生成血缘。当相同位置字段名称不同时，对应血缘会被静默丢失。

## 最小复现

元数据：

```text
whc.whc_hive_01(id, name, age)
whc.whc_hive_02(id, name, sex)
```

SQL：

```sql
insert into whc.whc_hive_01
select * from whc.whc_hive_02;
```

## 预期结果

按字段位置生成三条 `DIRECT` 血缘：

```text
whc.whc_hive_02.id   -> whc.whc_hive_01.id
whc.whc_hive_02.name -> whc.whc_hive_01.name
whc.whc_hive_02.sex  -> whc.whc_hive_01.age
```

源、目标有效字段数量不一致时返回 `COLUMN_COUNT_MISMATCH`，且不生成部分字段血缘。

## 修复前实际结果

只生成 `id -> id` 和 `name -> name`，遗漏 `sex -> age`。源表或目标表多出字段时，解析器仍返回成功并输出同名字段的部分血缘。

## 根因

`HiveTargetColumnResolver.expandSingleInputStar` 同时承担来源星号展开和目标字段筛选，并调用 `intersectionInTargetOrder` 取同名交集。`HiveScriptLineageAnalyzer` 随后又把这组交集字段同时当成来源表达式和目标字段，因此无法表达字段名不同的位置映射，也无法检测原始列数不一致。

## 修复方案

- 仅当语句为 INSERT、无显式目标字段、顶层 `FROM` 只有一个直接物理表关系且来源元数据可用时，启用位置映射路径；自连接、普通连接、逗号连接和派生表均不进入该路径。
- 位置映射路径按来源元数据顺序展开为带物理表名的来源表达式。
- 新增星号目标字段专用解析入口，独立读取完整目标元数据字段顺序。
- 分别得到来源表达式和目标字段后按索引生成血缘。
- 复用现有列数校验；数量不一致时整条语句返回 `COLUMN_COUNT_MISMATCH`。
- 派生表、CTE、多来源裸星号、来源元数据缺失、显式目标字段、CTAS 和 View 保留原有星号处理路径。

## 影响范围

- 单来源 `INSERT ... SELECT *` 且未显式指定目标字段列表的字段血缘。
- 不改变限定星号、多来源星号、集合运算、默认库、`DISTINCT` 和子查询解析逻辑。

## 回归案例

- 测试类：`HivePositionalStarInsertLineageTest`
- 测试方法：`singleSourceStarMapsDifferentColumnNamesByPosition`
- 测试方法：`singleSourceStarFailsWhenTargetHasExtraColumns`
- 测试类：`HiveColumnLineageTest`
- 测试方法：`selectStarFailsWhenSourceHasExtraColumns`
- 测试类：`HivePositionalStarScopeTest`
- 测试方法：`derivedTableStarKeepsExistingProjectionBehavior`
- 测试方法：`multiSourceBareStarKeepsExistingUnresolvedBehavior`
- 测试方法：`selfJoinBareStarDoesNotUseSingleSourcePositionalMapping`
- 测试方法：`missingSourceMetadataDoesNotBecomeColumnCountMismatch`
- 测试方法：`explicitTargetColumnsKeepExistingStarBehavior`
- 既有成功案例：`selectStarExpandsColumnsFromMetadata`
- RED 证据：3 个问题测试全部失败；异名位置实际只有 2 条血缘，两种列数不一致场景的状态码均错误为成功 `0`。
- 范围 RED 证据：派生表、多来源、来源元数据缺失、显式目标字段和同表自连接用例均曾被错误改成 `COLUMN_COUNT_MISMATCH`。
- GREEN 证据：8 个核心行为与范围测试全部通过；相关星号回归共 40 个测试全部通过。

## 完整验证

- 命令：`/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn test`
- 结果：135 tests，0 failures，0 errors，5 skipped，`BUILD SUCCESS`。
- 条件跳过项：5 个 `JdbcMetadataColumnV2ProviderIntegrationTest` 需要外部 MySQL JDBC 参数。
- 生成测试：`GeneratedSqlTestSetTest` 2 tests，0 failures，0 errors；`lineage_case_04` 元数据调整为源、目标列数一致的成功路径，并刷新血缘快照。
