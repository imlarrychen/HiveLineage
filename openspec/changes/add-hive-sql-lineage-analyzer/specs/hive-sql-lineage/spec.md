# 能力：Hive SQL 血缘解析

## 新增需求

### 需求：技术栈和解析器依赖

解析工具必须以 Java 8 和 Maven 为基础构建，并使用 Hive parser 4.0.1 作为第一版主解析器。

#### 场景：使用 Hive parser adapter 隔离解析依赖

- **假设** 工具模块引入 Hive parser 4.0.1
- **当** 业务血缘逻辑需要解析 SQL
- **那么** 必须通过 parser adapter 获取内部解析模型
- **并且** 业务血缘逻辑不得直接依赖 Hive parser 的具体 AST 类型
- **并且** 后续替换 Hive parser 版本时，应优先修改 adapter 而不是改动血缘生成模型

#### 场景：第一版不实现持久化

- **假设** 解析器完成脚本解析
- **当** 返回解析结果
- **那么** 结果结构必须满足后续 TDSQL 和图数据库消费需要
- **但是** 第一版工具类不直接写 TDSQL
- **并且** 第一版工具类不直接写图数据库

### 需求：脚本级解析入口

解析器必须以 Hive 任务脚本作为主要输入。

#### 场景：解析包含多条语句的任务脚本

- **假设** 请求中包含一个 Hive 任务脚本，且脚本中有多条 SQL 语句
- **并且** 请求中包含 `workflowId`、`taskId` 和 `taskVersion`
- **当** 解析器执行解析
- **那么** 解析器必须按脚本中的语句顺序逐条处理
- **并且** 返回的解析结果必须关联相同的 `workflowId`、`taskId` 和 `taskVersion`
- **并且** 产生血缘、解析失败或需要诊断的 SQL 语句必须返回语句级解析结果
- **并且** 语句级解析结果必须保留变量替换前的原始 SQL

### 需求：语句级结果

解析器必须为产生血缘、解析失败或需要诊断的 SQL 语句返回语句级解析结果。

语句级解析结果是推荐的持久化单元。

#### 场景：成功且不产生血缘的语句默认不返回结果

- **假设** 脚本中包含 `set bizdate=20260629`
- **并且** 该 `set` 语句不产生正式血缘
- **当** 脚本被解析
- **那么** 解析器必须在内部使用该变量更新 Session
- **并且** 默认不返回该 `set` 语句对应的 `StatementLineageResult`
- **并且** 默认不将该 `set` 语句持久化到 TDSQL

#### 场景：开启忽略语句审计后返回被忽略语句

- **假设** 请求配置 `includeIgnoredStatements = true`
- **并且** 脚本中包含 `set bizdate=20260629`
- **当** 脚本被解析
- **那么** 结果中可以包含该 `set` 语句对应的 `StatementLineageResult`
- **并且** 该结果的 `parseStatusCode` 必须为 `3`
- **并且** 该结果的 `skipReason` 必须说明该语句是 Session 变量设置语句

#### 场景：语句结果记录原始 SQL 和标准化 SQL

- **假设** 某条语句中包含 `${hiveconf:bizdate}`
- **并且** 前序 `set` 语句设置了 `bizdate=20260629`
- **当** 脚本被解析
- **那么** 该语句级解析结果的 `rawSql` 必须包含 `${hiveconf:bizdate}`
- **并且** 该语句级解析结果的 `normalizedSql` 必须包含 `20260629`

#### 场景：语句级结果作为持久化单元

- **假设** 一个任务脚本被分割为三条 SQL 语句
- **当** 脚本被解析
- **那么** 解析器必须为产生血缘、解析失败或需要诊断的语句返回 `StatementLineageResult`
- **并且** 每条记录必须包含 `workflowId`、`taskId`、`taskVersion`、`statementIndex` 和 `statementId`
- **并且** 每条记录都可以独立持久化到 TDSQL
- **并且** 写入图数据库的边可以引用同一个 `statementId`

#### 场景：解析失败语句必须返回结果

