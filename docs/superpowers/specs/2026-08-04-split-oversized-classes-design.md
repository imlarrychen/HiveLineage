# 超长类职责拆分设计

## 背景与目标

项目当前有三个 Java 文件超过 1000 行：

- `HiveScriptLineageAnalyzer.java`：3354 行
- `HiveParserAstAdapter.java`：1217 行
- `HiveLineageAnalyzerTest.java`：2551 行

本次重构的目标是让 `src/main/java` 和 `src/test/java` 下每个 Java 源文件均不超过 1000 行，同时保持公开 API、SQL 解析行为、返回模型和现有测试语义不变。拆分应形成稳定的职责边界，而不是仅按行号搬移代码。

## 约束

- 保持 Java 8 兼容。
- 不修改 `HiveLineageAnalyzer` 的公开接口或请求、结果模型。
- 不改变解析选项的默认值、warning 语义、状态码和输出顺序。
- 新协作类优先使用包级可见性，避免扩大公共 API。
- 不引入新的运行时依赖。
- 不修改未跟踪的 `release/` 内容。
- 每次提取只迁移一个内聚职责，并由现有行为测试保护。

## 方案

### 1. `HiveScriptLineageAnalyzer` 保留流程编排

`HiveScriptLineageAnalyzer` 继续实现 `HiveLineageAnalyzer`，保留构造、脚本级入口、逐语句调度、成功/失败/忽略结果组装和各协作组件的调用。它不再直接实现所有 SQL 文本处理、Join 分析和字段投影算法。

按现有方法聚类，提取以下包级协作类；实际迁移时可以在不突破 1000 行的前提下合并过小的无状态组件：

- `HiveScriptPreprocessor`
  - 负责语句切分、注释处理、`set` 会话变量、变量替换和残留变量兜底。
  - 输入原始脚本与变量，输出规范化的语句序列。
- `HiveJoinConditionAnalyzer`
  - 负责显式 Join、隐式 Where Join、CTE/子查询块路径、直接字段与物理字段解析。
  - 持有 Join 解析所需的内部值对象，如 `JoinClause`、`JoinTypeInfo`、`CteBlock` 和计数器。
- `HiveProjectionLineageResolver`
  - 负责 select item、星号、集合操作、目标字段对齐、表达式到来源字段的映射，以及投影表解析。
  - 持有投影相关内部值对象，如 `ColumnLineageProjection`、`SetOperationParts` 和 `DirectColumn`。
- `HiveLineageExpansionService`
  - 负责临时表注册与展开、视图输入及字段展开、边界 warning。
  - 通过明确参数接收请求、缓存和当前脚本的临时表上下文，不保存跨请求可变状态。
- `HiveSqlTextUtils`
  - 仅承载无状态且可复用的 SQL 顶层关键字、括号、标识符和 select item 文本操作。
  - 若某个工具只服务于一个组件，则留在该组件内，避免形成新的杂物类。

依赖方向为：`HiveScriptLineageAnalyzer` 调用上述组件；组件可以调用 `HiveAstParserAdapter`、元数据接口和小型文本工具，但组件之间不循环依赖。每次 `analyzeScript` 所需的临时表、会话变量和 warning 集合仍为请求局部状态，保证分析器可以安全复用。

### 2. `HiveParserAstAdapter` 拆分 AST 提取职责

`HiveParserAstAdapter` 保留 Hive `ParseDriver` 调用、主 AST 定位和 `ParsedHiveStatement` 组装。提取两个包级组件：

- `HiveAstProjectionExtractor`
  - 负责 select 表达式、别名、集合操作分支、子查询投影、字段来源和静态分区值提取。
- `HiveAstJoinExtractor`
  - 负责显式/隐式 Join AST 遍历、Join 类型、查询块路径、直接字段和直接对象提取。

通用 AST 查找继续复用现有 `HiveAstWalker`，表达式渲染继续复用 `HiveAstExpressionExtractor`。新组件通过构造参数接收这两个依赖，不重复实现树遍历基础设施。

### 3. 测试类按行为主题拆分

把 `HiveLineageAnalyzerTest` 中的测试按行为主题迁移到多个测试类：

- `HiveJoinLineageTest`：显式 Join、隐式 Join、CTE/子查询 Join、临时表 Join。
- `HiveColumnLineageTest`：普通表达式、常量、星号、集合操作、窗口函数、分区字段。
- `HiveViewAndTemporaryLineageTest`：视图展开、深度限制、临时表展开和边界 warning。
- `HiveScriptProcessingTest`：语句切分、变量、忽略语句、失败隔离和解析模式。
- `HiveParserAstAdapterTest`：AST adapter 和 fallback adapter 的直接行为。
- `HiveMetadataLineageTest`：元数据缓存、缺失元数据、字段归属和 JDBC 安全校验。

共享的请求构造、断言和测试 metadata provider 移到一个小型包级测试支持类。测试方法只迁移，不改变断言内容；若迁移暴露重复搭建代码，可在测试保持绿色后提取公共 fixture。

## 自动约束

新增结构测试，递归扫描：

- `src/main/java`
- `src/test/java`

对每个 `.java` 文件按物理行数统计并断言不超过 1000 行。当前项目采用一文件一个顶层类型，因此文件限制能够稳定约束类规模，同时也会阻止通过堆积多个包级类规避限制。错误信息应列出超限文件和实际行数。

## 实施顺序

1. 新增源码行数结构测试，并确认它因当前三个超限文件而失败。
2. 先按主题拆分 `HiveLineageAnalyzerTest`，保持测试内容与覆盖面不变。
3. 提取 `HiveParserAstAdapter` 的 AST Join 和投影职责，每次提取后运行 adapter 及相关血缘测试。
4. 依次提取 `HiveScriptLineageAnalyzer` 的脚本预处理、Join、投影和展开职责。
5. 清理只因迁移产生的重复代码和无用 import，确认所有 Java 文件不超过 1000 行。
6. 运行完整 Maven 测试，并再次执行独立行数统计。

先拆测试可以让后续回归定位更清晰；生产代码从依赖较少的 AST adapter 开始，再处理承担编排职责的主分析器，可以降低同时改动范围。

## 错误处理与兼容性

本次不引入新的业务错误类型。现有解析异常仍由 `HiveScriptLineageAnalyzer` 转换为失败结果；缺失元数据、视图边界和解析降级仍使用现有 warning code。协作类不吞掉异常，也不改变 warning 的添加顺序。

包级内部类名称不是公共兼容承诺，但现有 public 类型、构造器和方法签名必须保持不变。结果集合继续保持当前顺序，避免快照和下游消费发生变化。

## 验证标准

- 行数结构测试先红后绿。
- 原有测试方法全部保留并通过，仅允许移动所属测试类和提取共享 fixture。
- 完整 Maven 测试退出码为 0，无失败和错误。
- `src/main/java` 与 `src/test/java` 中没有超过 1000 行的 Java 文件。
- `git diff` 不包含 `release/` 或与本次职责拆分无关的文件。

