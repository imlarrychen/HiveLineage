# Delivery: V2 接口、模型与单元测试阶段记录

本文记录最初限定范围的交付；用户随后授权完成剩余开发，当前实现与验收分别见 [implementation.md](implementation.md)、[validation.md](validation.md)。

## 范围

用户最初批准的接口阶段限定为“所有接口和模型、单元测试，不完成具体代码实现”。该阶段新增自有模型、阶段/SPI 接口、构造检查、不可变集合、模型 fixture 和契约测试。

接口阶段未实现词法、语法、Normalizer、Binder、目标映射算法、IR Verifier、血缘传播、Provider 转换、缓存加载/录制/重放、Session 提交、V2 CLI 或双运行比较；这些算法在接续阶段实现。两个阶段均未使用默认成功/空血缘假引擎或 UnsupportedOperationException 补位。

V1、默认入口、POM、原测试和 generated SQL/快照保持原样。

## 主要接口和模型索引

所有新增生产代码位于 `src/main/java/com/panfan/hivelineage/v2`。

| 层 | 接口 | 核心模型 |
|---|---|---|
| api | ScriptLineageAnalyzer、WorkflowBatchAnalyzer | 请求、执行契约、能力、资源限制、任务/引擎身份、语句/批次结果、分析依据 |
| frontend | ScriptPreprocessor、VariableResolver | ScriptUnit、ExpandedUnit、SourceMap、VariableEvidence、入口和变量选项 |
| lexical | SqlLexer | LexToken、Trivia、LexedUnit、LexResult、词法符号和通道 |
| syntax | SqlSyntaxParser<P>、SyntaxNormalizer<P> | SyntaxStatement、SyntaxQuery、结构化名称、表达式、FROM/CTE/Join、窗口、生成、集合、分区、写入、DDL/命令和 SyntaxTemplate |
| dialect | DialectFactory<P> | DialectProfile；无 Hive/ANTLR 具体类 |
| semantic | SemanticBinder、AnalysisDemandPlanner、TypeAnalyzer、FunctionCatalog、SinkPlanner、EffectBinder、IrVerifier、StructuralVerifier、BindingVerifier、ViewTemplateCache | Scope、BindingContext/Result/Demand、PreparedDefinition、函数描述、写入请求、VerificationResult、模板缓存键 |
| ir | BoundExpression、BoundRelation、BoundEffect | 身份、TypeRef、FieldIdentity、Expressions、Relations、OutputSchema、BoundWrite、Effects、BoundAnalysisUnit、绑定覆盖和验证证书 |
| metadata | MetadataProvider、ObjectDescriptorProvider、RelationSchemaProvider、MetadataAccess/Factory、MetadataCache、MetadataRecorder、MetadataReplayProviderFactory、V1MetadataAdapterFactory、ScriptSourceProvider | 读取域、能力、明确查询状态、字段/关系/对象、定义/版本/出处、原 Provider/cache 绑定、读取清单和真实载荷快照 |
| session | DefinitionTemplate、DefinitionCoordinator<T>、SessionEffectPlanner、SessionEffectCommitter | InitialSessionContext、SessionSnapshot、变量、目录覆盖、逻辑定义、版本、不确定状态、计划和提交结果 |
| analysis | LineageEngine、JoinConditionAnalysis、ExpressionLineageRule、RelationLineageRule、LineageRuleRegistry | 类型化端点、角色、精度、表达式/关系事实、质量、证据、Join 及完整条件来源 |
| compat | RequestAdapter、LegacyResultProjector、CompatibleLineageAnalyzer | LegacyProjection、ProjectionLimitation |
| export | SourceRenderer、JoinConditionRecordExporter、LineageRecordExporter、FormalLineageRecordExporter | 独立身份记录、ColumnAddress、RenderedExpression、JoinPresentation、表/字段记录 |
| compare | GeneratedSqlCorpusLoader、LineageComparisonRunner、LineageDifferenceRenderer | 原 SQL 用例、差异、分类、比较结果和报告 |

类名以实际源码为准。未来算法实现使用这些接口，不要求重用 V1 内部模型。

## Bound IR 的实际位置