- **假设** 脚本中某条 SQL 语句解析失败
- **当** 脚本被解析
- **那么** 结果中必须包含该失败语句对应的 `StatementLineageResult`
- **并且** 该结果的 `parseStatusCode` 必须为 `2`
- **并且** 该结果必须包含 `rawSql`
- **并且** 该结果必须包含 `failureCode` 和 `failureMessage`
- **并且** 该结果可以独立持久化到 TDSQL，供后续按失败状态查询分析

#### 场景：脚本级包装对象不是持久化单元

- **假设** 一个任务脚本包含多条 SQL 语句
- **当** 脚本被解析
- **那么** 解析器可以返回一个包含全部语句结果的内存包装对象
- **但是** 该包装对象不是推荐的 TDSQL 持久化单元
- **并且** 解析结果不要求在每条语句结果中保存完整原始任务脚本

### 需求：任务版本隔离

解析器必须把任务版本纳入血缘结果身份标识。

#### 场景：同一任务存在不同版本

- **假设** 两次请求具有相同的 `workflowId` 和 `taskId`
- **并且** 第一次请求的 `taskVersion = V1`
- **并且** 第二次请求的 `taskVersion = V2`
- **当** 两个版本的脚本都被解析
- **那么** 两次解析结果必须能够通过 `taskVersion` 区分
- **并且** 结果身份标识必须包含 `workflowId`、`taskId`、`taskVersion`、`statementIndex`、目标对象，以及适用时的目标字段

### 需求：正式血缘必须同时具备输入和输出

解析器只有在语句同时具备至少一个输入数据对象和一个输出数据对象时，才产生正式数据血缘。

#### 场景：纯查询语句完全忽略

- **假设** 语句为 `select id from ods.user`
- **当** 该语句被解析
- **那么** 不产生表级血缘
- **并且** 不产生字段级血缘
- **并且** 不产生查询依赖信息
- **并且** 不写入图数据库边

#### 场景：Insert Select 产生正式血缘

- **假设** 语句为 `insert overwrite table dwd.user select id from ods.user`
- **当** 该语句被解析
- **那么** 必须产生从 `ods.user` 到 `dwd.user` 的表级血缘
- **并且** 当目标表元数据可用时，必须产生从 `ods.user.id` 到 `dwd.user.id` 的字段级血缘

#### 场景：Insert Values 完全忽略

- **假设** 语句为 `insert overwrite table dwd.user values (1, 'tom')`
- **当** 该语句被解析
- **那么** 不产生表级血缘
- **并且** 不产生字段级血缘
- **并且** 不产生写入事件
- **并且** 不写入图数据库边

### 需求：按需解析血缘类型

解析器必须支持调用方选择只解析表血缘、只解析字段血缘，或同时解析表血缘和字段血缘。

#### 场景：只解析表血缘

- **假设** 请求中的解析模式为 `TABLE_ONLY`
- **并且** 语句为 `insert overwrite table dwd.user select id from ods.user`
- **当** 该语句被解析
- **那么** 必须产生从 `ods.user` 到 `dwd.user` 的表级血缘
- **并且** 不产生字段级血缘
- **并且** 默认不得加载 Hive 表字段元数据
- **并且** 如果开启视图展开，允许加载对象类型元数据来判断输入对象是否为视图

#### 场景：只解析字段血缘

- **假设** 请求中的解析模式为 `COLUMN_ONLY`
- **并且** 语句为 `insert overwrite table dwd.user select id from ods.user`
- **当** 该语句被解析
- **那么** 不返回表级血缘
- **并且** 当源表和目标表元数据可用时，必须产生从 `ods.user.id` 到 `dwd.user.id` 的字段级血缘
- **并且** 解析器可以在内部识别输入表和输出表，用于字段归属解析和正式血缘口径判断

#### 场景：同时解析表血缘和字段血缘

