# v2 审查问题修复与重构

本轮对应 2026-10-15 提供的 21 项审查，保留 Syntax → Bound IR → Verifier → Analysis 的阶段边界。所有修改位于 v2、相关测试和质量检查入口；以下编号与原审查清单一致。

## 逐项结果

| 编号 | 核验与实施 | 验收依据 |
| --- | --- | --- |
| 1 | CASE ELSE 确实遗漏。owner 收集改为迭代遍历 `IrTraversal.children()`，并按 scope/schema 快照索引 slot owner。 | `DefaultSemanticBinderTest` 验证 ELSE 才引用第二个 owner 时的最终隐式 JoinFact。 |
| 2 | Unknown 的可靠 children 确实丢失。显式绑定 `UnknownExpressionSyntax`，保留字段、子查询及未知原因；子查询收集也识别这些 children。 | 字段和子查询同时被未知节点包装的 IR 回归。 |
| 3 | NOT BETWEEN 等运算符规则遗漏、函数签名未被消费。新增 `OperatorTypeRules`；类型分析读取函数 descriptor、版本和重载，集合/struct 形状参与匹配，歧义保守合并。 | BOOLEAN 投影及 `FunctionTypeInferenceTest` 的返回类型、重载、版本和未知函数契约。 |
| 4 | 来源、目标、DDL 解析路径确实分叉。`CatalogObjectResolver` 集中临时遮蔽、lookup alias、overlay、删除、不确定状态及 schema/存在性查询。 | 跨默认数据库、同名永久/临时表、INSERT 目标及 ALTER 对象身份一致回归。 |
| 5 | `USE +` 可通过原快捷路径。`HiveIdentifierReader` 校验 Token 类型和原生 identifier 语法；非法 USE 无 session effect。 | 非法标点、数字、字符串、保留词、空引用，以及合法非保留词/反引号；后续输入输出表仍使用原数据库。 |
| 6 | 展示字符串去重可构造碰撞。`ColumnAddress` 按 QualifiedName 和分段 fieldPath 实现值相等；普通写入及定义导出均使用结构化键。 | `StructuredColumnAddressExportTest` 同时保留 `["a.b"]` 与 `["a", "b"]`，顺序稳定。 |
| 7 | 未知叶节点和函数头存在过度解释。使用明确 literal/CAST 类型与形状分派；未知结构保留可靠 children 和窗口子结构，产生 normalization 诊断。真实 Token 类型优先于文本前缀。 | 未知叶/函数、`tok_id`/`tok_udf`、窗口 partition/order/frame 回归及全部语法语料。 |
| 8 | parser 诊断重复汇总路径成立。Normalizer 只返回新增诊断；Frontend 汇总阶段状态和诊断，并用结构化 `DiagnosticKey` 保序去重。定义准备也复用该键。 | `RECOVERED_PROJECTION` 和非法 USE 诊断计数回归。 |
| 9 | SELECT 绑定阶段与 refresh 顺序集中。拆为来源、投影、后置子句、组装阶段；使用不可变阶段结果、明确的 schema 完成边界和 `ClauseLocation` 别名可见性。 | star/JOIN/GROUP/HAVING 别名/window/ORDER BY 组合的完整绑定与 IR 验证。 |
| 10 | 编排层直接装配变化点。`AnalyzerServices` 在组合入口装配变量、需求、函数、验证、分析、规则和提交能力；抽出定义准备重试协议及发布决策。元数据改为预算与 manifest 能力接口；失败结果保留原始 Throwable。 | `AnalyzerStageSubstitutionTest` 比较替换组件的完整 JSON、未知降级、诊断、会话效果、资源限制和异常 cause。原具体参数构造器保留。 |
| 11 | Project 的 registry/fallback 双实现成立。内置关系算子统一由 `BuiltInRelationRules` 注册；Engine 保留输入准备、缓存、分派和证据收集。 | 所有内置算子注册、override、公共预分析表达式调用和 Project 证据一致契约。 |
| 12 | 两套 slot 来源登记有差异。不可变 `SlotProvenanceIndex` 统一 assignment/mapping/set/generator/window 结构，并包含 frame offset；格式化和兼容筛选保留各自策略。 | `SharedSlotProvenanceTest` 验证共享索引、窗口 offset 及只读集合。 |
| 13 | 提交条件与状态变化集中。拆为 `EffectPreconditionEvaluator`、按效果类型分派的 handler 和独立 `SessionStateDelta`；只在完成后发布新快照。Effects 继续是纯数据。 | 原有 session 生命周期、临时遮蔽、rename/drop、条件创建、定义登记、版本抑制和不确定性测试全部通过。 |
| 14 | API 识别 Hive TOK 字符串成立。方言内部输出稳定语义分类及数据变更标记；API 使用具名语句族分类和独立发布决策。 | 所有 DDL/命令分类与输出契约；架构测试禁止 API 分类器包含原生 Token 名。 |
| 15 | 按领域归属常量。新增 `DiagnosticCode` 契约和编排诊断枚举、`ClauseLocation`、运算符目录、显式 namespace qualifier、规则身份目录；默认资源/缓存/脚本预算及引擎身份归所属类型，语句身份使用工厂。保留扩展字符串诊断。 | 稳定输出契约、架构门禁及原有参数/默认值测试。不同语义但同数值的预算没有合并。 |
| 16 | AST 归一化入口确实缺边界。`SyntaxOptions` 增加节点数和深度预算；归一化前显式栈校验并建立索引，索引/查找不再依赖递归。 | 深 AST、宽 AST 在转换前以稳定 `NORMALIZATION_DEPTH_LIMIT` / `NORMALIZATION_NODE_LIMIT` 失败。 |
| 17 | 变量展开分配后检查成立。原文和替换值共用 append 预算方法，追加前以 long 检查预计大小，失败立即返回。 | 重复大变量在首个越界追加前终止，不继续分配后续 mapping/evidence。 |
| 18 | 共享 DAG 重复求值成立。一次同步稳定环境内使用 identity memo，不跨 slots/rows 环境复用；写入 condition 在 assignment 循环外求值，再逐目标重定向。 | 45 层共享 DAG 的规则访问次数、环境隔离、80 列写入条件只求值一次。 |
| 19 | 逐列/逐 slot 扫描成立。结构验证按 operator 建透明 mapping 和祖先集；set 分支及绑定验证复用输出索引。导出按写入/对象/字段分组依赖，兼容层复用 selector 和 root inputs，PureIrIndex 索引透明 mappings。 | 2000 列/60 层验证链不随列数增加祖先访问；1200 条依赖和 1200 次查询不重扫，并隔离 write/object 身份。 |
| 20 | 多 INSERT 的公共结构与 Token 重复扫描成立。缓存不可变 FROM/CTE/query/expression 转换结果；parser、bridge、artifact、normalizer 共用 TokenIndex 和兼容标记，范围查询使用索引。 | 多 INSERT 分支共享同一公共 FROM/CTE 对象；现有多 INSERT、CTE、区间和 mode 语料通过。 |
| 21 | 原架构门禁不足。新增 JDK AST 复杂度基线检查，集成到 ArchitectureContractTest；补充替换实现的共同契约与基于访问次数的规模测试。 | 新方法复杂度上限 20，已有热点不得超过基线；全部架构、风格、输出及规模测试通过。 |

