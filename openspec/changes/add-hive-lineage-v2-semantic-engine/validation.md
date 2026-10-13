# V2 具体实现验收记录

初次验证日期：2026-10-12；后续复核及连接桩验收：2026-10-13。最新结果见文末“JDBC 连接桩验收”。用户已批准方案并授权完成剩余开发；本记录覆盖具体实现阶段，前一轮接口与模型记录仍保留在 [contracts.md](contracts.md)。入口与配置见 [implementation.md](implementation.md)。

## 交付范围

新增 `com.panfan.hivelineage.v2` 的独立词法、语法、归一化、Binder、Bound IR 校验、纯 IR 血缘与 Join、Session 效果、元数据适配/缓存/录制/重放、兼容投影、原生 JSON、记录导出、比较与 CLI。V1 源码、默认入口、旧测试、SQL、快照及 POM 均未修改，也未执行切换、删除、发布或持久化。

实现不是将 V1 分析流程包装为新接口。Lexer/Parser 不读取元数据；Binder 生成绑定结构和定义准备需求；推导仅消费带当前能力证书的 Bound IR。手工 IR 测试不需要 SQL、Hive Parser 或 Provider；架构测试约束禁止依赖。独立 `ScriptSourceProvider` 可读取 opaque 定义引用，统一前端负责准备，读取预算与元数据共享。

## 自动化结果（2026-10-12 首次验收）

| 验证 | 实际结果 | 证据 |
| --- | --- | --- |
| 全仓回归及工程约束 | 681 项，676 通过，0 失败，5 项显式 assumption skip | `target/v2-validation-all.log` |
| V2 独立测试 | 374 项全部通过，0 skip；47 个测试源 | `target/v2-validation-release8.log` |
| 原 V1 断言使用 V2 入口 | 27 个原测试类、200 项；194 通过，0 失败，6 项显式 skip | `target/v2-validation-oracle.log` |
| Java 8 API/字节码编译 | 487 个新增生产源码、51 个测试/fixture 源码通过 `javac --release 8` | `target/v2-release8-verification.txt` |
| generated 原配置双运行 | 18/18 语句统计与全部 sink 字段来源一致；15/18 完整旧 DTO 零差异 | `target/v2-generated-original.log`、`target/lineage-v2/comparison/original-config-summary.md` |
| generated 三模式矩阵 | 18 文件 × TABLE_ONLY / COLUMN_ONLY / TABLE_AND_COLUMN，共 54 次实际分析 | `GeneratedV2EngineRegressionTest`，纳入全仓/V2 套件 |
| 原 OpenSpec 追踪 | 53 个血缘场景 + 24 个 Join 场景，共 77 行，包含策略分歧和方言限制 | [coverage.md](coverage.md) |
| 实际 CLI 与 JSON | 成功进程退出 0；失败进程退出 2；JSON 能解析，样例 22 个证据节点的父引用闭合 | `V2ComparisonAndCliTest`、`target/lineage-v2/cli-verification/` |

初次验收脚本动态发现当时 87 个含 `@Test` 的测试源，无漏选。`Tests` 包含已启动的方法以及方法级 assumption skip；类级 skip、类级失败和 ignored 单列，不能将其计为通过。专门测试覆盖 `BeforeClass` 假设不满足，避免通过数变成负数。

全仓五项 skip 是没有启用 MySQL 连接环境的既有 JDBC 集成测试。原断言替换验证另有一项 V1 AST 构造器专用故障注入 skip；V2 使用独立前端故障注入验证同类视图边界、诊断及后续恢复。未删除这些项，也未把跳过计作通过。

本机使用 Homebrew OpenJDK 17 执行；Java 8 兼容性由 `--release 8` 编译验证，并用该产物执行 V2 测试。这里不宣称使用真实 Java 8 VM 或连接实时 Metastore/MySQL。

## 原配置完整比较

