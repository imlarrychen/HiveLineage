# Hive Lineage V2 Compatibility

## ADDED Requirements

### Requirement: 原有功能与选项完整承接

V2 SHALL 承接原 `hive-sql-lineage`、`hive-sql-join-condition` 的场景和既有功能，全部场景 SHALL 在追踪表中有目标与验收。当前尚未实现的旧规范 SHALL 明确登记，不能以 V1 未实现为由从验收删除。

#### Scenario: 使用现有选项

- **假设** 调用方传入三种模式、Join 开关、临时/忽略选项、视图开关和深度、failFast 及兜底值
- **当** 请求转换为 V2 执行契约
- **那么** 每个选项及其默认值均有对应行为
- **并且** 不把按需解析只实现为最后删除输出

#### Scenario: 功能齐平包含复杂已有场景

- **假设** 验收 V1 功能齐平
- **当** 检查实现清单
- **那么** 包含分区、CTE、子查询、集合、窗口、EXPLODE、视图、临时表、独立 Join 和失败恢复
- **并且** 不把这些已有能力列为可省略的后期增强

### Requirement: 公开血缘资格与忽略契约

兼容入口 SHALL 保留正式血缘需要输入和输出的资格规则，纯查询、VALUES 及无血缘命令 SHALL 按既有契约返回或忽略；内部事实不自动成为公开依赖或事件。

#### Scenario: 纯查询和 VALUES 默认忽略

- **假设** 未开启独立结构分析，语句为纯 SELECT 或 INSERT VALUES
- **当** 返回兼容结果
- **那么** 不公开表/字段血缘、查询依赖或 VALUES 写入事件
- **并且** 只有必要 Session 定义处理和计数发生，扩展数据版本不默认推进

#### Scenario: 无血缘 DDL 与数据语句分开

- **假设** 语句属于普通结构 DDL、元数据或资源命令
- **当** 按既有分类处理
- **那么** 保留 IGNORED 和 skipReason，已识别非法 DDL 使用 INVALID_DDL 审计
- **并且** 未支持的 MERGE、UPDATE、DELETE、LOAD、IMPORT、EXPORT 等数据语句不得归为普通忽略

### Requirement: 表字段 Join 的正交能力与元数据访问

系统 SHALL 支持 TABLE_LINEAGE、COLUMN_LINEAGE、JOIN_CONDITION 正交选择，且 SHALL 只加载所需元数据。限定物理字段的 Join-only 分析 SHALL 不为了条件归属加载字段 schema。

#### Scenario: TABLE_ONLY 不请求字段

- **假设** 只选择表血缘、视图关闭、Join 未请求
- **当** 分析 INSERT SELECT
- **那么** 返回表血缘、不返回字段血缘
- **并且** Provider 字段调用为零，不产生字段缺失告警

#### Scenario: COLUMN_ONLY 内部识别表

- **假设** 只选择字段血缘
- **当** 分析来源与目标映射
- **那么** 可内部解析对象并按需读结构
- **并且** 对外 tableLineages 为空

#### Scenario: 仅请求 Join

- **假设** 原生能力仅有 JOIN_CONDITION，SQL 为 SELECT * FROM ods.a a JOIN ods.b b ON a.id=b.id
- **当** 完成分析
- **那么** 返回一条语句及 Join 结果，formalLineageProduced=false
- **并且** 表/字段正式血缘为空，不请求无关投影 schema

### Requirement: V1 元数据 Provider 与缓存适配

系统 SHALL 新增适配器复用原 HiveMetadataProvider，不修改已有 Provider 实现。字段顺序、可用属性、缺失能力及访问异常 SHALL 如实转换。

#### Scenario: 缺少类型和分区属性

- **假设** V1 元数据只有有序字段名、对象类型及视图 SQL
- **当** V2 消费该元数据
- **那么** 已有字段映射仍可分析，缺失属性标记未知
- **并且** 不要求调用方先接入完整 Metastore

#### Scenario: 空返回与访问故障

