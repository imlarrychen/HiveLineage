# Design: 词法、语法、语义绑定与血缘推导解耦

## 1. 目标与硬边界

本专项已获批准，接口、不可变模型、Binder、Verifier、独立词法/语法与纯 IR 血缘算法均已实现。模型索引见 [接口与模型交付](contracts.md)，实现和实际验收见 [实现说明](implementation.md)、[验证记录](validation.md)。

词法只回答“有哪些 Token”，语法只回答“这些 Token 构成什么结构”，Binder 回答“结构中的名称和输出绑定到什么身份”，血缘规则回答“这些已绑定身份如何参与值、条件和行集推导”。各阶段拥有独立输入输出、诊断、验证和测试。

Bound IR 使用类型化表达式、关系和效果图。不得把 V1 的 ParsedHiveStatement、SELECT 字符串、别名 Map、已抽取来源集合换个名字当作 IR；不得在分析中继续扫描 SQL 补来源。

复用范围为外部 Provider、输入输出契约、fixture 和报告能力。V1 的词法抽取、投影推断和血缘展开不进入内核。

## 2. 数据流与阶段职责

```text
原始脚本
    ↓ 脚本单元与当前变量展开
ExpandedUnit + SourceMap
    ↓ SqlLexer
LexedUnit
    ↓ SqlSyntaxParser
DialectParseArtifact（方言私有）
    ↓ SyntaxNormalizer
SyntaxStatement（自有 Syntax IR）
    ↓ SemanticBinder + SinkPlanner + EffectBinder
BoundAnalysisUnit + BindingCoverage
    ↓ StructuralVerifier + BindingVerifier
VerifiedAnalysisUnit
    ↓ LineageEngine / JoinConditionAnalysis
LineageFacts / JoinFacts
    ↓ 公开结果投影、文本渲染、Session 效果提交
既有结果 / 完整 V2 结果 / 导出记录
```

| 阶段 | 输入 → 输出 | 允许的职责 | 禁止职责 |
|---|---|---|---|
| 脚本前端 | 脚本位置、当前变量 → ExpandedUnit | 单元边界、命令、逐句展开、位置映射 | 业务 SQL 执行、物理来源推断 |
| Lexer | ExpandedUnit、词法配置 → LexedUnit | 标识符、关键字、字面量、操作符、注释和位置 | 查表、解别名、判断字段来源 |
| Parser | LexedUnit、语法配置 → 方言语法树 | 语法结构、优先级、查询块和语法诊断 | 查询 Provider、展开星号、追溯 CTE |
| Normalizer | 方言语法树 → SyntaxStatement | 转为自有结构，保留语法差别和位置 | 绑定字段、创建 ColumnLineage、按 SQL 模板造来源 |
| Binder | SyntaxStatement、BindingContext、Demand → BoundAnalysisUnit | 名称、作用域、Slot、必要类型、输出结构、函数分类 | 血缘集合计算、结果格式决定、提交 Session |
| Sink/Effect 绑定 | 已绑定查询及目标、效果规则 → BoundWrite/BoundEffect | 写入赋值、分区范围、待提交目录/配置效果 | 血缘传播、实际执行效果 |
| Verifier | BoundAnalysisUnit、Demand → VerifiedAnalysisUnit | 结构/引用/输出/绑定覆盖校验 | 补扫 SQL、代替 Hive 完整执行校验 |
| 血缘与 Join 分析 | VerifiedAnalysisUnit、规则 → Facts | 已绑定引用传播、角色、行集和证据 | SQL/Token/AST 解析、名称重绑定、Provider 调用 |
| 编排与投影 | Facts、效果、公开契约 → 结果与本地状态 | 选择公开结果、渲染、按验证效果提交 | 从文本重新推导来源 |

## 3. 词法与语法独立接口

建议接口：

```java
interface SqlLexer {
    LexResult tokenize(ExpandedUnit unit, LexicalOptions options);
}

interface SqlSyntaxParser<P> {
    ParseResult<P> parse(LexedUnit unit, SyntaxOptions options);
}

interface SyntaxNormalizer<P> {
    SyntaxResult normalize(P parseArtifact, SyntaxOptions options);
}

interface SemanticBinder {
    BindingResult bind(SyntaxStatement syntax,
                       BindingContext context,
                       BindingDemand demand);
}

interface IrVerifier {
    VerificationResult verify(BoundAnalysisUnit unit,
                              BindingDemand demand);
}

interface LineageEngine {
    LineageFacts analyze(VerifiedAnalysisUnit unit,
                         LineageRuleRegistry rules);
}
```

