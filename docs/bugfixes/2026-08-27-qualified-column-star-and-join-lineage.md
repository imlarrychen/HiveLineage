# 限定字段、限定星号与 JOIN 血缘修复

- 日期：2026-08-27
- 状态：已修复并合并
- 最终提交：`00112e7`

## 问题现象

字段血缘解析不能完整处理 `whc.b.*`、`whc.a.field1` 和 `whc.a.id = whc.b.id` 等数据库限定名称；部分未知或歧义 owner 会被错误绑定为其他物理表。相同问题还会出现在集合运算、CTE 和 View 的组合路径中。

## 最小复现

```sql
INSERT INTO whc.c
SELECT whc.a.field1, whc.b.*
FROM whc.a LEFT JOIN whc.b ON whc.a.id = whc.b.id;
```

元数据：

- `whc.a(id, field1)`
- `whc.b(id, name, value)`
- `whc.c(field1, id, name, value)`

## 预期结果

- `whc.a.field1 -> whc.c.field1`
- `whc.b.id -> whc.c.id`
- `whc.b.name -> whc.c.name`
- `whc.b.value -> whc.c.value`
- JOIN 表和字段完整保留为 `whc.a`、`whc.b`、`whc.a.id`、`whc.b.id`
- 未知或歧义 owner 产生告警且不生成猜测来源

## 修复前实际结果

- 限定星号没有按来源表元数据展开，目标字段数量错误。
- 三段限定字段可能被降级为派生字段或只保留首段 owner。
- JOIN 表可能被错误解析为 `whc`，JOIN 字段可能被错误解析为 `whc.id`。
- UNION、INTERSECT、EXCEPT、CTE 或 View 的部分路径可能跨作用域绑定 owner，或在 unresolved 时保留另一分支的部分来源。

## 根因

- 字段引用与星号展开缺少统一的限定名称模型。
- owner 解析的 exact physical、explicit alias、unique simple-name 优先级未在所有路径统一使用。
- 集合运算分支、CTE、View 和 JOIN 各自维护投影逻辑，导致作用域与 unresolved 状态传播不一致。
- JOIN 的三段字段处理按第一个点而不是最后一个点拆分表名与字段名。

## 修复方案

- 引入 `QualifiedColumnReference` 与 `QualifiedOwnerResolver`，统一限定字段和 owner 解析。
- 按查询块及集合分支隔离 alias/input 作用域，并在分支列数不一致时返回 `COLUMN_COUNT_MISMATCH`。
- 使用 `ColumnLineageProjection.unresolved` 在 CTE、View、集合和 JOIN 间传播不确定状态；任一分支 unresolved 时对应位置不输出部分来源。
- JOIN 字段统一按最后一个点拆分，并复用共享投影解析结果。

## 影响范围

- 限定字段和限定星号投影
- UNION、UNION ALL、INTERSECT、EXCEPT
- 嵌套 CTE 与 View 展开
- 显式 JOIN 和隐式 JOIN 条件
- 未知或歧义 owner 的告警与 no-guess 行为

## 回归案例

- `HiveQualifiedStarDdlScenarioTest`：验证原始 DDL/DML 场景的 4 条 DIRECT 血缘和 JOIN。
- `HiveQualifiedProjectionHardeningTest`：验证集合分支作用域、未知/歧义 owner 及 dotted-name 边界。
- `HiveQualifiedStarScopeTest`：验证嵌套查询块 alias 不泄漏。
- `HiveScopedCteProjectionWarningTest`：验证 CTE unresolved 状态和集合原子传播。
- `HiveScopedViewOwnerResolutionTest`：验证 View owner 歧义不猜测。
- `HiveQualifiedJoinColumnTest`：验证二段/三段 JOIN 字段及未知 owner。
- `HiveUnresolvedProjectionJoinTest`：验证 unresolved View/CTE 在复杂表达式和 JOIN 中不回退为伪物理来源。

RED 阶段分别观察到星号未展开、来源被跨分支绑定、缺失告警、JOIN owner 截断和 unresolved 回退等失败；修复后上述测试全部通过。

## 完整验证

- 命令：`mvn test`
- 合并后结果：128 tests，0 failures，0 errors，5 skipped
- 条件跳过项：5 个 `JdbcMetadataColumnV2ProviderIntegrationTest` 需要外部 MySQL JDBC 参数；本问题的 DDL 元数据通过标准 `HiveMetadataProvider` 接口以内存实现初始化。
- Java 源文件规模检查：通过，不存在超过 1000 行的 Java 文件。
- `git diff --check`：通过。
