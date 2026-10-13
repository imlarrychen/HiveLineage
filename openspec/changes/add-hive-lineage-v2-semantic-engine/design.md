# Design: 兼容既有需求的独立 Hive 血缘 V2

## 1. 状态与设计原则

方案已获批准，词法、语法、归一化、绑定、验证、纯 IR 推导、缓存重放与效果提交现已独立实现。具体入口与策略见 [实现说明](implementation.md)，实际验收见 [验证记录](validation.md)。[接口与模型交付](contracts.md) 保留前一阶段接口索引。

原则：一个语义内核承载 SQL 原子和递归组合，执行契约控制按需分析、兼容绑定、Session 推进和公开结果。兼容必须贯穿流程，不能全量分析完再仅删除输出字段。

词法、语法与血缘不是一个混合处理器。新增的 [Bound IR 专项设计](bound-ir.md) 明确阶段边界：词法输出 Token，语法输出结构，Binder 输出按需求绑定的语义单元，血缘只消费经校验的 Bound IR。V1 的 ParsedHiveStatement、文本抽取和来源 Map 不作为这些模型的基类、输入或实现捷径。

V1 保留。V2 尽量新增类；原有 SQL、测试、DTO、Provider 和默认 CLI 保留。必要构建修改单独列明，不修改旧推断规则以配合新实现。

## 2. 包与入口

先在当前 Maven 工程新增 `com.panfan.hivelineage.v2`，不先拆多模块：

| 包 | 职责 |
|---|---|
| `api` / `compat` | 原生入口、既有请求和结果适配 |
| `frontend` | 脚本单元、命令、变量与展开位置映射 |
| `lexical` / `syntax` | 自有 Token、词法/语法接口、结构化 Syntax IR |
| `dialect.hive` | Hive Lexer/Parser、Token 桥、AST 归一化和方言规则 |
| `semantic` / `ir` | Binder、写入/效果绑定、Scope、Slot、类型化 Bound IR 与 Verifier |
| `analysis` | 表、字段、Join 条件、效果和质量分析 |
| `session` | 脚本状态、定义目录、覆盖层、数据版本 |
| `metadata` | V1 Provider 适配、能力描述、缓存 |
| `export` | 独立语句和 Join 导出记录 |
| `compare` | 双运行、规范化比较与报告 |

`HiveV2LineageAnalyzer` 实现现有 `HiveLineageAnalyzer`，保留 `analyzeScript(LineageAnalyzeRequest)` 返回类型。V2 原生入口额外接收能力集合和显式执行上下文，并返回完整质量及证据。

V2 不调用 V1 的投影推断、字段血缘构建或展开流程；可复用公开 DTO、外部 Provider、测试 fixture、输出渲染器及注入的脚本预处理实现。

## 3. 主流程与契约

```text
原请求 / V2 请求
    ↓ RequestAdapter
AnalysisContract + AnalysisDemandPlanner
    ↓
ScriptFrontend：取得当前单元、当前变量展开、保留源码位置
    ↓
SqlLexer → LexedUnit
    ↓
SqlSyntaxParser → 方言私有语法树
    ↓
SyntaxNormalizer → 结构化 Syntax IR
    ↓
SemanticBinder + SinkPlanner + EffectBinder → BoundAnalysisUnit
    ↓
IrVerifier → VerifiedAnalysisUnit
    ↓
TableAnalyzer / ColumnAnalyzer / JoinConditionAnalyzer → 分析事实
    ↓
编排层按契约校验并提交本语句效果
    ↓
LegacyResultProjector / V2 完整结果 / 独立记录导出
```

`AnalysisContract` 包含功能集合、变量策略、兼容定义策略、效果应用策略和结果策略。策略以固定、可测试的规则表达，核心循环不按 SQL 模板分支，也不散布一个全局 `legacyMode` 布尔判断。

Lexer/Parser/Normalizer 不查询元数据或 Session 目录。Binder 只读取显式 BindingContext，不生成血缘结果；SinkPlanner 在血缘前形成绑定赋值映射。血缘规则不接收 SQL、Token、AST、别名查找表或 Provider；文本渲染与 Session 提交在分析之外。