## 预期行为差异

- CASE ELSE 引用的 owner、Unknown 中可靠字段/子查询/窗口结构现在参与分析。Unknown 本身仍然保留未知/部分覆盖语义。
- 非法 USE 失败且不更改会话；跨数据库临时对象的写入与读取使用同一身份。
- 点字段和嵌套路径分别导出，修复原先互相覆盖的情况。
- 未识别原生结构现在明确降级并产生诊断；前端 outcome 可由 SUCCESS 变为 PARTIAL。诊断不会因 parser/normalizer 汇总重复。
- USING 的 Unknown 已知字段会触发 schema 加载，原先由遗漏 children 导致的 `OUTPUT_SCHEMA_UNKNOWN` 可消失；仍保留 unsupported 诊断、PARTIAL 及既有可靠血缘。
- 默认规则库的 Project proof ID 保持不变。自定义空 registry 也复用同一内置 Project 规则，其行证据由旧 fallback 的 `relation.rows` 统一为 `project.rows`。其他内置证据标识与版本保持稳定。
- 未登记的第三方 Effect 实现会标记提交不确定，避免静默忽略。

## 修复前代码回放（RED）

为复核修复依据，将本轮修改前 Git HEAD 的全部 v2 源码解出到独立临时目录，以 `javac --release 8` 编译，使用本轮回归对旧实现进行隔离回放。这是收尾时的修复前版本回放记录。

