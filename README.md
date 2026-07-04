# Hive SQL Lineage Analyzer

这个项目是一个 Java 8 + Maven 的 Hive SQL 血缘解析工具。它面向工作流场景：一个工作流包含多个 Hive 任务，一个 Hive 任务脚本中可能包含多条 Hive SQL 语句。工具会按脚本维度解析 SQL，并按单条语句输出解析结果，用于后续落 TDSQL 或写入图数据库。

项目当前重点支持：

- 表血缘解析：识别输入表、输出表、语句类型和临时表链路。
- 字段血缘解析：识别目标字段、来源字段、表达式、直接/加工/常量类型。
- 按需解析：支持只解析表血缘、只解析字段血缘、同时解析两者。
- 元数据缓存：字段血缘依赖 Hive 表字段元数据，内置缓存减少元数据库访问。
- 变量处理：支持运行时变量、`set` 会话变量、残留 `${xxx}` 和 `@{xxx}` 兜底替换。
- 视图展开：可通过元数据判断对象是否是视图，并递归解析视图定义 SQL。
- 解析状态：成功、失败、忽略语句都使用数值枚举状态。
- 原始 SQL 保留：每个有返回结果的语句都会保留 `rawSql` 和变量替换后的 `normalizedSql`。

## 技术栈

- Java 8
- Maven
- Hive Parser: `org.apache.hive:hive-exec:4.0.1`
- MySQL Connector/J: `8.0.33`
- JUnit: `4.13.2`

## 代码入口

主入口接口：

- [HiveLineageAnalyzer.java](src/main/java/com/panfan/hivelineage/core/HiveLineageAnalyzer.java)

默认实现：

- [HiveScriptLineageAnalyzer.java](src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java)

当前主代码按职责拆成四个包：

- `com.panfan.hivelineage.core`：核心分析器、Hive Parser AST 适配器、显式 AST 遍历器、表达式提取器、启发式兜底解析器。
- `com.panfan.hivelineage.request`：解析请求、解析选项、解析模式。
- `com.panfan.hivelineage.result`：解析返回实体、表/字段血缘实体、状态枚举和 warning。
- `com.panfan.hivelineage.metadata`：元数据接口、缓存、表标识、JDBC 元数据实现。

AST 相关代码入口：

- [HiveParserAstAdapter.java](src/main/java/com/panfan/hivelineage/core/HiveParserAstAdapter.java)：调用 Hive Parser 生成 `ASTNode`，并组织表、字段、别名、分区等信息提取。
- [HiveAstWalker.java](src/main/java/com/panfan/hivelineage/core/HiveAstWalker.java)：集中封装语法树遍历能力，包括查找第一个指定 token、查找直接子节点、收集指定 token。
- [HiveAstExpressionExtractor.java](src/main/java/com/panfan/hivelineage/core/HiveAstExpressionExtractor.java)：递归处理表达式节点，提取字段引用并尽量还原表达式文本。

典型调用方式：

```java
import com.panfan.hivelineage.core.HiveLineageAnalyzer;
import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageAnalyzeResult;

HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

LineageAnalyzeResult result = analyzer.analyzeScript(LineageAnalyzeRequest.builder()
    .workflowId("wf_001")
    .taskId("task_001")
    .taskVersion("V1")
    .defaultDatabase("default")
    .rawScript("insert into test_a(a1, a2) select b1 + b2, b3 from test_b;")
    .options(LineageAnalyzeOptions.builder()
        .parseMode(LineageParseMode.TABLE_AND_COLUMN)
        .build())
    .build());
```

核心入参：

- `workflowId`：工作流 ID。
- `taskId`：任务 ID。
- `taskVersion`：任务版本，例如 `V1`、`V2`、`V3`。
- `clusterName`：集群名，默认 `default`。
- `defaultDatabase`：默认库名，用于补全未带库名的表。
- `rawScript`：一段 Hive SQL 脚本，可以包含多条语句。
- `runtimeVariables`：运行时已经传入的变量。
- `options`：解析选项。
- `metadataProvider`：元数据查询接口。
- `metadataCache`：元数据缓存实例。

## 解析选项

解析模式由 `LineageParseMode` 控制：

- `TABLE_ONLY`：只解析表血缘，一般不查询字段元数据。
- `COLUMN_ONLY`：只解析字段血缘，需要时查询字段元数据。
- `TABLE_AND_COLUMN`：同时解析表血缘和字段血缘。

其他重要选项：