- **假设** 请求中的解析模式为 `TABLE_AND_COLUMN`
- **并且** 语句为 `insert overwrite table dwd.user select id from ods.user`
- **当** 该语句被解析
- **那么** 必须产生从 `ods.user` 到 `dwd.user` 的表级血缘
- **并且** 当源表和目标表元数据可用时，必须产生从 `ods.user.id` 到 `dwd.user.id` 的字段级血缘

#### 场景：表血缘解析不依赖字段元数据

- **假设** 请求中的解析模式为 `TABLE_ONLY`
- **并且** 元数据服务无法返回 `ods.user` 的字段列表
- **当** 语句 `insert overwrite table dwd.user select id from ods.user` 被解析
- **那么** 解析器仍然必须产生从 `ods.user` 到 `dwd.user` 的表级血缘
- **并且** 不得因为字段元数据缺失而返回字段血缘相关 warning

### 需求：字段血缘表达式解析

解析器在解析字段血缘时，必须识别目标字段依赖的源字段集合，并判断字段血缘类型。表达式文本也必须尽量提取；当 parser 无法稳定提取表达式文本时，不得阻断字段血缘解析，但必须通过 `expressionExtracted = false` 标记。

#### 场景：计算表达式字段血缘

- **假设** 测试 SQL 为：

```sql
insert into test_a(a1, a2)
select b1 + b2, b3
from test_b;
```

- **并且** 请求中的解析模式包含字段血缘解析
- **当** 该语句被解析
- **那么** `test_a.a1` 的源字段必须包含 `test_b.b1` 和 `test_b.b2`
- **并且** `test_a.a1` 的血缘类型必须为 `DERIVED`
- **并且** `test_a.a2` 的源字段必须包含 `test_b.b3`
- **并且** `test_a.a2` 的血缘类型必须为 `DIRECT`
- **并且** 解析器必须尝试提取 `test_a.a1` 的表达式
- **并且** 如果表达式提取成功，则 `test_a.a1` 的表达式应记录为 `b1 + b2`，且 `expressionExtracted = true`

#### 场景：函数表达式字段血缘

- **假设** 测试 SQL 为：

```sql
insert overwrite table test_a
select nvl(b1, 0) as a1
from test_b;
```

- **当** 字段血缘被解析
- **那么** `a1` 的源字段必须包含 `b1`
- **并且** `a1` 的血缘类型必须为 `DERIVED`
- **并且** 解析器必须尝试提取 `a1` 的函数名和表达式
- **并且** 如果函数名提取成功，则 `a1` 的函数名集合应包含 `nvl`
- **并且** 如果表达式提取成功，则 `a1` 的表达式应记录为 `nvl(b1, 0)`，且 `expressionExtracted = true`

#### 场景：常量字段血缘

- **假设** 测试 SQL 为：

```sql
insert overwrite table test_a(a1)
select 't1' as a1
from test_b;
```

- **当** 字段血缘被解析
- **那么** `test_a.a1` 的源字段集合必须为空
- **并且** `test_a.a1` 的表达式必须记录为 `constant('t1')`
- **并且** `test_a.a1` 的血缘类型必须为 `CONSTANT`
- **并且** 字符串常量不得被解析为 `test_b.t1` 或其他伪来源字段

#### 场景：静态分区和动态分区字段对齐

- **假设** 测试 SQL 为：

```sql
insert overwrite table dwd.user_tag_detail partition(source_system='profile', dt)
select user_id, tag, dt
from ods.user_base;
```

- **并且** 目标表字段包含 `user_id`、`tag`、`source_system`、`dt`
- **当** 字段血缘被解析
- **那么** `dwd.user_tag_detail.user_id` 必须来自 `ods.user_base.user_id`
- **并且** `dwd.user_tag_detail.tag` 必须来自 `ods.user_base.tag` 或可追溯到生成 `tag` 的表达式来源
- **并且** `dwd.user_tag_detail.dt` 必须来自 `ods.user_base.dt`
- **并且** `dwd.user_tag_detail.source_system` 的源字段集合必须为空
- **并且** `dwd.user_tag_detail.source_system` 的表达式必须为 `constant('profile')`
- **并且** `dwd.user_tag_detail.source_system` 的血缘类型必须为 `CONSTANT`