既有请求的三个解析模式转换为 `TABLE_LINEAGE`、`COLUMN_LINEAGE`，Join 开关增加 `JOIN_CONDITION`。原生请求可只选择 `JOIN_CONDITION`。`includeTemporaryLineage`、`includeIgnoredStatements`、`expandViewLineage`、深度、`failFast`、兜底值逐个映射并保留默认值。

## 4. 按需与渐进绑定

绑定按所需事实分层：对象与关系绑定、条件字段绑定、输出结构和目标映射绑定。未请求的信息不应造成整体失败。

| 能力 | 分析范围 | 元数据要求 |
|---|---|---|
| 表血缘 | 输入输出对象、临时/CTE 对象来源 | 默认不读字段；视图展开另行请求对象信息 |
| Join 条件 | 当前条件、查询块、直接引用、必要的定义追溯 | 限定物理字段不要求 schema；歧义和星号定义追溯按需读取 |
| 字段血缘 | 有序输出、字段归属、表达式、目标映射 | 按需读取来源及目标结构 |

SchemaState 分为 `NOT_REQUESTED`、`KNOWN`、`UNKNOWN`。`a.id` 的对象可确定但字段存在性未验证时记录该事实，不能因未请求 schema 拒绝 Join-only 分析，也不能声称字段存在性已验证。

渐进绑定每次产生不可变结果与 BindingCoverage。已确定字段使用 SlotRef；仅对象归属已确定使用 QualifiedFieldHandle；无法确定使用 UnknownReference/UnknownOutputSegment。未请求区域使用显式 NotRequestedRegion，禁止把原始 SQL、Syntax 节点或可调用 Provider 的惰性对象塞入 Bound IR 让血缘阶段继续解析。扩展需求由编排层重新请求 Binder。

TABLE_ONLY 未开视图时不展开 `*`、不因字段数或未知字段失败、不产生字段缺失 warning。多个能力复用中间事实与记忆化结果，不强制完整编译检查。

## 5. 模型与规则

Bound IR 在 `v2.ir` 独立定义；Syntax IR 在 `v2.syntax` 定义，两者不互相继承。完整结构、字段引用状态、算子输出契约和示例见 [专项设计](bound-ir.md)。SourceRef 仅用于证据和文本定位，不用于来源推断。

身份层次为对象、数据版本、关系实例、有序输出 Slot 和嵌套路径。名称仅用于显示和查找；自连接和重复列名不得按字符串合并。

表达式包含常量、字段引用、函数、CASE、CAST、复杂字段访问、子查询和关联引用。关系包含 Scan、Project、Filter、Join、Aggregate、SetOp、Window、Generate 等。模型允许表达式与关系互相嵌套。

依赖事实区分 VALUE、CONDITION、ROWSET、SCHEMA、DEFINITION、STORAGE，保留已知来源、未知项和证据。常量空来源与未知来源不同。表依赖包含行集来源，不能只反推字段值来源。

普通及未知函数都递归分析参数；未知函数额外保留行为不透明信息，不强制终止。复杂字段类型缺失降低路径或类型质量，不抹除已确定的根字段来源。

Slot 保留原文本引用与已解析引用。表达式文本优先使用源码片段；表达式提取失败不抹除已确定字段依赖。窗口参数、分区、排序及窗口范围分开建模；兼容字段输出采用既有窗口依赖口径。

SinkPlanner 校验目标列、静态/动态分区和位置。未知输出宽度不能被当成数量不匹配；已确定宽度不匹配按目标契约报告。标准位置映射保留完整输出，历史裁剪规则只作用于明确登记的映射策略。

## 6. Session 与效果

每次脚本分析建立独立 Session，包括数据库、变量、TemporaryCatalog、ScriptDefinitionStore、PersistentCatalogOverlay、DatasetVersions 和 UncertaintyState。

本语句 CTE 和别名属于 QueryScope。ScriptDefinitionStore 仅承接前序纯查询 CTE 的既有兼容来源定义，记录定义位置、绑定上下文和来源；不输出物理或临时节点。查找优先级、名称遮蔽和限定名规则通过现有回归固定，不对所有 SQL 开放不受控跨语句作用域。