- `includeTemporaryLineage`：是否返回临时表创建语句本身的血缘结果。默认不返回，但会用它串联后续血缘。
- `includeIgnoredStatements`：是否返回 `set`、纯查询、无输出表等被忽略语句的解析结果。默认不返回。
- `expandViewLineage`：是否展开视图血缘。
- `maxViewExpandDepth`：视图递归展开最大深度，默认 `5`。
- `fallbackVariableValue`：残留变量兜底替换值，默认 `__HIVE_LINEAGE_DEFAULT__`。

## 输出结果

顶层结果是 `LineageAnalyzeResult`：

- `workflowId`
- `taskId`
- `taskVersion`
- `statements`：语句级解析结果列表。
- `totalStatementCount`：脚本拆分后的语句总数。
- `ignoredStatementCount`：被忽略语句数量。
- `failedStatementCount`：解析失败语句数量。
- `warnings`：所有语句 warning 汇总。

单条语句结果是 `StatementLineageResult`。它是一条 SQL 对应一个解析结果，主要字段包括：

- `statementIndex`：语句在脚本中的序号。
- `statementId`：由 `workflowId_taskId_taskVersion_statementIndex` 组成。
- `statementType`：如 `INSERT_OVERWRITE`、`CREATE_TABLE_AS_SELECT`、`CREATE_VIEW_AS_SELECT`。
- `rawSql`：原始单条 SQL。
- `normalizedSql`：变量替换后的 SQL。
- `parseStatusCode`：解析状态数值。
- `formalLineageProduced`：是否产生正式血缘。
- `outputObject`：输出对象。
- `inputObjects`：输入对象。
- `tableLineages`：表血缘。
- `columnLineages`：字段血缘。
- `warnings`：语句级 warning。

解析状态枚举：

- `0 SUCCESS`：成功解析并处理。
- `1 PARTIAL_SUCCESS`：预留状态，表示部分成功。
- `2 FAILED`：解析失败。
- `3 IGNORED`：语句已处理但不产生正式血缘。

字段血缘类型：

- `DIRECT`：直接字段来源，例如 `select b3 from test_b`。
- `DERIVED`：字段经过函数或表达式加工，例如 `select b1 + b2`、`select upper(name)`。
- `CONSTANT`：字段由常量赋值得到，例如 `select 't1' as a1` 或静态分区 `partition(dt='20260701')`。

字段血缘表达式口径：

- 普通常量字段不产生源字段，表达式统一记录为 `constant(...)`。例如 `select 't1' as a1` 输出 `sourceColumns = []`、`expression = constant('t1')`、`lineageType = CONSTANT`。
- 静态分区字段按常量赋值保留在字段血缘结果中，但不产生字段到字段来源。例如 `partition(source_system='profile', dt)` 中 `source_system` 输出为 `constant('profile')`，动态分区 `dt` 则继续对齐 `select` 中的 `dt` 字段。
- `union all`/集合操作按分支聚合表达式。字段分支示例：`union_all(table1.a1, table2.a2)`；常量分支示例：`union_all(constant('A'), constant('B'))`。
- 字符串字面量、变量兜底后的字面量、日期格式串等不应被识别为来源字段。例如 `'fallback_value'`、`'yyyy/MM/dd'` 只作为表达式内容，不进入 `sourceColumns`。

## 元数据查询与缓存

字段血缘解析需要知道表中有哪些字段，尤其是：

- `select *`
- `insert into target select ...` 未显式声明目标字段时
- 多表 join 中未带表别名的字段归属判断
- 视图展开和视图字段映射

元数据接口：

- [HiveMetadataProvider.java](src/main/java/com/panfan/hivelineage/metadata/HiveMetadataProvider.java)

缓存实现：

- [HiveMetadataCache.java](src/main/java/com/panfan/hivelineage/metadata/HiveMetadataCache.java)

MySQL 元数据实现：

- [JdbcMetadataColumnV2Provider.java](src/main/java/com/panfan/hivelineage/metadata/JdbcMetadataColumnV2Provider.java)

当前 JDBC provider 查询 `metadata_column_v2` 获取字段：

- 库字段：`db_en_name`
- 表字段：`tb_en_name`
- 字段名：`column_name`
- 排序字段：`integer_idx, id`
- 有效标识：`is_active`

同时查询 `hive_view_parse_info` 判断视图：

- 视图名字段：`view_name`，兼容 `库名.表名` 和单独表名。
- 上游来源字段：`view_source`，格式为 `库名.表名`。
- 失败标识：`failed_status`，过滤 `b'1'` 的解析失败记录。

如果命中视图来源，provider 会返回 `HiveTableMetadata.view(...)`，并用视图字段和 `view_source` 构造可解析的视图 SQL，例如 `select col1, col2 from ods.source_table`，供 `expandViewLineage` 复用。

使用示例：

