# v2 第二轮 OOP 重构与契约验证

本轮以 `c9aa278`（第一轮重构后的格式整理提交）为基线，对 2026-10-15 的更新审查实施结构重构。审查列出的是职责和维护风险，本轮不把它们直接称为新的运行时缺陷；目标是让业务规则和状态一致性有明确归属，保持已有输出及扩展行为。

## 职责及不变量

| 审查项 | 实施后的对象边界 | 独立验证的契约 |
| --- | --- | --- |
| 1. 引用解析与来源状态 | `ReferenceBinder` 协调 `NameLookup`、`SourceSchemaLoader`、`BoundReferenceFactory`。`SourceBinding` 通过工厂确定物理/派生/generator 初态，私有状态机控制一次 schema 加载和不确定终态；`ScopeSymbols` 统一来源注册、别名及字段/owner 索引。 | 别名优先于物理限定名；最长限定名、祖先作用域、struct 路径和 generator 优先规则保持；schema 不能重复完成，schema/来源可见性变化后 owner 索引正确。 |
| 2. 查询布局与作用域 | `ProjectionLayout.Builder` 配对注册 slot/assignment，统一维护可见 schema、隐藏输出和最终裁剪；`WindowLayout` 配对登记 slot/computation。`BoundFrom.Visibility` 将 ON 与输出来源分别应用到符号表，并在局部绑定后恢复。`DefinitionExpander` 负责发现/展开，返回 Expanded、Deferred、Unavailable。 | 重复 slot 原子拒绝；未知宽度与隐藏列裁剪不丢失结构；半连接右侧仅 ON 可见；定义结果不能以错误状态读取。 |
| 3. Sink 映射 | `TargetLayout` 只计算一次普通列、动态分区、静态赋值；四个 `SinkMappingStrategy` 分别处理 positional/spec/legacy/current API。`MappingPlan` 同时拥有赋值、覆盖、unknown 和诊断；`SinkMappingStrategies` 拥有默认注册，Planner 支持按策略替换。 | 策略实现可独立测试和注册；增加映射行为不需要修改 Planner 的分支；保留命名星号、推断、prefix、分区顺序和不请求/未知宽度契约。 |
| 4. 表达式 | 提取 `SubqueryExpressionBinder`、`WindowExpressionBinder`、`CaseExpressionBinder`；普通结构仍集中分派，所有递归经过原 `ExpressionBinder` 入口及缓存。 | 子查询路径和宽度、CASE 转换、窗口登记、相关引用与别名复用不变。 |
| 5. Effect 与 schema 编辑 | Session/Create/Drop/Alter 按语义族分派，共用 `CatalogEffectRequest` 的对象及前置条件解析。`SchemaEditor` 持有不可变列结构，编辑返回新对象，失败抛出明确的既有 `BindingFailure`。 | 名称唯一、位置连续、数据列先于分区列、分区角色和失败时保留原结构由编辑对象维护。Effects 仍为纯数据，未移入执行行为。 |
| 6. 规则求值 | `RelationEvaluationContext` 公开表达式求值和明确的 `Environment`；`RelationLineageRule.evaluate()` 用 Adapter 兼容旧预计算 Map 接口；`EvaluatingRelationLineageRule` 通过能力控制求值。Engine 不再识别内置规则实现类型。Window、SetOp、Generate 使用独立规则，`RelationFactBuilder` 负责通用事实及证据收尾。 | 外部规则可跳过 eager 求值；旧规则仍收到预计算参数；slots/rows 不跨环境混用；Environment 拥有只读快照，细化 rows 共享 slot 快照，避免每列复制宽表。 |
| 7. Verifier | `VerificationContext` 集中只读关系、输出、slot block、owner、scope 和可达性查询；覆盖、引用、版本与算子输出规则分离，共用有序 `VerificationDiagnostics`。SetOp 位置规则独立验证。 | 检查只报告，不修改或重新绑定 IR；声明及可见性集合不可变；可达性缓存上限 32 项；同 NodeId 不同 schema 的非法图仍保持原诊断，不以另一对象 schema 推导额外错误。 |
| 8. Session 内部 | `CatalogTransaction` 管理对象、别名和定义；`SessionBindings` 管理数据库/变量值；`UncertaintyTracker` 区分持久不确定证据与本次提交 outcome；`SourceVersionCollector` 每 plan 建一次索引；`VersionTransitionPolicy` 处理写入、rename、drop、skip 和版本抑制。`SessionStateDelta` 协调一次原子发布。 | rename 直接迁移目录，保留版本和不确定性，不再删除后恢复；每个 write 的遍历状态隔离；条件创建和定义登记不绕过版本抑制；历史不确定性不自动把无关空提交标成 UNCERTAIN；已返回的版本快照不受后续事务操作影响。 |

额外完成三个边界：