`GeneratedV2OriginalConfigurationTest` 直接递归读取 `src/test/resources/hql/generated/**/*.sql`，通过同包桥复用 `GeneratedSqlTestSetTest.generatedMetadataProvider()`，没有复制 SQL 或修改旧 fixture。保持 `wf_generated` / 文件名去 `.sql` / `V1` 业务标识及原始 `TABLE_AND_COLUMN`、展开视图、包含临时血缘、`fallback_value` 配置，Join 和返回忽略语句保持默认关闭。

基线断言逐例检查统计、每个返回语句的业务身份、状态和正式血缘标记，以及 sink 的类型、模式、目标、完整输入对象、表边和每个目标字段的来源字段与来源对象。完整比较另捕获两套引擎所需的联合读取集合，冻结 payload、缺失状态及读取上下文，再在独立 Cache 和 Session 中调用两套引擎。`LegacyStructureDiffer` 比较所有旧 DTO 字段，包括 raw/normalized SQL、列 sourceObjects、warning 消息及失败信息；没有自动更新旧 expected。

修复的实际兼容缺陷包括集合标签/函数出现次数/complex、星号逐字段表达式、derived 外层别名、UNION DISTINCT 包装下的 OVERWRITE、常量列 sourceObjects、去注释显示、视图定义边顺序及临时定义边发布边界。原生完整图及源码证据保留。

剩余三例共七个字段差异全部保持 `PENDING_REVIEW`：

| SQL | 完整字段差异 | 核验与分类 |
| --- | --- | --- |
| lineage_case_09.sql | 顶层/语句 warnings，2 项 | V2 增加 `OPAQUE_FUNCTION`，保留可证明的参数字段来源，并报告未知函数可能引入其他来源；两边兼容状态均为成功、零失败。 |
| lineage_case_11.sql | 顶层 warnings、failureMessage、语句 warnings，3 项 | 两边第一条非法 SQL 均失败，第二条均恢复并产生相同映射；旧 DTO 无语法产物的类型保持 UNKNOWN。V2 增加准确 `HIVE_SYNTAX_ERROR` 和分层失败证据，原生类型仍为 UNSUPPORTED。 |
| lineage_case_12.sql | 顶层/语句 warnings，2 项 | 两边目标元数据均缺失，sink 映射一致；V2 另报告 `CURRENT_API_TARGET_MAPPING` 的命名/位置假设，原生质量保留 PARTIAL/UNDETERMINED。 |

顶层和语句 warnings 各占一个比较字段，七项不是七个不同语义问题。2026-10-13 复核发现兼容错误分类、主失败信息及重复诊断仍有待修项，不能将这些差异全部视为已确认改进，详见本文末尾。逐例完整报告位于 `target/lineage-v2/comparison/*-original-config.md`；`original-config-review.md` 记录逐项原因。诊断改进不被伪装为 SQL 执行失败，也没有被自动批准为新标准。

## 重点边界验证

- 对象/字段身份与出现位置分开；相同 FieldId 在不同物理对象、关系实例和逻辑 Slot 中不会串名。输出位置、重复名称、未知宽度与目标映射分别保存。
- TABLE_ONLY 与限定字段 Join-only 不加载无关 schema；V1 打包 Provider 明示 `BUNDLED_LOOKUP`，缓存 payload 与读取形状原子保存，旧缓存未声明形状保持 UNKNOWN。
- 外部定义的内联与 opaque 引用均走 `NEEDS_DEFINITION`；默认缺文本保留边界并诊断，预算耗尽/源码超长在当前语句失败，恢复模式继续后续语句。
- 证据工厂增量限制全部新节点和父边；闭合图拒绝悬空引用和循环。大型共享 DAG 按唯一身份计数；500 层计划在推导前按配置校验深度。
- Session、变量、目录和版本不跨任务共享。每个写入的前驱只取实际来源，缺可选 query root 时仍沿 Slot/限定字段身份追踪；临时表保持物化版本边界，视图跟踪逻辑定义。
- CATALOG_SIMULATION / DATASET_VERSION_SIMULATION / EXTERNAL_DATA_LINEAGE 显式启用后才应用相应新增语义；IF NOT EXISTS 不明存在性保留条件假设，未请求版本保持 NOT_REQUESTED。
- 扩展演练通过自定义规则与局部外部来源规则完成，无需改脚本主循环；未保留中间父证据的扩展规则明确拒绝。