```java
import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.metadata.JdbcMetadataColumnV2Provider;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageAnalyzeResult;

HiveMetadataProvider provider = new JdbcMetadataColumnV2Provider(
    "jdbc:mysql://127.0.0.1:3306/hive",
    "root",
    "85418825"
);

LineageAnalyzeResult result = new HiveScriptLineageAnalyzer().analyzeScript(
    LineageAnalyzeRequest.builder()
        .workflowId("wf_001")
        .taskId("task_001")
        .taskVersion("V1")
        .defaultDatabase("default")
        .metadataProvider(provider)
        .metadataCache(new HiveMetadataCache())
        .rawScript("insert overwrite table dwd.user_profile select * from ods.user_base;")
        .options(LineageAnalyzeOptions.builder()
            .parseMode(LineageParseMode.TABLE_AND_COLUMN)
            .build())
        .build()
);
```

## 当前支持的 SQL 口径

会产生正式血缘的典型语句：

- `insert into/overwrite table ... select ...`
- `create table ... as select ...`
- `create temporary table ... as select ...`
- `create view ... as select ...`
- `alter view ... as select ...`

默认不返回正式血缘的语句：

- `set ...`
- `use ...`
- 纯查询 `select ...`
- `insert overwrite directory ...`
- `insert ... values (...)`
- 普通建表 DDL

临时表处理：

- `create temporary table tmp as select ...` 会记录在当前脚本会话中。
- 后续语句引用临时表时，会把最终目标表血缘串联到临时表的真实来源表。
- 默认不返回临时表创建语句本身的结果，除非开启 `includeTemporaryLineage`。

分区字段处理：

- 静态分区字段不从 `select` 列取来源字段，按常量字段血缘输出，例如 `partition(source_system='profile')` 输出 `source_system <- constant('profile')`。
- 动态分区字段参与 `select` 列对齐，例如 `partition(source_system='profile', dt) select user_id, tag, dt ...` 中，`dt` 对齐 `select` 的第三列，`source_system` 作为静态常量单独输出。

集合操作处理：

- `union all` 会按字段位置合并多个分支的来源字段。
- 字段表达式使用 `union_all(...)` 表示分支聚合，例如 `union_all(ods.a.id, ods.b.id)`。
- 如果分支是常量，使用 `constant(...)` 包裹，例如 `union_all(constant('A'), constant('B'))`。

视图处理：

- 如果开启 `expandViewLineage`，解析器会通过 `HiveMetadataProvider` 判断输入对象是否是视图。
- JDBC provider 会查询 `hive_view_parse_info`，通过 `view_name` 判断视图，并使用 `view_source` 生成视图展开 SQL。
- 如果是视图，并且元数据中存在或可生成视图 SQL，则继续解析视图 SQL。
- 如果视图定义缺失、解析失败、循环依赖或超过最大展开深度，会保留可推断血缘并返回 warning。

## 变量处理

支持三类变量：

- SQL 前置 `set` 变量，例如 `set hivevar:VARI='xx'; select ... ${hivevar:VARI}`。
- 运行时变量，例如 `${bizdate}`，可通过 `runtimeVariables` 传入。
- 环境变量，例如 `@{bizdate}`，残留时用兜底常量替换。

变量替换发生在单条语句解析前。`set` 语句只影响同一段脚本后续语句。

## 测试

运行全部本地测试：

```bash
bash scripts/run-tests.sh
```

如果使用 JDK 9 及以上版本运行 Maven 测试，`pom.xml` 会自动给 Surefire 增加下面的 JVM 参数，用于兼容 Hive/Hadoop 依赖中的反射访问：

```bash
--add-opens=java.base/java.net=ALL-UNNAMED
```

如果在 IDE 中直接运行 [HiveLineageAnalyzerTest.java](src/test/java/com/panfan/hivelineage/HiveLineageAnalyzerTest.java) 的 `main` 方法，也需要在该运行配置的 VM options 中手动加入同样参数。否则可能出现 `StringInternUtils` 访问 `java.net.URI.string` 被 Java 模块系统拒绝的异常。

该命令会运行：

- [HiveLineageAnalyzerTest.java](src/test/java/com/panfan/hivelineage/HiveLineageAnalyzerTest.java)
- [GeneratedSqlTestSetTest.java](src/test/java/com/panfan/hivelineage/GeneratedSqlTestSetTest.java)
- [JdbcMetadataColumnV2ProviderIntegrationTest.java](src/test/java/com/panfan/hivelineage/JdbcMetadataColumnV2ProviderIntegrationTest.java)

当前本地测试覆盖：

