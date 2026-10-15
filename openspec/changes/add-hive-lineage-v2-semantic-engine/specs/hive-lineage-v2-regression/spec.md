# Hive Lineage V2 Regression

## ADDED Requirements

### Requirement: 分层契约测试与禁止依赖验收

系统 SHALL 独立验证词法、语法、归一化、绑定、IR 校验和血缘推导，SHALL 用架构检查禁止分析阶段依赖 SQL/Token/AST/Provider。端到端 generated 回归不能替代分层契约测试。

#### Scenario: 手工 IR 绕过前端

- **假设** 使用 IrTestBuilder 构造关系、表达式、关联和写入
- **当** 无 SQL/Hive Parser/Provider 地运行校验和血缘
- **那么** 按声明规则得到可验证结果
- **并且** 不通过生产解析入口间接构造这些测试输入

#### Scenario: 分层测试替换输入产物

- **假设** 分别提供 Token fixture、Syntax fixture 和 Bound fixture
- **当** 测试 Parser、Binder 和血缘
- **那么** 各层只依赖其约定输入，错误分别归属 Lexical/Syntax/Normalization/Binding/Verification/Analysis
- **并且** 增加能力不迫使血缘调用前端

#### Scenario: 依赖边界与元数据隔离

- **假设** 检查新增核心包及实际调用
- **当** 验证架构约束
- **那么** ir 不引用 Hive/ANTLR、Provider 或旧 DTO，analysis 不导入 lexical/syntax/dialect/Provider/V1 core
- **并且** Lexer/Parser/Normalizer 无元数据调用，TABLE_ONLY 和 Join-only 的访问约束仍成立

### Requirement: 原 OpenSpec 场景逐项追踪

系统 SHALL 为原两份 OpenSpec 中每个场景建立追踪行，记录机制、目标策略、对应 V1/V2 测试和验收结论。设计承载 SHALL 与实现及验证状态分开。

#### Scenario: 不把旧任务勾选作为覆盖证明

- **假设** tasks.md、规范和现行代码不同步
- **当** 检查覆盖
- **那么** 以场景、代码及测试证据登记实际状态
- **并且** 未支持或仅有设计不计为已通过

#### Scenario: 规范与现行行为不同

- **假设** 变量、星号宽度、Join 显示或编码预期不同
- **当** 登记验收
- **那么** 单独记录来源、默认兼容行为和替代策略或导出
- **并且** 不以修改旧 SQL、删除断言或覆盖快照消除分歧

### Requirement: 原样复用 generated SQL 集

系统 SHALL 直接使用 `src/test/resources/hql/generated/**/*.sql`，UTF-8 读取、递归发现、按稳定相对路径排序，不复制改写 SQL。

#### Scenario: 自动发现当前和后续 SQL

- **假设** 目录当前含 core_lineage_cases.sql 和 lineage_case_01 至 17
- **当** 执行 V2 回归或双运行
- **那么** 当前全部 18 个文件进入样本清单
- **并且** 后续新增 SQL 自动纳入，包括子目录，不能固定上限为 18

#### Scenario: 同名文件和独立 Session

- **假设** 新增子目录同名文件或多个脚本使用同名临时表
- **当** 构建用例身份和运行
- **那么** 相对路径区分用例，各文件使用独立 Session
- **并且** 不丢弃同名文件或跨脚本继承临时对象

### Requirement: 复用现有元数据 fixture

系统 SHALL 在 `v2.testing.LegacyCorpusMetadataFixture` 中维护与既有 generated 基线一致的元数据契约，并通过 V1 Provider 适配层供 V2 使用。测试辅助独立放在 v2 包，不修改既有 v1 测试源码、引擎或断言。

#### Scenario: 同一元数据用于两个引擎

- **假设** generated fixture 包含表和视图定义
- **当** 运行 V1/V2
- **那么** 两边使用相同有序结构及定义快照
- **并且** 同次比对的两个引擎共享同一份 v2 测试元数据快照，与原始基线结构及定义一致，不访问真实数据库

#### Scenario: 不同元数据需求可重放