#### 场景：Union 分支字段表达式

- **假设** 测试 SQL 为：

```sql
insert overwrite table test_a(a1)
select table1.a1 from table1
union all
select table2.a2 from table2;
```

- **当** 字段血缘被解析
- **那么** `test_a.a1` 的源字段必须包含 `table1.a1` 和 `table2.a2`
- **并且** `test_a.a1` 的表达式必须记录为 `union_all(table1.a1, table2.a2)`
- **并且** `test_a.a1` 的血缘类型必须为 `DERIVED`

#### 场景：Union 分支常量表达式

- **假设** 测试 SQL 为：

```sql
insert overwrite table test_a(type_name)
select 'A' as type_name from table1
union all
select 'B' as type_name from table2;
```

- **当** 字段血缘被解析
- **那么** `test_a.type_name` 的源字段集合必须为空
- **并且** `test_a.type_name` 的表达式必须记录为 `union_all(constant('A'), constant('B'))`
- **并且** `test_a.type_name` 的血缘类型必须为 `CONSTANT`

#### 场景：聚合表达式字段血缘

- **假设** 测试 SQL 为：

```sql
insert overwrite table test_a
select user_id, sum(amount) as total_amount
from test_b
group by user_id;
```

- **当** 字段血缘被解析
- **那么** `total_amount` 的源字段必须包含 `amount`
- **并且** `total_amount` 的血缘类型必须为 `DERIVED`
- **并且** 解析器必须尝试提取 `total_amount` 的函数名和表达式
- **并且** 如果函数名提取成功，则 `total_amount` 的函数名集合应包含 `sum`
- **并且** 如果表达式提取成功，则 `total_amount` 的表达式应记录为 `sum(amount)`，且 `expressionExtracted = true`

#### 场景：未知 UDF 降级

- **假设** 测试 SQL 为：

```sql
insert overwrite table test_a
select my_udf(b1, b2) as a1
from test_b;
```

- **并且** 解析器没有该 UDF 的专用语义规则
- **当** 字段血缘被解析
- **那么** `a1` 的源字段必须尽量提取为 `b1` 和 `b2`
- **并且** 解析器不得因为未知 UDF 中断整条语句解析
- **并且** 解析器必须尝试提取 `a1` 的表达式
- **并且** 如果表达式提取成功，则 `a1` 的表达式应记录为 `my_udf(b1, b2)`，且 `expressionExtracted = true`
- **并且** 如果无法可靠识别参数字段，必须返回 `UNKNOWN_COLUMN_LINEAGE` warning

#### 场景：表达式无法稳定提取时不阻断字段血缘

- **假设** 测试 SQL 为：

```sql
insert overwrite table test_a
select complex_udf(named_struct('k', b1), b2) as a1
from test_b;
```

- **并且** parser 无法稳定返回 `a1` 对应的表达式文本
- **当** 字段血缘被解析
- **那么** `a1` 的源字段仍必须尽量包含可识别的 `b1` 和 `b2`
- **并且** `a1` 的血缘类型必须为 `DERIVED`
- **并且** `expressionExtracted` 必须为 `false`

### 需求：Session 级 Set 变量

解析器必须把前序 `set` 语句设置的变量应用到同一任务脚本的后续语句中。

#### 场景：Set 变量被后续 Insert 使用

- **假设** 脚本以 `set bizdate=20260629` 开头
- **并且** 后续 Insert 语句中包含 `${hiveconf:bizdate}`
- **当** 脚本被解析
- **那么** 后续 Insert 语句必须以 `${hiveconf:bizdate}` 被解析为 `20260629` 后的 SQL 进行解析

#### 场景：Hivevar 命名空间变量被后续语句使用

- **假设** 脚本中包含 `SET hivevar:VARI = 'xx'`
- **并且** 后续语句中包含 `${hivevar:VARI}`
- **当** 脚本被解析
- **那么** 后续语句的 `normalizedSql` 必须把 `${hivevar:VARI}` 替换为 `xx`
- **并且** 后续语句的 `rawSql` 必须保留 `${hivevar:VARI}` 原文