- `ExpressionShapeAnalyzer` 和 `FunctionUsageCollector` 拥有结构分类与函数收集，`BoundExpressionFormatter` 负责文本。旧 `ColumnDerivationClassifier.classify(formatter, ...)` 保留为兼容入口，核心使用 `classifyShape(...)`，不会调用 renderer，也不会增加 null 重载歧义。
- Hive 类型、窗口、特殊函数分别由专属 Normalizer 处理；子查询通过 `Function<ASTNode, SyntaxQuery>` 窄回调协作，递归继续复用同一表达式缓存。
- `HiveAstSupport` 组合 AST 索引/预算、来源映射、原生词表和诊断对象；诊断返回不可变快照，保留第一轮的预算、未知结构及稳定 ID 语义。

IR、DTO 和阶段结果继续保留不可变模型。没有为每个数据模型增加执行行为。

## 行为基线与测试

先在当前 HEAD 运行完整基线：1213 项全通过。新增的 3 项引用/查询集成契约、5 项 Sink 契约、2 项 Session outcome 契约在生产重构前先通过；其他独立对象契约针对新增边界验证不变量。它们用于保持行为，不把重构动机包装成缺陷 RED。

整合后验证：

- 全仓主体 1251 项，加 JDBC 内存连接桩 5 项、原 v1 隔离语料 2 项，合计 **1258 项**。
- 原断言切换到 v2 适配入口的 legacy-oracle：**200 项**。
- 全部为 0 失败、0 assumption skip；真实外部数据库不在这组验证中。
- Java 8 release 编译、原仓库导入/代码块风格检查、复杂度检查和 `git diff --check`。

新增独立契约包括 `BindingObjectContractsTest`、`DefaultSinkPlannerContractTest`、`SchemaEditorTest`、`RelationEvaluationContractTest`、`ExpressionStructureContractTest`、`VerificationContextContractTest`、`SessionTransactionContractTest`、`SessionParticipantsTest`、`HiveNormalizationServiceContractTest`。

### 原生输出逐字节对比

在覆盖 `target/classes` 前，对基线运行生成语料快照。共 185 份 SQL，覆盖三个解析模式，得到 **555 份完整原生 JSON**；重构后的相同 555 份逐字节对比，无差异。比较包括 JSON 已暴露的字段、顺序、质量、诊断与证据，不仅是成功/失败数量。默认规则 proof ID 和版本保持。

可复用驱动为测试工具 `com.panfan.hivelineage.v2.testing.NativeCorpusSnapshot`。从仓库根目录运行，使用相同的测试 classpath 和相同的语料/fixture：

```bash
java --add-opens=java.base/java.net=ALL-UNNAMED -cp "$TEST_CLASSPATH" \
  com.panfan.hivelineage.v2.testing.NativeCorpusSnapshot /tmp/native-before
# 切换至待比较的已编译实现，并使用相同驱动、语料、fixture 与解析模式。
java --add-opens=java.base/java.net=ALL-UNNAMED -cp "$TEST_CLASSPATH" \
  com.panfan.hivelineage.v2.testing.NativeCorpusSnapshot /tmp/native-after
diff -qr /tmp/native-before /tmp/native-after
```

本轮结果记录于 `target/v2-oop-native-comparison.json`。该比较证明当前语料上的输出等价，不替代未覆盖输入的行为契约。

## 复杂度门禁

使用仓库同一 JDK AST 统计器，基线与重构后均实测；以下分值包含短路条件，不将复杂度降低等同于职责已经合理。

| 原热点 | 基线实测 | 重构后协调入口 |
| --- | ---: | ---: |
| `ReferenceBinder.bind` 内部查找 | 50 | 1；查找策略由 `NameLookup` 持有 |
| `DefaultEffectBinder.bind` | 42 | 6 |
| `DefaultBindingVerifier.verifyBinding` | 40 | 6 |
| `ExpressionBinder.bindUncached` | 35 | 18 |
| `DefaultStructuralVerifier.operator` | 34 | 已由通用输出状态与类型规则替代 |
| `DefaultSinkPlanner.plan` | 32 | 4 |
| `QueryBinder.named` | 32 | 11 |

本轮没有抬高复杂度预算；移除已拆热点的旧豁免并收紧剩余预算，豁免由 24 项减至 4 项。新增方法仍不超过 20。保留的 4 项分别是现有 SQL 展示规范化、文本表达式渲染、结构图声明协调和语法子查询收集，不宣称所有存量方法均已低于 20。

## 验证入口

```bash
mvn -o -q -Dmaven.compiler.release=8 -DskipTests test-compile
python3 scripts/run-v2-validation.py --suite all --offline --no-compile \
  --classpath-file target/v2-maven-classpath.txt
python3 scripts/run-v2-validation.py --suite legacy-oracle --offline --no-compile \
  --classpath-file target/v2-maven-classpath.txt
python3 scripts/check-v2-quality.py
```

本轮使用 Homebrew OpenJDK 17；干净环境可直接运行 `python3 scripts/run-v2-validation.py --suite all` 生成 classpath 并编译。