- **假设** Provider 返回 null 或抛出访问异常
- **当** 适配查询结果
- **那么** null 默认表示元数据未知，异常表示访问失败
- **并且** 仅有明确权威契约时使用 NOT_FOUND，不以未知证明 IF NOT EXISTS 条件

#### Scenario: 打包接口与轻量接口

- **假设** 调用方仅提供 V1 打包元数据接口且开启视图展开
- **当** 查询对象类型或定义
- **那么** 明确记录打包读取能力，不宣称底层未读字段
- **并且** 可选轻量接口能够只请求对象描述，适配不改原 Provider

#### Scenario: 复用缓存和批次固定

- **假设** 多任务使用同一外部对象和指定缓存
- **当** 批次重复读取
- **那么** 通过明确的缓存链复用已固定元数据，并发首次请求合并
- **并且** taskVersion 不进入元数据缓存键，TTL 不改变本批次既定依据

### Requirement: 变量与源码兼容

系统 SHALL 逐语句处理运行时、SET、命名空间和环境占位变量，保留 rawSql、normalizedSql 和内部展开证据。既有统一兜底与规范保留未知规则 SHALL 使用明确策略表达。

#### Scenario: 前序 SET 与兜底来源

- **假设** 前序 SET 更新变量，后续有 ${...} 或 @{...}
- **当** 按当前 API 兼容策略展开
- **那么** 保留现有命名空间引用、优先级及 fallbackVariableValue 行为
- **并且** 不访问真实环境，rawSql 保留原文，内部标明已知值或兜底值

#### Scenario: 规范未知变量策略

- **假设** 显式采用 OpenSpec 保留未知变量策略
- **当** 遇到没有提供值的未知变量
- **那么** 保留原文并返回 UNRESOLVED_VARIABLE
- **并且** 不将该策略与当前 API 统一兜底默认混称为相同预期

#### Scenario: 切分扩展与原始位置

- **假设** 使用注入的预处理逻辑或文本含注释、引号、分号
- **当** 形成脚本单元
- **那么** 核心无需修改即可委托切分
- **并且** 保留原始文本、稳定序号及展开后的定位，边界不可靠时明确诊断

### Requirement: 数据库临时对象和兼容定义

系统 SHALL 保留默认数据库、USE 顺序、临时对象优先级及命名规则，并以独立逻辑目录支持前序纯查询 CTE 来源复用。

#### Scenario: USE 更新及失败恢复

- **假设** 多条 USE、显式库名或未解析 USE 变量出现在脚本中
- **当** 逐句处理
- **那么** 合法 USE 只影响后续语句，显式库名优先
- **并且** 无效或兜底 USE 不提交虚假库名，保持原数据库并返回失败

#### Scenario: 临时表名称与物理来源

- **假设** 脚本创建 source_db.tmp，后续读取 tmp 或 source_db.tmp
- **当** 解析对象
- **那么** 匹配同一 Session 临时对象，兼容名称采用统一临时库
- **并且** 默认穿透来源；开启 includeTemporaryLineage 后保留中间节点和创建结果

#### Scenario: 前序查询 CTE 兼容复用

- **假设** 后续写入使用前序纯查询定义的 CTE 名称
- **当** 按既有兼容契约解析
- **那么** 可从 ScriptDefinitionStore 取得来源定义
- **并且** 本语句 Scope 保持独立，不输出 CTE 物理或临时节点

### Requirement: 字段表达式分区窗口与函数输出

兼容结果 SHALL 保留 DIRECT、DERIVED、CONSTANT、expression、expressionExtracted、functionNames 和 complexExpression，并按既有规则表达静态/动态分区、集合分支和窗口来源。

#### Scenario: 表达式常量和集合

- **假设** 投影包含算术、函数、常量及 UNION 分支
- **当** 转换兼容字段结果
- **那么** 保留正确来源和 constant(...)、union_all(...)、union_distinct(...) 表示
- **并且** 字符串和变量字面量不产生伪字段，无法稳定提取文本不丢已知来源