## 复现

设置 `JAVA_HOME` 指向可用 JDK，并将 Maven 加入 PATH：

```text
python3 scripts/run-v2-validation.py --suite all --offline
python3 scripts/run-v2-validation.py --suite v2 --offline
python3 scripts/run-v2-validation.py --suite legacy-oracle --offline
```

脚本使用 `test-compile` 和 Maven 依赖清单，直接执行 JUnitCore。当前离线环境缺少 Surefire 3.2.5 运行依赖，因此保留 POM 并使用新增执行器；完整编译与实际测试都已执行。已有产物可传 `--no-compile --classpath-file target/v2-maven-classpath.txt`。

完整原配置报告的 Java 入口为 `com.panfan.hivelineage.GeneratedV2OriginalConfigurationTest.main`；先执行基线断言，再生成冻结比较报告。OpenSpec 验证命令：

```text
OPENSPEC_TELEMETRY=0 openspec validate add-hive-lineage-v2-semantic-engine --type change --strict --no-interactive
```

## 保留的验收限制

Hive 4.0.1 不接受 `LEFT ANTI JOIN` SQL 拼法；IR 和导出保留其类型码，前端不静默改为 SEMI。LOAD DATA 默认显式 UNSUPPORTED，与旧 V1 忽略该语句不同；显式能力仅声明 URI 来源，不读取文件或猜测字段。未知函数、缺元数据、外部执行条件都保留未知。

实时服务集成、全部生产方言和生成样例的人工标准答案仍需相应环境与独立依据。自动化结果覆盖本变更的实现与已登记场景，不代表任意 Hive SQL 已被穷尽验证。


## 2026-10-13：差异与跳过记录的定位复核

本轮重新使用相同冻结元数据运行 case09、case11、case12，并直接查看 native 结果、旧 DTO、写入赋值、质量及诊断。额外重跑 generated 原配置、三模式矩阵、视图故障/定义准备共 65 项，全部通过、无 skip（`target/v2-difference-review-tests.log`）。本轮仅分析与补充记录，生产实现和 expected 未修改。

原生探针：`target/v2-difference-analysis.log`；逐例完整 JSON：`target/lineage-v2/difference-analysis/lineage_case_09-native.json`、`lineage_case_11-native.json`、`lineage_case_12-native.json`。

### D-01：case09 未知 UDF 的额外警告合理，原生诊断存在重复

触发点是 `src/test/resources/hql/generated/lineage_case_09.sql:9` 的 `my_udf(score, level_code)`。函数目录没有这个函数的声明，`ExpressionBinder:205` 绑定为 OpaqueFunctionSymbol；`analysis/ExpressionFactsResolver:92-102` 保留参数的已知来源，同时添加未知行为依赖。实际 `udf_score` 来源仍为 `ods.user_feature.score` 和 `ods.user_feature.level_code`，五个目标赋值完整。

实测 native 状态为 SUCCESS，质量为 UNDETERMINED / PARTIAL / UNKNOWN；legacy 状态为成功 0。状态由 `api/DefaultV2LineageAnalyzer:248-255` 根据发布、绑定及提交决定，不根据推导质量重新归类。状态与质量是不同维度，调用方不能只看 SUCCESS 判定来源完整；当前未知 UDF 规范也要求不把已知来源加未知行为变为整条失败。

但原生列表有两条完全相同的 OPAQUE_FUNCTION 诊断。`analysis/FactSupport:212-219` 从关系输出和写入传播的 UnknownEndpoint 汇总时未去重；兼容投影已经合成一条。应按 stage/code/message/source 去重用户诊断，保留所有不同目标的依赖边和证据路径。不能通过删未知依赖来消除差异。

### D-02：case11 是预期 SQL 失败，但错误类别与主消息需修正