临时对象内部身份包含 Session；兼容显示名称继续使用 `seele_tmp_db_<workflowId>_<taskId>.<name>`，带库名与裸名引用按既有契约匹配。

语句效果先计划、再验证、再提交。SET、USE、临时创建和兼容定义承接原有效果。未知 USE 不应用兜底数据库。目录和数据版本扩展通过显式能力启用，既有入口不默认应用会改变后续结果的新 DDL/VALUES 效果。

扩展目录模型分开临时目录和永久覆盖层；删除临时对象恢复同名永久对象，删除永久对象使用本地 tombstone。追加、覆盖范围和自读写版本分开保存。所有推演声明“前序语句成功”的静态假设，不表示实际执行。

失败后默认继续到可靠边界，失败结果必须保留；`failFast` 终止后续推进。可能影响状态的未知语句保留不确定性，后续受影响结果降低质量；不把所有后续语句无条件判为确定失败。兼容恢复与扩展状态传播的变化进入差异记录。

## 7. 元数据适配与缓存

新增 `V1MetadataProviderAdapter` 包装原 Provider。优先使用调用方指定的数据源和既有缓存；一次运行采用明确的加载链，不由两套无关缓存各自再次读取底层服务。

原缓存不含 Provider 身份，复用范围必须限定为同一数据源和读取上下文；不能把同一个旧缓存直接挂到多个不同 Provider。原缓存的 null 负缓存只解释为“该读取未取得元数据”，不提升为对象不存在。V2 自有缓存使用含来源身份的键。

原 `HiveTableMetadata` 只提供字段名称列表、对象类型和视图 SQL。V2 保留字段顺序，类型、独立分区信息、版本等缺失属性标记未知。不因这些属性缺失拒绝已有可分析 SQL。

`null` 表示未取得元数据，默认不能作为对象不存在的证明；可通过 Provider 契约声明权威 NOT_FOUND。异常保持 ACCESS_ERROR，不写入对象不存在的缓存。Provider 未传入时返回能力缺失/元数据未知，表级分析仍可进行。

V1 接口打包返回对象、字段和视图信息。适配器报告 `BUNDLED_LOOKUP`，不能丢掉列列表就宣称底层未读字段。可选新增 `ObjectDescriptorProvider`、`RelationSchemaProvider` 及轻量实现满足分层加载。仅有旧 Provider 且视图展开开启时，允许明确记录的打包读取；无视图展开和无需 schema 的条件分析不发起该调用。

工作流批次固定读取清单，任务状态隔离。缓存键包括来源身份、集群/库/表、能力面、可用快照和访问上下文，不含任务版本。并发首次请求合并；元数据不可变；失败与未知分开。进程 TTL 只影响后续批次可重载数据，不能在一个批次中改变已固定依据。

ViewTemplateCache 共享解析及定义模板，不共享当前任务实例。键包括定义、方言、规则、相关初始绑定上下文及所依赖结构版本；Session 本地对象的实例化不得跨任务共享。读取时连接当前可见目录和数据版本。旧 Provider 合成定义的出处单独记录，无法判断时使用 PROVIDER_DEFINED。

外部定义文本由编排层 DefinitionCoordinator 通过同一词法/语法前端准备为 SyntaxTemplate，再提供给 Binder 递归绑定；缺少模板时 Binder 返回 NEEDS_DEFINITION。Binder 和血缘阶段不直接解析 viewSql 或 SOURCE 文本，避免定义展开重新耦合解析与分析。

## 8. Join 与公开结果

Join 条件是独立结构结果，不进入正式血缘。记录完整 ON，或同查询块逗号连接的跨来源 WHERE 条件；保留单侧 ON，排除逗号连接中的单表过滤和 EXISTS 的外层相关谓词。各查询块、重复文本不同节点均保留，语句内序号全局递增。

内部保存直接文本对象/字段、解析后直接对象/字段、追溯物理对象/字段。追溯不明保留直接信息与已成功事实。集合按首次出现顺序去重，类型采用内部明确枚举，旧数字编码单独映射。