- [表达式接口](../../../src/main/java/com/panfan/hivelineage/v2/ir/expression/BoundExpression.java) 与 [表达式节点](../../../src/main/java/com/panfan/hivelineage/v2/ir/expression/Expressions.java)。
- [关系接口](../../../src/main/java/com/panfan/hivelineage/v2/ir/relation/BoundRelation.java) 与 [关系节点](../../../src/main/java/com/panfan/hivelineage/v2/ir/relation/Relations.java)。
- [有序输出结构](../../../src/main/java/com/panfan/hivelineage/v2/ir/relation/OutputSchema.java)。
- [语义分析单元](../../../src/main/java/com/panfan/hivelineage/v2/ir/statement/BoundAnalysisUnit.java)。
- [对象显示声明](../../../src/main/java/com/panfan/hivelineage/v2/ir/statement/BoundObjectReference.java)。
- [绑定写入](../../../src/main/java/com/panfan/hivelineage/v2/ir/statement/BoundWrite.java)。
- [验证声明](../../../src/main/java/com/panfan/hivelineage/v2/ir/statement/VerificationCertificate.java) 与 [经声明验证的输入](../../../src/main/java/com/panfan/hivelineage/v2/ir/statement/VerifiedAnalysisUnit.java)。

模型中的证书只承载指定签发者和检查范围的声明，并绑定同一个不可变分析单元，不能拿相同 statementId 的另一张图复用。构造证书不代表 Verifier 算法已实现或实际运行，使用方必须信任签发者。

## 模型阶段固定的边界

- Slot、关系实例、物理对象、逻辑定义等使用不同身份；有序字段保留重复显示名称。
- NOT_REQUESTED、UNKNOWN、KNOWN 分开，已确定 owner 的 QualifiedFieldHandle 不声明字段存在性已验证。
- Bound IR 不存 SQL、Token、AST、Provider 或延迟执行回调。
- Binder 接口使用分面 MetadataAccess；定义产物为强类型 PreparedDefinition。
- 只共享 SyntaxTemplate 缓存，不共享任务绑定图；缓存键包含定义、上下文和来源。
- 元数据清单是读取证据；MetadataSnapshot 才保存实际载荷，两者不混称可重放产物。
- 名称展示保存在 BoundObjectReference/FieldIdentity，不能依赖解析身份字符串或重新查元数据。
- 完整 Join 条件保存表达式/范围；独立导出模型分开直接名称、物理名称和公开编码。
- 完整结果保留 Bound 单元及可选分析依据；旧 DTO 无法表达的信息有 ProjectionLimitation，不静默丢弃。
- 常量空来源、未知来源与未运行阶段不同；没有默认空结果冒充成功。

## 单元测试范围

新增测试覆盖：

1. 字段顺序和重复名、关系实例、关联引用、未知宽度和未请求区域。
2. 所有模型的关键不可变容器、嵌套集合复制和非法状态组合。
3. 验证声明的范围、检查项、签发者与图对象匹配。
4. 查询结构、词法位置和完整表达式形状，不依赖真实 Parser。
5. Metadata 真实载荷/读取域/类型、缓存身份、null 未知、能力与出处。
6. Session 初始状态、临时/永久层分开、逻辑定义、计划未提交和版本假设。
7. 阶段接口及禁止依赖：IR/analysis 无 Hive、Token、Provider 或 V1 推断；前端不读元数据。
8. 直接读取 generated 当前全部 18 个 SQL，并复用原 generatedMetadataProvider；保持 case_11 的预期错误契约。
9. 业务任务版本与引擎版本分开，独立导出信息及源码渲染不替代来源推断。

这里列出的契约测试验证接口和模型。具体算法、generated 端到端与原断言复用的后续执行证据见 validation.md。

## 接口阶段验证

- Maven 离线 `test-compile` 通过（JDK 17，项目 Java 8 source/target）。
- 所有新增生产源码使用 `javac --release 8` 编译通过。
- 不带 Hive 运行依赖，直接通过本地 JUnit4 运行新增契约测试及原源码规范检查：92 项通过，其中新增契约测试 87 项。
- Maven 默认 Surefire 3.2.5 的本地运行依赖不完整；尝试已缓存的 3.5.6 也缺 junit4 provider。因此不声称执行了成功的 `mvn test`。
- 额外使用匹配 Hive 依赖版本的本地 classpath（ANTLR 3.5.2、Hadoop 3.3.6）直接运行全部 JUnit 类：394 项运行、零失败；真实 MySQL 集成测试按原规则默认跳过。这是本轮新执行结果，没有用旧 surefire-reports 推定通过。

测试日志位于 `target/v2-junit-contracts.log`、`target/v2-junit-full-matched.log`；这些构建输出不作为已确认语义标准答案。

## 后续工作

接口阶段按当时授权在契约和模型处停止。用户随后明确授权继续剩余开发；当前完整实现、工程检查与适配结果见 implementation.md、validation.md。