`DefaultSemanticBinderTest` 原样在旧代码运行 24 项，其中 3 项失败：

| 回归 | 旧实现实际结果 | 当前结果 |
| --- | --- | --- |
| CASE ELSE 隐式 JOIN | JoinFact 数量期望 1，实际 0。 | 1 个隐式 JoinFact，并保留完整 owner 引用。 |
| Unknown 字段/子查询 | 未知原因期望 `FUTURE_OPERATOR`，实际被改为 `UNSUPPORTED_EXPRESSION`，可靠 children 丢失。 | 原因与 2 个 children 均保留。 |
| 跨数据库临时表 sink | 期望对象 `temporary-object`，实际为 `source:cluster:other.t`。 | 来源、sink 和 DDL 均使用临时对象身份。 |

直接类型回放使用旧两参 TypeAnalyzer API：5 项中 4 项失败，包括 `not_between`、`not_in` 内部运算符期望 BOOLEAN、实际 INTEGER；函数 `greater_customer` 的 descriptor 期望 STRING、旧名称匹配实际 BOOLEAN；变参 descriptor 期望 DATE、实际 STRING。两个直接否定运算符回归已持久保存在 `FunctionTypeInferenceTest`。当前独立语义回放共 33 项全部通过。

普通 SQL 的 NOT BETWEEN / NOT IN 投影测试在旧代码也通过，不能据此声称所有此类 SQL 都曾推断错误。原生前端可用外层 NOT 表达否定；上述失败证据对应内部运算符规则和函数 descriptor 扩展契约。

## 质量检查口径

复杂度采用 `scripts/quality/V2ComplexityGate.java` 的 JDK AST 统计：1 加 if、循环、catch、非 default case、三元表达式以及 `&&` / `||`。这是本仓库的可复现门禁，不声称与 Sonar/PMD 完全同口径。基线从本轮修改前的 Git HEAD 建立；CI/测试只校验，不自动抬高阈值。嵌套类的方法独立统计。

```bash
python3 scripts/check-v2-quality.py
```

资源及算法测试检查边界、对象共享和访问次数，不依赖机器速度或毫秒阈值，也不推断生产吞吐提升百分比。

## 验证

以 Homebrew OpenJDK 17 执行 Java 8 release 编译：

```bash
mvn -o -q -Dmaven.compiler.release=8 -DskipTests test-compile
python3 scripts/run-v2-validation.py --suite all --offline --no-compile --classpath-file target/v2-maven-classpath.txt
python3 scripts/run-v2-validation.py --suite legacy-oracle --offline --no-compile --classpath-file target/v2-maven-classpath.txt
```

本地 classpath 文件复用仓库已有构建产物；干净环境可使用 `python3 scripts/run-v2-validation.py --suite all` 生成依赖 classpath 并编译。验证入口直接运行 JUnit，不依赖本机未缓存完整的 Surefire 插件依赖。

- 全仓库主体：1206 项通过，0 失败、0 assumption skip。
- 原 JDBC Provider 与原断言：内存连接桩运行 5 项通过。
- 原 v1 生成语料：隔离原 18 份 SQL 与未改快照，2 项通过。
- 合计全仓库：1213 项通过。
- legacy-oracle：原有断言切换至 v2 适配入口，200 项通过，0 失败、0 assumption skip。
- 复杂度门禁、原仓库导入/代码块风格门禁及 `git diff --check` 通过。