### 需求：变量兜底

解析器必须在运行时变量和 Session 变量都无法解析时，对已知内置变量使用兜底值。

#### 场景：内置日期变量未解析

- **假设** 某条语句包含 `${bizdate}`
- **并且** 运行时变量和 Session 变量都没有提供 `bizdate`
- **当** 该语句被解析
- **那么** `${bizdate}` 必须被替换为配置的兜底值
- **并且** `normalizedSql` 必须记录替换后的值

#### 场景：未知变量无法解析

- **假设** 某条语句包含 `${unknown_var}`
- **并且** 运行时变量、Session 变量和兜底变量都没有提供 `unknown_var`
- **当** 该语句被解析
- **那么** `normalizedSql` 中必须保留该变量原文
- **并且** 必须返回 `UNRESOLVED_VARIABLE` warning

#### 场景：环境变量兜底替换

- **假设** 某条语句包含 `@{env_name}`
- **当** 该语句被解析
- **那么** 解析器不查询真实运行环境
- **并且** `normalizedSql` 必须使用配置的兜底值替换 `@{env_name}`
- **并且** `rawSql` 必须保留 `@{env_name}` 原文

### 需求：元数据缓存

解析器必须缓存 Hive 表元数据，以降低批量工作流解析期间对 Hive Metastore 的访问压力。

字段血缘解析依赖 Hive 表字段元数据；表血缘解析默认不依赖字段元数据。

#### 场景：元数据接口先使用 Mock 实现

- **假设** 第一版解析器运行单元测试
- **当** 字段血缘、视图判断或视图展开需要表结构
- **那么** 测试必须通过 mock metadata provider 返回表结构、对象类型和视图 SQL
- **并且** 解析器必须只依赖 `MetadataProvider` 接口，不依赖具体 MySQL 查询实现

#### 场景：生产元数据可由 Metastore MySQL 适配

- **假设** 生产环境 Hive Metastore 元数据存储在 MySQL
- **当** 后续接入生产元数据查询
- **那么** 可以通过实现 `MetadataProvider` 读取 Metastore MySQL 表
- **并且** 该实现必须能返回表字段、分区字段、对象类型和视图定义 SQL

#### 场景：同一张表出现在多个任务中

- **假设** 同一个工作流中的多个任务都读取 `ods.user`
- **当** 该工作流被解析
- **那么** 在缓存为空时，`ods.user` 的元数据只从底层元数据服务加载一次
- **并且** 缓存有效期内的后续访问必须使用缓存元数据

#### 场景：任务版本不影响元数据缓存

- **假设** `task_a` 的 `V1` 和 `V2` 都读取 `ods.user`
- **当** 两个版本都被解析
- **那么** 元数据缓存 key 不得包含任务版本
- **并且** 表结构必须被视为与任务版本无关
- **并且** 血缘结果身份标识仍然必须包含任务版本

#### 场景：只解析表血缘不触发字段元数据加载

- **假设** 请求中的解析模式为 `TABLE_ONLY`
- **当** 解析器解析可产生表血缘的语句
- **那么** 解析器不得为了字段血缘解析去加载 Hive 表字段元数据
- **并且** 元数据缓存中不得因为该语句新增字段元数据项
- **但是** 如果开启视图展开，解析器可以加载对象类型元数据来判断输入对象是否为视图

### 需求：临时表血缘展开

解析器必须在同一任务 Session 内串联临时表血缘。

#### 场景：临时表作为最终目标的上游

- **假设** 脚本先基于 `ods.user` 创建临时表 `tmp_user`
- **并且** 后续语句基于 `tmp_user` 写入 `dwd.user_profile`
- **当** 脚本被解析
- **那么** 最终表级血缘必须展开为从 `ods.user` 到 `dwd.user_profile`
- **并且** 最终字段级血缘必须把 `tmp_user` 字段展开到其上游 `ods.user` 字段