`src/test/resources/hql/generated/lineage_case_11.sql:3` 的 `select from` 缺少投影表达式。第一条：V1 与 V2 legacy 均状态 2、类型 UNKNOWN、failureCode SQL_PARSE_ERROR；原生 failureCode 为 SQL_PARSE_FAILED，没有 Bound IR 或血缘。第二条状态成功、正式血缘存在，两个目标字段分别来自 `ods.user_base.user_id` / `user_name`。

定位出的实际问题：

1. `compat/LegacyStatementProjector:359-372` 对旧枚举没有的 HIVE_SYNTAX_ERROR 和 SQL_PARSE_FAILED 走默认 UNKNOWN_COLUMN_LINEAGE。没有血缘产物的语法失败不应归为字段来源未知。可在不修改 V1 枚举的前提下映射为既有 UNSUPPORTED_STATEMENT，并在消息中保留原生错误代码；详细原生诊断继续保留 SYNTAX 阶段。
2. `api/DefaultV2LineageAnalyzer:188-189` 生成通用 StageFailure，`262-266` 将其文本存为 failureMessage；具体 `cannot recognize input near 'from' ... in select clause` 只在诊断中。主消息应优先采用具体 parser 诊断，无具体诊断再回退通用消息。
3. `compare/LegacyStructureDiffer:61-62` 把 failureMessage 变更归为 MAPPING，仅因其不属于少数特判字段。当前三条中这一条不是字段映射差异，应归为 DIAGNOSTIC；不会改变差异数量或自动接受结果。

现有 `LegacyProjectionExecutionTest:244` 检查类型、状态及 failureCode，没有验证 warning 类别和具体主消息；原配置基线也没有把 warning/failureMessage 作为 JUnit 的一致性门禁，完整 differ 才发现它们。因此“测试全部通过”不能证明这里的投影语义正确。

### D-03：case12 保留映射不确定性合理，但双重诊断及区间措辞不准

`src/test/resources/hql/generated/lineage_case_12.sql:3` 的目标未登记到原元数据 fixture。`semantic/StatementBinder:305-313` 明确目标 schema 不可用；`DefaultSinkPlanner:66-78` 使用已选择的 CURRENT_API_TARGET_MAPPING，按查询的 user_id/user_name 推断目标名称。

实测有两个完整 SlotRef 赋值和准确的两个来源字段，mappingCoverage 为 PARTIAL，UnknownMapping 为 UNSUPPORTED_RULE / CURRENT_API_TARGET_MAPPING。native 为 PARTIAL_SUCCESS、质量 UNDETERMINED / PARTIAL / UNKNOWN；legacy 成功 0、失败数为 0。此例无法确认真实目标 schema 和位置，但没有已经发现的缺失列区间。

`DefaultSinkPlanner:103-109` 已报告“命名/推断/前缀映射假设”，`analysis/DefaultLineageEngine:91-94` 再将同一 UnknownMapping 写为“Write field mapping has an unknown interval”。兼容 `LegacyStatementProjector:351` 按 code+message 去重，消息不同故同时保留。建议根据 UnknownMapping 原因区分“目标映射未经 schema 确认”和真实宽度未知；兼容显示在已有具体 binding 诊断时省略其通用包装。未知证据与 PARTIAL 质量仍应保留。

### S-01：五项 MySQL 跳过是集成验证缺口

位置均为 `src/test/java/com/panfan/hivelineage/JdbcMetadataColumnV2ProviderIntegrationTest.java`：

| 方法 | 方法行 / Assume 行 | 尚未实测的能力 |
| --- | --- | --- |
| queriesColumnsFromMetadataColumnV2 | 27 / 31 | JDBC 查询字段及排序 |
| analyzerUsesJdbcMetadataProviderForSelectStar | 43 / 47 | JDBC 元数据驱动星号展开与目标映射 |
| queriesViewSourceFromHiveViewParseInfo | 89 / 93 | 视图类型及合成定义来源 |
| analyzerExpandsJdbcViewSourceFromHiveViewParseInfo | 107 / 111 | JDBC 视图的边界与展开来源 |
| analyzerUsesJdbcMetadataProviderForUnqualifiedJoinColumns | 148 / 152 | JDBC 字段元数据驱动 Join 无限定字段归属 |

