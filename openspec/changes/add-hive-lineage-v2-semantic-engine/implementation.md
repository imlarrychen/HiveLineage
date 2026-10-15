# V2 实现与使用

V2 独立入口位于 `com.panfan.hivelineage.v2`。V1 源码、默认入口、既有 SQL、原 Markdown 快照和 Maven 配置保留。

生产类型按功能命名：原生入口为 `ScriptLineageAnalyzer` / `DefaultScriptLineageAnalyzer`，请求和结果为 `ScriptAnalysisRequest` / `ScriptAnalysisResult`，兼容入口为 `HiveScriptLineageAdapter`，CLI 与 JSON 输出分别为 `ScriptLineageCli`、`LineageJsonRenderer`。迁移表见 [README](../../../README.md#package-layout-and-api-migration)。v2 测试按被测功能包组织，独立辅助类位于 `v2.testing`，跨模块契约位于 `v2.contracts`；既有 v1 代码和测试保持不变，SQL 资源不移动。

## 入口

兼容入口接收已有请求、返回已有 DTO：

```java
HiveLineageAnalyzer analyzer = new HiveScriptLineageAdapter();
LineageAnalyzeResult result = analyzer.analyzeScript(existingRequest);
```

`HiveScriptLineageAdapter.analyzeNative(existingRequest)` 同时提供完整 V2 结果。原生入口是 `DefaultScriptLineageAnalyzer.analyzeScript(ScriptAnalysisRequest, WorkflowContext)`；工作流批次使用该类的 `analyze(WorkflowBatchRequest, WorkflowContext)`。业务 `taskVersion` 不作为引擎版本，结果单独记录 `EngineIdentity`。

## 分层实现

| 阶段 | 实现 | 输入与输出 |
| --- | --- | --- |
| 脚本 | DefaultScriptPreprocessor / DefaultScriptUnitReader | 按引号、注释与分隔符形成原始单元 |
| 变量 | DefaultVariableResolver | 当前 Session 的显式变量 → ExpandedUnit、SourceMap、VariableEvidence |
| 词法 | HiveSqlLexer | ExpandedUnit → 不可变 LexedUnit |
| 语法 | HiveSqlSyntaxParser | 自有 Token 桥、独立解析游标 → 私有 HiveParseArtifact |
| 归一化 | HiveSyntaxNormalizer | Hive 结构 → 强类型 Syntax IR |
| 绑定 | DefaultSemanticBinder / Sink / Effect / Type / Function 组件 | Syntax + 只读 Session + 按需元数据 → Bound IR、定义准备需求、效果提案 |
| 验证 | DefaultStructuralVerifier / DefaultBindingVerifier / DefaultIrVerifier | 验证图、身份、输出、关联范围及请求能力，颁发当前 unit 的证书 |
| 推导 | DefaultLineageEngine / DefaultJoinConditionAnalysis | 仅 VerifiedAnalysisUnit → 角色、未知、质量和证据 |
| 提交 | DefaultSessionEffectPlanner / DefaultSessionEffectCommitter | 显式计划 → 新的不可变 SessionSnapshot |
| 输出 | DefaultLegacyResultProjector / 三类 RecordExporter | 声明与事实 → 兼容 DTO、独立记录；源码附件只用于渲染 |

Hive Parser 只能通过方言 Token 桥使用，不能调用 `ParseDriver.parse(String)` 重新分词。前端不读 Provider；Binder 不计算血缘；血缘推导不读取 SQL、Token、AST 或元数据回调。源附件在 V2 结果中单独保存，核心 IR 不保存 SQL 文本。

## 元数据

`DefaultV1MetadataAdapterFactory` 复用 V1 Provider 和调用方 Cache。V1 打包接口无法保证对象读取不加载字段，能力明确声明为 `BUNDLED_LOOKUP`。要避免字段加载，可在原生 `WorkflowContext` 提供 `ObjectDescriptorProvider` 与 `RelationSchemaProvider`；适配层优先请求独立 facet。

缓存键包含数据源、集群、snapshot/access 上下文、对象及 facet，不包含任务版本。既有 Cache 第一次适配时绑定一个 Provider 和读取上下文；跨来源复用会被拒绝，已预热旧 Cache 的历史来源必须由调用方保证匹配。

`CapturedMetadataAccess` 固定批次首次读取，合并并发请求；进程 Cache 的 TTL 不改变当前批次结果。访问失败不会成为权威不存在，也不写进进程负缓存。`snapshot()` 捕获实际 payload；`DefaultMetadataReplayProviderFactory` 与 `SnapshotMetadataServices` 不对缺失项回退到实时服务。既有 JDBC Provider 合成的视图定义标记为 `SYNTHESIZED_DEFINITION`，普通不明出处标记为 `PROVIDER_DEFINED`。

`DefaultDefinitionCoordinator` 统一准备外部定义，缓存独立 SyntaxTemplate；每次读取在定义上下文和当前目录中重新绑定。临时目录、变量、逻辑定义和数据版本均为脚本内状态，不经过共享 Cache 跨任务传播。

原生分析器可以注入 `ScriptSourceProvider`，读取不含内联 SQL 的 `DefinitionRef`；Binder 只返回 `NEEDS_DEFINITION`，由编排层读取、变量展开并调用统一前端。默认 `DefinitionTextSourceProvider` 使用已提供的文本；缺失时保留视图边界并发出诊断。外部源服务与元数据服务共享请求的 `maxExternalReads` 预算，内联文本和缓存命中不消耗预算；读取预算或定义源码长度超限使当前语句失败，恢复模式继续后续语句。

模板键使用长度编码的上下文与展开文本指纹，避免变量内容含分隔符时发生碰撞。缓存命中保留相同诊断、变量证据和源码映射；默认模板缓存最多 1024 项、TTL 15 分钟，并可通过原生分析器构造参数替换。元数据 payload 与读取形状以原子条目保存，打包读取不会在下一批次伪装成轻量读取；旧缓存未声明形状时保留 `UNKNOWN`。

## 已有契约与原生策略

兼容入口默认选择 `CURRENT_API_TARGET_MAPPING`，承接已有目标元数据缺失时按查询输出名推断、普通无显式目标列按前 N 个字段映射以及显式目标列与星号按名匹配。涉及推断或裁剪时，原生结果记录 `PARTIAL_SUCCESS`、`UnknownMapping` 和 `UNDETERMINED`，兼容 DTO 保留原数字状态及可表达的输出。原生 `POSITIONAL_EXACT` 校验真实位置和已知宽度；`EXPLICIT_LEGACY_TARGET_MAPPING` 与 `SPEC_TARGET_SCHEMA_MAPPING` 分别用于限定的旧星号契约和原 OpenSpec L-13 场景。

变量同样采用明确策略：`CURRENT_API_FALLBACK` 承接已有统一兜底行为；`PRESERVE_UNKNOWN` 保留未知占位；`SPEC_VARIABLES` 复现原规范的 bizdate 白名单与 `@{...}` 兜底。运行时 hivevar/hiveconf/system/env 的命名空间独立，SET/USE 逐语句提交。

Join 直接对象中的基础表使用物理名称，CTE/派生表使用逻辑名称；原生直接字段保留出现位置和别名，旧 DTO 使用其原来的解析后字段展示。展开视图或临时表时，原生依赖保留完整物理路径。兼容投影按旧 DTO 的发布边界输出：视图定义边先于消费语句的写入边；临时表定义边在创建语句输出，下游消费不重复追加。

## CLI 与结果

复制 `config/lineage-analyzer-v2.example.properties` 后，运行：

```text
com.panfan.hivelineage.v2.cli.ScriptLineageCli <config-path>
```

支持已有配置项，并增加 `lineage.parseJoinConditions`、`lineage.v2.jsonOutputPath`。JDBC 配置可省略，缺少元数据会保留明确未知与诊断。Markdown 和 JSON 默认写入 `target/lineage-v2/`，不会覆盖 V1 快照。有失败语句返回退出状态 2；成功或可诊断的部分成功返回 0。

JSON 包含完整依赖角色、关系输出、写入、未知诊断、闭合的证据 DAG、原始/展开源码、变量 SourceMap 及元数据读取清单。证据父节点全部可追溯，常量、参数、复杂字段路径和外部 URI 分别保留身份。RecordExporter 不直接持久化；Join 记录包含父语句的工作流、任务、版本和稳定序号。

启用 `CATALOG_SIMULATION` / `DATASET_VERSION_SIMULATION` 后可推演目录和数据版本，版本历史同时记录读取版本、提交后的前驱、UNKNOWN 以及静态执行假设。临时表的版本作为物化读取边界；视图继续跟踪逻辑定义来源。显式 `EXTERNAL_DATA_LINEAGE` 支持 LOAD DATA 的 LOCAL / DISTRIBUTED URI、写入模式和未知文件 schema，不读取文件；未启用时返回明确 unsupported。多目标 INSERT 与 MERGE 多动作分别保存写入贡献。

## 对比与自验证

`DefaultGeneratedSqlCorpusLoader` 递归直接读取 `src/test/resources/hql/generated/**/*.sql`。新增文件自动进入测试分母；`v2.testing.LegacyCorpusMetadataFixture` 为比对测试提供原配置的元数据契约，测试辅助独立维护在 v2 包内，不修改既有 v1 fixture 或 SQL。

`DefaultLineageComparisonRunner` 要求显式 MetadataSnapshot。V1/V2 使用相同 payload、业务身份与选项，分别创建 Cache 和 Session；`DefaultLineageDifferenceRenderer` 输出结构差异。差异默认 `PENDING_REVIEW`，不能把本次 V2 输出自动批准为标准答案。

资源限制包括源码长度、定义展开深度、IR 唯一节点与 Slot、IR 深度、全部证据边及实际外部 Provider 调用。缓存命中不消耗外部读取预算；共享子图按身份计数。算法推导前先验证深度，证据工厂在每个新节点/父边入图前检查预算。

自验证命令（使用已配置 JAVA_HOME 的 JDK 8 或以上）：

```text
python3 scripts/run-v2-validation.py --suite all
python3 scripts/run-v2-validation.py --suite v2
python3 scripts/run-v2-validation.py --suite legacy-oracle
```

脚本直接读取项目测试源码和 Maven 依赖。`--suite all` 在临时工作目录中为既有 v1 语料测试提供原始 18 份 SQL 和快照，并运行原测试字节码；全部 v2 扩展资源仍由 v2 测试读取。原 JDBC 五项测试由临时子类继承，子类使用 `JdbcMetadataConnectionRule` 安装内存连接桩和作用域内的 `lineage.mysql.*` 属性；不向原 v1 类添加 Rule，不改原方法、Assume、fixture 或断言。测试结束后恢复属性并注销驱动。此模式验证连接边界及应用逻辑，真实 MySQL 驱动协议和服务由独立环境验证。

`--suite legacy-oracle` 只在临时源码副本替换分析器入口；V1 AST 构造器故障注入只替换为 `ViewFrontendFaultAnalyzerFactory` 的独立前端故障注入，保留原 SQL、fixture 和期望，失败不自动更新。统计测试的故意失败夹具保存为 `.java.txt`，运行时在临时目录编译，不进入 IDE 测试发现路径。已有 classpath 可用 `--classpath-file` 指定，`--no-compile` 使用已编译结果。既有 `scripts/run-tests.sh` 继续按原方式执行 Maven，不为新增 v2 资源提供这层隔离。

具体通过结果、历史行为差异与逐场景证据以 `coverage.md` 和本轮验证报告为准；模型存在或 SQL 无解析错误不等于血缘正确。