### 需求：视图血缘展开

解析器必须识别输入对象是否为 Hive 视图，并在配置允许时把视图血缘展开到真实上游对象。

视图判断必须来自元数据服务，不能依赖 SQL 文本、对象命名规则或解析器猜测。

#### 场景：通过元数据判断输入对象是否为视图

- **假设** SQL 中读取对象名为 `dim.v_user`
- **并且** `dim.v_user` 不在当前 Session 临时表中
- **并且** 元数据服务返回该对象的 `objectType = VIEW`
- **当** 解析器处理该输入对象
- **那么** 解析器必须把 `dim.v_user` 识别为视图
- **并且** 必须读取该对象的视图定义 SQL 用于后续血缘展开

#### 场景：临时表优先于 Hive 元数据

- **假设** SQL 中读取对象名为 `tmp_user`
- **并且** 当前 Session 中存在同名临时表
- **当** 解析器处理该输入对象
- **那么** 解析器必须按 Session 临时表处理
- **并且** 不得优先使用 Hive Metastore 中同名表或视图的对象类型

#### 场景：读取视图时展开到真实上游表

- **假设** `dim.v_user` 是一个视图
- **并且** 视图定义为 `select id, name from ods.user`
- **并且** 语句为 `insert overwrite table dwd.user_profile select id, name from dim.v_user`
- **并且** 请求配置 `expandViewLineage = true`
- **当** 该语句被解析
- **那么** 表级血缘必须展开为从 `ods.user` 到 `dwd.user_profile`
- **并且** 字段级血缘必须展开为从 `ods.user.id` 到 `dwd.user_profile.id`
- **并且** 字段级血缘必须展开为从 `ods.user.name` 到 `dwd.user_profile.name`

#### 场景：重复读取同一视图时复用展开缓存

- **假设** 同一个任务脚本或同一个工作流中多次读取 `dim.v_user`
- **并且** `dim.v_user` 的视图定义 SQL 未变化
- **当** 第一次读取该视图时，解析器已经完成视图展开
- **那么** 后续读取同一视图时必须优先复用缓存的视图展开结果
- **并且** 不应重复解析同一份视图定义 SQL

#### 场景：视图定义缺失时降级

- **假设** SQL 读取对象 `dim.v_user` 是视图
- **并且** 元数据服务无法返回该视图的定义 SQL
- **当** 字段血缘被解析
- **那么** 解析器必须返回 `VIEW_METADATA_NOT_FOUND` warning
- **并且** 不得因为该视图定义缺失中断整个脚本解析

#### 场景：视图递归展开深度限制

- **假设** 视图之间存在多层嵌套
- **并且** 嵌套层数超过 `maxViewExpandDepth`
- **当** 解析器展开视图血缘
- **那么** 解析器必须停止继续展开
- **并且** 必须返回 `VIEW_EXPANSION_DEPTH_EXCEEDED` warning

#### 场景：视图循环依赖

- **假设** 视图展开过程中检测到循环依赖
- **当** 解析器展开视图血缘
- **那么** 解析器必须停止继续展开
- **并且** 必须返回 `VIEW_EXPANSION_CYCLE` warning

### 需求：注释处理和语句分割扩展点

解析器必须定义注释移除和 SQL 语句分割的扩展点。

#### 场景：复用已有预处理逻辑

- **假设** 项目中已经存在注释移除和 SQL 分割逻辑
- **当** 集成该解析器
- **那么** 解析器必须能够通过 `ScriptPreprocessor` 委托给已有逻辑
- **并且** 不需要修改解析器核心逻辑

### 需求：Warning 降级

解析器必须对可恢复的解析问题、元数据问题或血缘问题返回 warning。

#### 场景：元数据缺失

- **假设** 某条语句引用的表无法找到元数据
- **当** 该语句被解析
- **那么** 解析器必须返回 `METADATA_NOT_FOUND` warning
- **并且** 必须保留不依赖字段元数据即可推断出的表级血缘