- **假设** 两个引擎请求的对象或属性不同
- **当** 录制和重放外部读取
- **那么** 依据同一固定清单提供可用事实，记录额外请求及失败
- **并且** 缓存命中差异不被误判为业务血缘差异

### Requirement: 同输入双运行和语义比较

系统 SHALL 使用相同脚本、业务身份、初始数据库、变量、方言和选项比较 V1/V2，独立初始化 Session。比较 SHALL 覆盖结构及结果质量，不仅检查无异常。

#### Scenario: 复用现有 generated 配置

- **假设** 现有配置为 TABLE_AND_COLUMN、视图和临时结果开启、fallback_value
- **当** 进行基线双运行
- **那么** 两边沿用该配置、相同 workflowId/taskId/taskVersion
- **并且** 引擎版本作为独立标签，不改变业务身份

#### Scenario: 差异范围和规范化

- **假设** 两边结果存在顺序或映射差异
- **当** 生成报告
- **那么** 比较语句、输入输出、字段位置、来源、表达式、Join、warning、状态及身份
- **并且** 仅规范化无语义集合顺序，不消除输出列位置和 Join 首次出现顺序

### Requirement: 预期失败与恢复场景

系统 SHALL 保留故意失败样例的原预期，失败计数、失败位置及后续恢复 SHALL 按场景验收。

#### Scenario: lineage_case_11 预期失败

- **假设** 执行 lineage_case_11.sql 的既有配置
- **当** 回归完成
- **那么** 验证一条失败及后续语句成功，保留其对应目标和返回记录
- **并且** 不以全部零失败为验收，也不删除该 SQL

### Requirement: 配置矩阵与定向断言

系统 SHALL 扩展三种模式、Join-only、临时/忽略/视图选项的测试矩阵，并定向验证元数据调用和复杂语义。

#### Scenario: 各能力配置的输出和访问

- **假设** 同一 SQL 在多种能力配置下运行
- **当** 检查结果
- **那么** 不请求的血缘集合为空，按需元数据规则成立
- **并且** Join-only、表模式和缺失 schema 的验证不被字段模式替代

#### Scenario: 历史修复与原子组合

- **假设** 存在别名隔离、限定星号、集合分支、视图和标量子查询历史回归
- **当** 验证 V2
- **那么** 关联这些用例并增加必要原子、组合和合法变换断言
- **并且** 未知传播、对象身份和依赖角色有独立标准，不只断言解析状态

### Requirement: 独立报告快照和人工标准答案

系统 SHALL 保留原 V1 报告和快照，V2/差异输出 SHALL 位于专用目录。正确性标准 SHALL 经过核验，不自动接受当前任一引擎输出。

#### Scenario: 分开输出核验报告

- **假设** 生成 V2 快照或差异
- **当** 写入报告
- **那么** 使用 `target/lineage-output/v2/`，包含文件、语句、SQL 和结果定位
- **并且** 不覆盖 generated/lineage_parse_results.md 或原 Join 快照

#### Scenario: 接受差异与统计

- **假设** 某项差异已确认或仍待判定
- **当** 更新验收结论
- **那么** 分类为兼容、确认改进、回归、不支持或待核验，并显式维护已确认 expected
- **并且** 普通测试不自动刷新标准，未知和失败样本不从覆盖统计分母删除

### Requirement: 确定性并发资源与阶段验收

系统 SHALL 验证固定输入结果可复现、任务状态隔离、并发加载、缓存生命周期及资源限制，V1 功能齐平 SHALL 作为新能力扩展前的里程碑。

#### Scenario: 生命周期和重复运行

- **假设** 改变任务顺序、并发度或重复运行
- **当** 输入与快照相同
- **那么** 各脚本身份、结果和有语义顺序保持稳定，无全局 Parser 或 Session 污染
- **并且** 资源超限和访问失败返回明确诊断

#### Scenario: 齐平之后再扩展

- **假设** 尚未完成已有需求验收
- **当** 规划多动作写入或完整目录推演
- **那么** 优先完成齐平和差异解释，不将原有功能推迟为可选扩展
- **并且** 默认切换、删除 V1 和发布不自动发生
