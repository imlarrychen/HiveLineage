# 剩余代码质量问题修复设计

## 背景与目标

补充质量报告发现 3 个阻断问题：

- `HiveAstExpressionExtractor.isBinaryOperator` 的 boolean 表达式复杂度为 14，限制为 4。
- `HiveAstProjectionExtractor.isLiteralNode` 的 boolean 表达式复杂度为 6，限制为 4。
- `JdbcMetadataColumnV2Provider.getTableMetadata` 中 `try-with-resources` 嵌套两层，最多允许嵌套一层。

本次仅修复这 3 个问题并将 try 嵌套规则加入项目质量规格，不扩大到报告之外的重构。

## 方案

### 操作符判断

`HiveAstExpressionExtractor` 保留当前字符串比较方式，把二元操作符按语义拆成三个私有谓词：

- 算术操作符：`+`、`-`、`*`、`/`、`%`。
- 比较操作符：顶层比较谓词再委托给等值谓词（`=`、`==`、`<>`、`!=`）和顺序谓词（`<`、`<=`、`>`、`>=`）。
- 逻辑操作符：`and`、`or`，保持大小写不敏感。

顶层 `isBinaryOperator` 只组合算术、比较、逻辑三个语义谓词；比较谓词继续组合两个更小的谓词。
每个 boolean 表达式复杂度均不超过 4，操作符集合和匹配语义保持不变。

### 字面量 token 判断

`HiveAstProjectionExtractor` 把字面量 token 分为：

- 字符串与数值 token。
- boolean token。
- null token。

`isLiteralNode` 组合三个命名谓词，不引入集合、装箱或新的状态，保持 Hive Parser token 判断结果不变。

### JDBC 资源管理

`JdbcMetadataColumnV2Provider.getTableMetadata` 仅管理 `Connection`，将列查询提取到私有方法。
列查询方法管理 `PreparedStatement`，并保留一层 `ResultSet` 资源块。这样既满足 try 最多嵌套一层，
又保持列查询的 ResultSet、PreparedStatement 在视图查询开始前关闭；SQL 参数设置、SQLException 包装和返回逻辑均不改变。

规则文档新增 CQ-11：`try` 或 `try-with-resources` 最多嵌套一层；同一生命周期资源优先合并声明，不同职责优先提取方法。

## 测试与验收

- 修改前的结构扫描必须稳定报告复杂度 14、复杂度 6 和两层 try 嵌套。
- 修改后对上述三个文件重新扫描，不再报告对应问题。
- 运行 AST、字段血缘和 JDBC 元数据相关测试。
- 运行完整 Maven 测试，要求 0 失败、0 错误。
- 保持 Java 8 兼容，不引入依赖，不修改公开 API，也不修改 `release/`。