#### Scenario: 窗口和无源分区函数

- **假设** row_number 使用分区排序字段，动态分区函数没有业务字段参数
- **当** 生成字段记录
- **那么** 窗口按既有口径包含相关来源和真实函数名
- **并且** 无源分区保留赋值表达式但不生成字段到字段边，WHERE 不补作其来源

#### Scenario: 未知 UDF 降级

- **假设** 没有函数专有规则
- **当** 参数字段可识别
- **那么** 尽量保留参数来源和表达式，不终止整条 SQL
- **并且** 内部函数质量未知与旧 warning、状态映射分别处理

### Requirement: 视图边界缓存和诊断

系统 SHALL 依据元数据识别视图，在配置下保留上游到视图、视图到目标的表和字段边界，并复用定义模板，限制深度和检测循环。

#### Scenario: 展开或保留视图边界

- **假设** 元数据提供视图定义，或定义缺失/失败
- **当** 请求视图展开
- **那么** 可展开部分保留原表到视图到目标链路
- **并且** 缺失、失败、超深、循环分别映射既有 VIEW_* warning，不中断整个脚本

#### Scenario: 临时遮蔽和定义上下文

- **假设** Session 临时对象与元数据视图同名，或定义含裸表名
- **当** 解析来源
- **那么** 临时对象优先，定义使用正确数据库和上下文
- **并且** 不复用其他 Session 的绑定实例

### Requirement: 独立 Join 条件完整输出

系统 SHALL 从统一模型独立输出 Join 条件，保留所有查询块、完整条件、直接与物理来源、首次出现顺序和数字类型映射，不产生正式血缘边。

#### Scenario: 多块连续 Join 和直接字段

- **假设** CTE、派生表、主查询、EXISTS 内含多个 Join
- **当** 分析该语句
- **那么** 一个语句结果包含所有 Join，全局 joinIndex 从 1 递增，queryBlockPath 稳定
- **并且** 保存原文本及解析后引用，歧义时保留直接信息和已追溯部分

#### Scenario: 完整 ON 和隐式 WHERE

- **假设** ON 只引用单侧字段，或逗号连接 WHERE 包含跨来源和单表条件
- **当** 输出 Join 条件
- **那么** 单侧 ON 不被丢弃，隐式记录只含跨来源条件
- **并且** 不把外层 EXISTS 相关谓词当作内层显式 Join

#### Scenario: 独立记录身份

- **假设** 下游要求单独消费 Join 条件
- **当** 导出 JoinConditionRecord
- **那么** 记录携带父语句的 workflowId、taskId、taskVersion、statementIndex、statementId 和 rawSql
- **并且** 无需修改旧 JoinCondition DTO，不要求同血缘存储在一张表

### Requirement: 结果身份状态恢复与消费方式

系统 SHALL 保留原请求身份、语句及计数契约，兼容数字状态 0/1/2/3，原始 SQL、失败原因及 warning。引擎版本 SHALL 独立于业务任务版本。

#### Scenario: 业务身份和统计

- **假设** 相同任务版本由两个引擎分析
- **当** 输出兼容结果和报告
- **那么** 业务 taskVersion 相同，statementIndex 从 0 起始且稳定，statementId 延续现有规则
- **并且** 不用引擎 V2 改写任务版本，过滤结果不重新编号，总/忽略/失败数按契约统计

#### Scenario: 失败后继续与停止

- **假设** 当前语句失败且后续边界可恢复
- **当** failFast=false 或 true
- **那么** 默认保留失败结果并继续可分析语句，true 时停止后续推进
- **并且** 不提交失败效果；受不确定状态影响的结果明确降低质量

#### Scenario: 旧格式能力不足

- **假设** V2 完整结果有旧 DTO 无法表达的多目标、角色或未知事实
- **当** 转换旧结果
- **那么** 明确报告转换限制并保留完整 V2 结果
- **并且** 不静默丢弃目标，不直接执行 TDSQL 或图数据库写入