- 表达式字段血缘：`b1 + b2 -> a1`、`b3 -> a2`。
- `set` 会话变量传递。
- 被忽略语句是否默认不返回、开启后是否返回。
- `TABLE_ONLY`、`COLUMN_ONLY`、`TABLE_AND_COLUMN` 三种解析模式。
- 解析失败语句的数值状态和原始 SQL 保留。
- 元数据缓存命中、缺失缓存、TTL 过期。
- `select *` 依赖元数据展开字段。
- join 别名解析和未限定字段归属判断。
- CTAS、临时表、视图、CTE、子查询别名。
- 动态分区和静态分区字段血缘。
- 注释、字符串中分号的脚本拆分。
- `insert overwrite directory` 等无输出表语句忽略。
- 缺失元数据、缺失视图定义的 warning。
- 视图递归展开深度超限的 `VIEW_EXPANSION_DEPTH_EXCEEDED` warning。
- `alter view ... as select ...`。
- `lateral view explode(tags) ... as tag` 来源字段回溯。
- `src/test/resources/hql/generated` 下生成 SQL 测试集的批量接入。
- JDBC provider 表名安全校验。

真实 MySQL 集成测试默认跳过。传入连接参数后会实际连接本机 MySQL：

```bash
mvn -q \
  -Dtest=JdbcMetadataColumnV2ProviderIntegrationTest \
  -Dlineage.mysql.url=jdbc:mysql://127.0.0.1:3306/hive \
  -Dlineage.mysql.user=root \
  -Dlineage.mysql.password=85418825 \
  test
```

集成测试会向 `metadata_column_v2` 和 `hive_view_parse_info` 插入测试数据，并验证：

- `JdbcMetadataColumnV2Provider` 能按库表查出字段。
- 分析器能通过 JDBC 元数据解析 `select *` 字段血缘。
- `JdbcMetadataColumnV2Provider` 能通过 `hive_view_parse_info` 判断视图并生成视图 SQL。
- 分析器能通过 JDBC provider 把视图输入展开到 `view_source` 指向的底层表。

## SQL 样例

仓库中保留了用户提供的生产风格样例：

- [samplingHql01.txt](samplingHql01.txt)
- [samplingHql02.txt](samplingHql02.txt)

自动生成的核心测试样例：

- [core_lineage_cases.sql](src/test/resources/hql/generated/core_lineage_cases.sql)
- [lineage_case_01.sql](src/test/resources/hql/generated/lineage_case_01.sql) 到 [lineage_case_10.sql](src/test/resources/hql/generated/lineage_case_10.sql)
- [lineage_case_metadata.md](src/test/resources/hql/generated/lineage_case_metadata.md)
- [lineage_parse_results.md](src/test/resources/hql/generated/lineage_parse_results.md)

[GeneratedSqlTestSetTest.java](src/test/java/com/panfan/hivelineage/GeneratedSqlTestSetTest.java) 会扫描 `src/test/resources/hql/generated` 下的 `.sql` 文件并逐个调用 `HiveScriptLineageAnalyzer`，断言生成测试集不出现解析失败。后续新增 SQL 测试集时，只要放到该目录即可被纳入这条测试链路。

`lineage_parse_results.md` 用于人工核验，按 SQL 文件分节记录测试 SQL 和对应解析结果。修改解析逻辑或测试 SQL 后，可以先编译测试类，再运行生成器刷新该文件：

```bash
mvn -q -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=/tmp/hive-lineage-cp.txt
java -cp "target/test-classes:target/classes:$(cat /tmp/hive-lineage-cp.txt)" com.panfan.hivelineage.GeneratedSqlLineageSnapshot
```

如果使用 JDK 9 及以上版本直接运行 `java` 命令，需要额外加入 `--add-opens=java.base/java.net=ALL-UNNAMED`。

## OpenSpec

设计和需求说明位于：

- [proposal.md](openspec/changes/add-hive-sql-lineage-analyzer/proposal.md)
- [design.md](openspec/changes/add-hive-sql-lineage-analyzer/design.md)
- [spec.md](openspec/changes/add-hive-sql-lineage-analyzer/specs/hive-sql-lineage/spec.md)
- [tasks.md](openspec/changes/add-hive-sql-lineage-analyzer/tasks.md)

其中 `spec.md` 已使用中文描述。

## 已知边界

- SQL 拆分和注释处理当前是项目内简化实现，后续可以替换为现有生产逻辑。
- Hive Parser 解析失败时会退回启发式解析，保证批量脚本解析尽量不中断。
- 字段表达式会尽量保留，但复杂 SQL 场景下准确率仍依赖 Hive Parser AST 和元数据完整性。
- 视图展开依赖元数据中能返回对象类型和可解析的视图 SQL；当前 JDBC provider 通过 `hive_view_parse_info.view_source` 生成简单视图 SQL，复杂视图定义如果需要完全还原仍建议接入 Hive Metastore 原始视图 SQL。