这里 LexResult、ParseResult 等均是建议的自有结果类型，不是承诺现成 Hive 同名类具有这些契约。方言工厂通过相同 P 类型组合 SqlSyntaxParser<P> 和 SyntaxNormalizer<P>，对外 Facade 返回 SyntaxResult；P 不进入 semantic/ir/analysis 包。

LexToken 保留稳定位置、词法种类、原 lexeme、可选解码值、通道和方言符号身份；Trivia 保留空白、注释。大小写识别视图不能改写原始文本。语法节点保留 Token/Source 范围，但词法符号不是字段身份。

### Hive 复用的实际边界

Hive 4.0.1 的 ParseDriver.parse(String, Configuration) 内部创建 GenericHiveLexer、TokenRewriteStream 和 HiveParser。它是词法/语法组合入口，不能仅在外面包两层方法就声称两个阶段已独立。[Hive 4.0.1 ParseDriver 源码](https://raw.githubusercontent.com/apache/hive/rel/release-4.0.1/parser/src/java/org/apache/hadoop/hive/ql/parse/ParseDriver.java)

V2 在 dialect.hive 内封装 Lexer 和 Parser：词法形成独立 Token 产物，Parser 消费该产物；同次分析复用已完成的词法结果，不再次调用字符串入口重复分词。Hive/ANTLR Token 的构造、符号码和语法树适配留在方言包，通过私有 Token 桥实现。方言之外只有自有模型，没有 Hive AST、ANTLR 类型或 TOK 数字常量。

默认 Token 桥根据 LexToken 的方言符号身份、lexeme、通道和位置还原该版本 Parser 所需 Token；ANTLR 整数符号码在 Hive adapter 内映射。产物为不可变 buffer，每次 Parser 创建独立 cursor/TokenStream，不把可变索引当作可共享词法产物。

若技术验证后为减少转换保留原生 Token buffer，则它只能位于方言私有的词法产物中，必须与自有 LexedUnit 显式关联并传入；泛型接口由方言工厂组合，其他模块不接触原生类型。两种实现都必须满足显式输入、无重新分词、无全局 Token 状态、可独立测试，不使用隐藏注册表或共享游标。

脚本单元切分与展开后 SQL 分词分开：切分识别字符串、注释和入口分隔规则，不能依赖“整脚本展开后的最终值”。一次单元确认后，使用当前变量展开，再分词解析；未知变量改变边界时明确诊断。

## 4. Syntax IR：只保存语法结构

```text
SyntaxStatement
├── QuerySyntax
│   ├── CteDefinitions[]
│   ├── FromSyntax
│   ├── SelectItems[]
│   ├── Where / GroupBy / Having
│   ├── Window / OrderBy / Limit
│   └── SetOperation / SubquerySyntax
├── InsertSyntax
│   ├── TargetName / TargetColumns[] / PartitionSpec
│   └── QuerySyntax
└── Command / Definition / OtherStatementSyntax
```

表达式使用 NameRef、Literal、Call、Case、Cast、FieldAccess、Subscript、Subquery；限定名称是 IdentifierPart 列表，星号是独立语法节点。JOIN 保留形式和 ON 结构，逗号来源保留其语法身份，ALL/DISTINCT 明确区分。

Normalizer 不先猜测名称归属，不把所有查询块摊平成全局 Map，不把 SQL 文本顺序误当语义求值顺序。语法树只带结构、位置和字面量，不带物理字段依赖集合。

## 5. Bound IR：自有、类型化、不可变

建议包划分：

```text
v2.ir.identity    NodeId / RelationInstanceId / SlotId / ObjectId
v2.ir.expression 已绑定表达式、关联引用、未知节点
v2.ir.relation   关系节点及输出契约
v2.ir.statement  查询、写入、定义和 Session 效果
v2.ir.quality    BindingCoverage / SchemaState / 诊断引用
v2.semantic      Binder / Scope / 类型 / 函数 / Sink 绑定
v2.analysis      LineageEngine / JoinConditionAnalysis
```

身份由稳定语法节点、声明位置和局部序号分配，不取决于请求能力或分析遍历顺序。数据版本和实例化 Session 身份另有明确上下文；同一次输入的三种能力结果可对齐。

### 5.1 分析单元

```text
BoundAnalysisUnit
  statementId             语义语句身份
  relationGraph           关系节点及嵌套查询区域
  queryResults[]          已请求查询输出
  boundWrites[]           目标与赋值已绑定的写入
  boundEffects[]          计划中的目录/Session 效果
  bindingCoverage         各区域完成到何种绑定阶段
  diagnostics[]           未知/歧义/非法的结构化诊断
```

它不包含 sourceColumns 等血缘结果；血缘分析生成独立 Facts。IR 可以构造、序列化、校验和测试；没有 Provider 回调、Parser、可变 Scope 或 SQL 解析辅助对象。

### 5.2 关系与输出

| 节点 | 关键结构与输出 |
|---|---|
| Scan | ObjectId、数据版本、RelationInstanceId、SchemaState、已知有序字段或符号字段身份 |
| Project | 输入及有序 OutputAssignment(SlotId, Expression) |
| Filter | 输入和已绑定谓词；按规则映射输入输出身份 |
| Join | 左右输入、类型、已绑定 ON、输出映射；semi/anti 不直接输出右侧字段 |
| Aggregate | 分组表达式、聚合表达式和有序输出；HAVING 保留独立过滤语义 |
| SetOp | 类型、ALL/DISTINCT、有序分支及按位置输出映射 |
| Window | 输入、参数、partition/order/frame 与新增输出 |
| Generate | 输入、多输出函数、各生成 Slot、OUTER 属性 |
| Sort / Limit / Distinct | 绑定排序键、范围和行集语义，不能被归一化时丢弃 |
| DefinitionInstance | 当前视图或逻辑定义的实例及固定输出边界 |

所有节点有 NodeId 和有序 OutputSchema。名称可以重复，输出不能通过 Map<String,Column> 唯一化。可共享子图；递归定义在 Binder 阶段检测，不生成无限图。

### 5.3 表达式与字段引用状态

```text
Literal(value, type)
SlotRef(slotId, fieldPath)
CorrelatedSlotRef(outerScopeId, slotId, fieldPath)
QualifiedFieldHandle(relationInstanceId, fieldIdentity, path, existenceState)
Call(functionSymbol, arguments[])
Case(branches[], elseValue)
Cast(expression, targetType)
FieldAccess / Subscript
ScalarSubquery / ExistsSubquery / InSubquery
UnknownReference / UnknownExpression
```

已取得 schema 的引用绑定到 SlotRef。仅对象归属已知的物理 `a.id` 可以成为 QualifiedFieldHandle：owner 已确定，字段名是 Binder 分配的结构化身份，existence=UNVERIFIED；这满足 Join-only 不读 schema，不宣称验证了字段存在或类型。

派生表或 CTE 输出不能通过拼接物理表名伪装为 QualifiedFieldHandle。其引用需要对应输出 Slot，或带原因的 UnknownReference。Unknown 可保存候选身份和诊断，不允许血缘引擎拿原始名称再次绑定。

UnknownExpression 保留可证明的已绑定子表达式及未知项；并不等同 Literal 或空依赖。未知函数有明确 OpaqueFunctionSymbol 和参数，不加载 UDF 类。

### 5.4 写入与语句效果

```text
BoundWrite
  targetObject / beforeVersion / afterVersion
  writeMode
  assignments[]           targetFieldIdentity ← boundExpression
  partitionScope         static / dynamic / unknown
  condition
  mappingCoverage

BoundEffect
  effectKind
  boundObjectOrSessionKey
  precondition
  proposedChange
  certainty
```

Binder 解析目标身份、结构和规则，SinkPlanner 在血缘分析前形成位置与分区赋值。血缘引擎只传播 assignments 的来源，不读取目标元数据或猜目标列。效果由编排层应用，IR 节点本身不能修改 Session。

beforeVersion/afterVersion 在没有请求数据版本推演时使用显式 VersionState.NOT_REQUESTED，不能因兼容请求没有版本而拒绝普通写入绑定。

## 6. 渐进绑定不破坏阶段解耦

BindingDemand 指定 OBJECTS、CONDITION_FIELDS、OUTPUT_SCHEMA、SINK_MAPPING 等要求。BindingResult 返回不可变 BoundAnalysisUnit 和覆盖状态，不通过分析对象持有一个“需要时再解析 SQL”的闭包。

| 需求 | 验证后的可分析事实 | 不要求的部分 |
|---|---|---|
| TABLE_ONLY | 对象及关系来源、按契约需要的定义实例 | 全量投影 Slot、目标列宽度 |
| JOIN_CONDITION | Join 类型、谓词、引用 owner、请求的追溯路径 | 无关 SELECT 星号和 Sink |
| COLUMN_LINEAGE | 输出 Slot、表达式、必要上游输出、目标赋值或明确未知 | 无关完整 Hive 编译检查 |

未请求区域保存 NotRequestedRegion(id, kind)，不嵌入 Syntax IR、AST 或 Token。原始语法由 Binder/编排层的独立产物库保存；后续新增需求由编排层调用 Binder，得到新的分析单元，再校验分析。

需要的字段结构缺失时使用 UnknownOutputSegment/UnknownMapping，保存已知前缀或后缀及不确定边界；不能造一列 `*`，不能强行编号未知宽度后继续精确位置映射。

“已绑定”表示当前需求范围内的 owner、身份和语义已解析，或未知已明确建模，不表示整条 SQL 的所有字段都已验证。VerifiedAnalysisUnit 带能力覆盖证书；分析器只能消费已验证的请求范围，新增范围返回 NEEDS_BINDING，由编排层处理，不能在血缘引擎内回调 Binder。

## 7. Verifier 的不变量

StructuralVerifier 校验：

- NodeId 唯一，Slot 定义唯一，子图引用存在，图可终止。SlotRef 及透明算子输出映射可以引用同一 Slot 定义，不能把合法复用误判为重复定义。
- 每个算子的输入输出遵守其声明，输出有序，不按重复名称合并。
- SetOp、Window、Generate、Join 和写入位置映射一致；未知宽度显式存在。
- IR 不包含原始名称待解析节点、Hive AST/Token 或隐式外部服务对象。

BindingVerifier 校验：

- Slot 引用对当前区域可达，关联引用显式且作用域合法。
- QualifiedFieldHandle 的关系 owner 确定，未验证存在性如实保留。
- 请求范围内无未处理 Star 或 UnresolvedName；无法完成的部分只能是显式 Unknown。
- NOT_REQUESTED、UNKNOWN、KNOWN 不混用，效果与映射的确定性一致。
- 按能力验证，不以未请求输出检查阻断表级或 Join-only。

Verifier 验证自有模型和声明口径，不代替 Hive 全部执行合法性、权限或运行时检查。

## 8. 血缘与源码附件的边界

LineageEngine 只接收 VerifiedAnalysisUnit 和不可变规则注册表。基本规则从 Slot 引用和关系输入传播，按 VALUE、CONDITION、ROWSET 等角色构建证据。表血缘从对象/行集事实生成，字段结果从输出和 BoundWrite 赋值生成。

JoinConditionAnalysis 读取已绑定 Join/谓词标注、QueryBlockId、SourceOrder 和引用身份，不再次寻找 JOIN/WHERE 关键字。逗号连接的跨来源条件由结构与绑定 owner 判定，相关谓词有明确 CorrelationRef，不误当内层 Join。

SourceRef、SourceMap、LexicalReference 等附件用于定位、还原表达式和独立导出，不参与业务来源计算。需要显示原文时由外部 SourceRenderer 根据引用取得；无附件时表达式可标为未提取，血缘仍可分析。

可替换前端只需生成相同 Syntax IR。可替换 Binder 只需生成遵守契约的 Bound IR。现有节点已能表达的新函数或依赖规则主要扩展局部规则；新增 SQL 语法允许修改方言前端，新关系种类允许版本化扩展 IR。不为保持接口表面不变而将新语义塞入任意 Map 或字符串，脚本主循环与生命周期仍按稳定契约工作。

### 外部定义的解析入口

视图 SQL、SOURCE 文本和其他外部定义也必须经过相同前端。Binder 不解析元数据返回的定义字符串：BindingContext 提供已准备的 SyntaxTemplate / BoundDefinition 和 DefinitionRef；没有模板时 Binder 返回 NEEDS_DEFINITION(ref, definitionContext, demand)。

编排层的 DefinitionCoordinator 获取受控定义文本，按定义上下文分词、解析和归一化，缓存不可变 SyntaxTemplate 后构造新 BindingContext，再请求 Binder 递归绑定。CTE 已属于本语句 Syntax IR，前序兼容 CTE 和临时对象也保存明确模板或绑定定义，不另开 SQL 血缘解析入口。

定义循环、最大深度、语法错误和缺失上下文由协调/绑定阶段报告，不进入血缘引擎重新试解析。模板缓存不共享任务绑定状态；需要固定创建时结构或数据版本的定义保留其上下文，不能用最新 schema 偷换依据。

## 9. 一个端到端示例

```sql
INSERT INTO dwd.target(id, amount)
SELECT a.id, a.price * a.qty
FROM ods.orders a
WHERE a.status = 'paid';
```

词法阶段识别名称、乘号、字符串等 Token，不能决定 a 指哪张表。Syntax IR 保存目标列、投影表达式、From 名称和 Where 结构，不能提前生成字段来源。

已知元数据下，Binder 生成：

```text
Scan R1: ods.orders@entry
  slots: S1=id, S2=price, S3=qty, S4=status
Filter R2(input=R1)
  predicate: eq(SlotRef(S4), Literal('paid'))
  outputMapping: R1 slots → R2 slots
Project R3(input=R2)
  P1 ← 对应 R2 的 id SlotRef
  P2 ← multiply(对应 R2 的 price SlotRef, qty SlotRef)
BoundWrite dwd.target
  target.id ← SlotRef(P1)
  target.amount ← SlotRef(P2)
```

血缘阶段推导 id 值来源、price/qty 加工来源、status 条件/行集影响。结果投影按既有契约返回字段边，过滤来源保持独立。即使不提供 SQL 文本，只提供上述有效 IR，依赖仍然相同。

TABLE_ONLY 可只绑定扫描对象、关系结构和目标对象，不加载这组字段 schema。Join-only 对类似限定谓词使用明确 field handle，不强制读取整个 SELECT 输出。

## 10. 独立验证与实施门槛

1. Lexer 测 Token、引号、转义、注释、大小写和位置；Parser 测优先级、查询块和语法错误，均不接 Provider。
2. Normalizer 用固定语法树验证 Syntax IR，禁止来源推断；Binder 用手工 Syntax IR 和内存元数据验证 Scope、Slot、星号、函数和目标映射。
3. 先提供 IrTestBuilder，手工构造关系/表达式/写入，验证 Verifier 和血缘，不需要 SQL、Hive Parser 或 Provider。
4. 对同一 IR 替换显示名称、原文附件或 Parser 出处，来源结论不变；合法别名变换绑定后得到可比较的语义结构。
5. 架构检查禁止 analysis 导入 lexical/syntax/dialect、Provider 和 V1 core；禁止 ir 导入 AST、ANTLR、Provider 和旧 DTO。
6. 同一输入不同能力的稳定身份、未请求范围和错误归属有测试。Lexical、Syntax、Normalization、Binding、Verification、Analysis 诊断不能混成一个 SQL_PARSE_ERROR。
7. generated SQL 集验证端到端兼容与准确性；不能以其全部运行无异常替代各阶段契约检查。
8. 对读取视图、SOURCE 和兼容定义的路径执行依赖检查：Binder 返回定义需求，编排前端准备模板，不能在 Binder/Lineage 内调用 Parser。验证 Slot 定义唯一与合法引用复用，以及未请求数据版本的写入。

算法已按 IR 推导规则独立测试 → Binder → 语法归一化 → Lexer/Parser 接入 → 原 SQL 双运行的顺序接通。阶段依赖仍由契约测试约束；原有模型测试继续保留。