三个 Java system properties：lineage.mysql.url、lineage.mysql.user、lineage.mysql.password，任一 null 就在连接与断言前 Assume skip。这不是连不上数据库后被忽略的 SQLException，也不是失败断言被删掉。fixture/mock 测试不能替代实际 JDBC 类型、排序、连接异常及数据库结构的验证。

测试会 DELETE/INSERT metadata_column_v2 和 hive_view_parse_info 的测试行（178、241、259 行开始的 fixture 方法），重跑应使用隔离测试库。配置放在普通 CLI properties 文件里不会自动进入这些 JVM system properties。新增 Python 执行器当前也没有 JVM 参数入口；`-D` 必须传给 Java，不能直接传给 Python，MAVEN_OPTS 只影响 Maven 子进程。JAVA_TOOL_OPTIONS 虽能继承，但 JVM 会回显其原文，不宜用来携带密码并保存日志。

### S-02：oracle 多出的一项跳过是旧 AST 注入接口不适用

原 `HiveViewAndTemporaryLineageTest:489-520` 使用 V1 的 HiveAstParserAdapter 构造器，在遇到 bad_view_marker 时抛错，只断言视图输入边界和 VIEW_EXPANSION_FAILED。`scripts/run-v2-validation.py:49-57` 在临时副本中显式替换这一个方法为 Assume；原 V1 文件仍由全仓测试执行，未改动。

V2 的 `api/V2AuditEvidenceIntegrationTest:77-98` 在独立 SyntaxFrontend 注入同一异常，覆盖原两个业务断言，并检查返回两条结果、失败计数为零。它没有逐字段断言第二条映射；独立 case11 矩阵与原配置基线则验证另一类真实语法失败后的正式输出和完整来源。可用 V2 前端注入重建 oracle fixture 来取消此结构性 skip，无须给生产 V2 添加旧 AST 依赖。

### F-01：预期 SQL 失败、历史测试失败与当前测试结果必须分开

- case11 的 expectedFailureCount 是 1，三模式矩阵和原配置测试都实际执行失败语句与恢复断言，JUnit 通过；它没有被跳过。
- 最后一次全仓：681 / 676 pass / 0 fail / 5 method skip；oracle：200 / 194 pass / 0 fail / 6 method skip；V2：374 / 374 pass / 0 skip。五项 MySQL 在两套验证中重复出现，不能相加为十个不同问题。
- `target/v2-junit-full.log` 的 202 个历史失败全部与 Hadoop shaded InputBootstrapper 缺失 / HiveConf 初始化失败相关；是早期 classpath 问题。`v2-reused-v1-tests.log` 的 91 失败、`v2-reused-v1-tests-latest.log` 的 25 失败以及 API/style 中间日志也是修复前记录。25 个失败方法全部仍存在于当前 oracle 源，没有通过删除或跳过消除；最后 200 项验证已通过可执行断言。文件名 latest 不能替代时间和最终日志识别。
- 本轮再次执行只含 `V2ValidationRunnerTest` 的离线 Maven test，进程在 Surefire 3.2.5 依赖解析阶段退出 1，测试未启动。原始记录为 `target/v2-surefire-diagnostic.log`：surefire-common/api/booter/shared-utils 等尚未缓存，离线无法获取。这是 PluginResolutionException，不是测试断言失败；应在可用的依赖环境补齐后再使用 Maven test。
- 现有 surefire-reports 只有 40 个 V1 报告、307 项、0 failure/error、5 skip；不含本轮 V2 结果。681 项的依据是 test-compile 后直接 JUnitCore 执行器的最终日志，不能用旧 Surefire 报告代替。

### 修复优先级与验证要求

优先修 D-02 的错误分类、具体主失败信息和比较类别，再修 D-03 的假设诊断措辞/包装重复与 D-01 的原生去重。新增测试必须检查 warning 类别、failureMessage 的具体原因、同一来源诊断去重、完整证据图仍保留，以及缺目标 schema 时两个已知映射没有被清空。两项真实集成/接口适配验证分别处理，不能把它们改成假通过，也不能为了追求零差异删除未知证据。