结果采用三种表示：

1. 既有 LineageAnalyzeResult：保留旧 DTO、选项、名称、状态、表达式和计数契约。
2. 完整 V2 结果：质量、未知、角色、路径、实际加载清单及算法版本。
3. 独立导出记录：语句身份加 Join 或血缘明细，供下游独立消费，不直接落库。

正常纯 SELECT 默认不公开依赖；必要 CTE 定义或 Join 可内部分析。VALUES 在既有契约下无正式血缘、无公开写入事件，也不默认推进扩展数据版本。普通 DDL 保留忽略分类；已识别非法无血缘 DDL 按 INVALID_DDL 审计，不变成业务数据语句静默忽略。

statementIndex 兼容当前从 0 起始；joinIndex 从 1 起始；statementId 延续业务身份拼接。引擎版本另记，不替换 taskVersion。完整质量与旧状态映射分开，不能因内部出现 Unknown 就自动改成 FAILED 或 PARTIAL_SUCCESS。

独立 Join 导出记录补齐父语句身份和 rawSql；旧 Join DTO 无需增加字段。新增信息不适用旧 DTO 时完整结果保留转换限制，禁止静默丢目标或未知信息。

## 9. 既有规范差异

详见 coverage.md。变量、星号宽度、Join 类型编码和直接字段显示存在旧规范与现行行为差异。现有 API 兼容契约、规范策略或导出映射分别表达，追踪表不得把两种相反预期都标记为同一默认入口通过。

后续修正需要具体验证记录，不通过改旧 SQL、删断言或重写 V1 快照让差异消失。

## 10. 测试素材与验收

直接扫描 `src/test/resources/hql/generated/**/*.sql`，UTF-8 读取、按相对路径排序，当前 18 个文件是基线而非上限。采用相对路径作为 case 身份，避免新增子目录同名文件冲突。

新增同包 fixture 桥复用 `GeneratedSqlTestSetTest.generatedMetadataProvider()`，不改旧测试；V2 通过生产元数据适配器消费该 fixture。两边元数据及运行上下文一致，Session 独立、缓存独立重放或按明确共享协议执行。

先使用现有生成测试配置，再覆盖三种模式、Join-only、临时/忽略/视图选项。默认测试不访问外部数据库。原生扩展配置与既有配置的报告分开。

case_11 按既有预期验证一条失败、后续成功，不要求零失败。测试比较结构、字段位置、来源、表达式、Join、状态、warning、身份和元数据调用。未支持样例不从统计分母移除，覆盖率与正确性分开。

保留原 `lineage_parse_results.md`；V2 和差异报告输出到 `target/lineage-output/v2/`。经过核验的标准答案另存专用 expected 目录，显式维护，普通测试不自动刷新。旧快照不是全部正确性的唯一依据。

## 11. 实施阶段

1. 冻结逐场景矩阵、兼容契约、元数据 fixture 和比较模型。
2. 新增入口、按需骨架、基本关系与表达式、Provider 适配和对比链路。
3. 达到 V1 功能齐平：查询组合、分区、窗口、EXPLODE、视图、临时/CTE、Join、恢复和现有输出。
4. OpenSpec 场景验收：轻量能力、独立导出、扩展点、缓存及诊断，已知差异逐项结论。
5. 在显式能力下扩展更完整目录、数据版本、多动作写入和外部来源。

新增原子与组合、变换、缓存/隔离和大型计划测试；Parser 可变状态不共享，规则不可变。限制脚本、展开、递归、IR 和证据规模，超限明确诊断。新增代码遵守现有 Java 样式、正则常量和单文件行数检查。

实现顺序增加前置门槛：先用手工 IR 验证血缘引擎和 Verifier，再接 Binder，再接 SyntaxNormalizer 和 Lexer/Parser。generated 集检验端到端行为，不能替代各层独立契约测试。

## 12. 确认与实施边界

用户确认前只新增本目录文档。本文确认后按 tasks.md 实施，不自动删除 V1、切换默认入口或发布。生产方言细节和实际 Provider 能力在目标清单记录；采用明确基线推进，不把未取得的信息写成已验证事实。