## 2026-10-13：JDBC 连接桩验收

按用户“JVM连接请直接打桩”的要求，新增测试专属 `JdbcMetadataConnectionStub`。它通过 DriverManager 注册实例独占的 `jdbc:lineage-stub:<UUID>` URL，提供 Connection、Statement、PreparedStatement、ResultSet 的内存实现。原 `JdbcMetadataColumnV2Provider`、V1 实现、旧五项测试、SQL fixture 和 POM 均未修改。

执行器选到 `JdbcMetadataColumnV2ProviderIntegrationTest` 或其 V2 oracle 副本时，自动安装连接桩与作用域内的 lineage.mysql.* 属性，使原 Assume 前提满足；原 DELETE/INSERT fixture、Provider 的参数化查询、ResultSet 映射和全部原断言实际执行。结束后恢复原属性、检查关闭计数并注销驱动。日志明确标记 `JDBC mode: in-memory connection stub`，不打印原属性值。

| 验证 | 实际结果 | 日志 |
| --- | --- | --- |
| 全仓、旧原测试及新增桩测试 | 702 项全部通过，0 失败，0 skip | `target/v2-validation-jdbc-stub-all.log` |
| 原 V1 断言使用 V2 入口 | 200 项，199 通过，0 失败，仅 1 项 AST 注入 skip | `target/v2-validation-jdbc-stub-oracle.log` |
| V2 专项，使用 Java 8 编译产物 | 395 项全部通过，0 skip；49 个测试源 | `target/v2-validation-jdbc-stub-release8.log` |
| 原五项 + 执行器作用域验证 | 9 项全部通过，0 skip | `target/v2-jdbc-original-stub.log` |
| 新连接桩 | 8 项通过 | `JdbcMetadataConnectionStubTest`，纳入全仓/V2 套件 |
| 真实 Provider 经 V1/V2 入口 | 2 入口 × 6 方法，12 项全部通过 | `JdbcMetadataProviderStubTest`，纳入全仓/V2 套件 |

全部 54 个 V2 测试/桥接源码通过 javac --release 8。生产源码未新增依赖或修改；原五项 MySQL 测试在本执行器中已不再因连接属性缺失而跳过。原生 JVM/MySQL 驱动协议、认证及真实数据库服务仍由对应环境验证；本次完成的是用户指定的连接桩与应用流程验证。

桩只支持当前 Provider 和原 fixture 的明确 SQL。过滤或排序条件缺失、未知 SQL、错误参数与非法游标操作都会抛 SQLException，不能通过返回假成功掩盖 Provider 问题。已验证：大小写匹配、active NULL/false 过滤、integer_idx/id 排序、失败/NULL 视图排除、modified_time/id 顺序及源码去重；连接/查询的一次性 SQLException 保留原 cause 和 vendor code；V2 映射为 ACCESS_ERROR，批次捕获及重放保持该状态；嵌套 URL/属性作用域隔离，Connection/Statement/ResultSet 正常及异常路径资源关闭。

复现仍使用原验证脚本，无需提供连接参数：

```text
python3 scripts/run-v2-validation.py --suite all --offline
python3 scripts/run-v2-validation.py --suite legacy-oracle --offline
python3 scripts/run-v2-validation.py --suite v2 --offline
```

### 额外观察：V1 多源合成视图的既有局限

新的真实 Provider 桩样例生成 `select user_id, user_name from ods_it.user_base_it union all select user_id, user_name from ods_it.user_other_it`，再消费 `dim_it.v_user_it`。两入口均零失败、sink 字段保留视图边界；V1 的 view-definition 字段仅保留第一个基础来源，V2 对应字段与物理证据保留两个来源。

共同兼容断言使用原五项中的单源视图场景；多源方法对两个参数行都实际断言 Provider 的完整合成 SQL 和资源关闭，V2 行另验证两个物理来源。未把 V1 丢失第二来源的行为自动写成正确 expected，也未修改 V1。此前 D-01 / D-02 / D-03 的诊断待修项继续保留，本次连接桩不改变其结论。
