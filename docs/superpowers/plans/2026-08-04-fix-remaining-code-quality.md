# Remaining Java Code Quality Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复补充报告中的两个 boolean 复杂度问题和一个 try 嵌套问题，保持现有解析及元数据行为不变。

**Architecture:** 保留现有方法入口，将枚举式条件按语义拆成私有谓词；将 JDBC 列查询提取到私有方法，使每个方法内的资源嵌套不超过一层且保持原资源生命周期。使用一次性 AST 结构扫描作为红绿测试，并由现有行为测试保护重构。

**Tech Stack:** Java 8、Maven、JUnit 4、Hive Parser 4.0.1、JDBC。

---

### Task 1: 复现三个结构问题

**Files:**
- Verify: `src/main/java/com/panfan/hivelineage/core/HiveAstExpressionExtractor.java`
- Verify: `src/main/java/com/panfan/hivelineage/core/HiveAstProjectionExtractor.java`
- Verify: `src/main/java/com/panfan/hivelineage/metadata/JdbcMetadataColumnV2Provider.java`

- [x] **Step 1: 运行 boolean 复杂度扫描**

Run:

```bash
java -cp '/tmp:/Library/Java/JavaVirtualMachines/jdk1.8.0_261.jdk/Contents/Home/lib/tools.jar' \
  QualityScan src/main/java/com/panfan/hivelineage/core/HiveAstExpressionExtractor.java
java -cp '/tmp:/Library/Java/JavaVirtualMachines/jdk1.8.0_261.jdk/Contents/Home/lib/tools.jar' \
  QualityScan src/main/java/com/panfan/hivelineage/core/HiveAstProjectionExtractor.java
```

Expected: 分别报告 boolean complexity 14 和 boolean complexity 6。

- [x] **Step 2: 运行 try 嵌套结构扫描**

Run:

```bash
java -cp '/tmp:/Library/Java/JavaVirtualMachines/jdk1.8.0_261.jdk/Contents/Home/lib/tools.jar' \
  QualityScan src/main/java/com/panfan/hivelineage/metadata/JdbcMetadataColumnV2Provider.java
```

Expected: 报告 `try nesting 2`。

### Task 2: 拆分 AST 操作符和字面量判断

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveAstExpressionExtractor.java:110-119`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveAstProjectionExtractor.java:320-329`
- Test: `src/test/java/com/panfan/hivelineage/HiveParserAstAdapterTest.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java`

- [x] **Step 1: 运行行为基线**

Run:

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
  -Dtest=HiveParserAstAdapterTest,HiveColumnLineageTest test
```

Expected: PASS。

- [x] **Step 2: 拆分二元操作符谓词**

实现以下结构，操作符集合与原实现一致：

```java
private boolean isBinaryOperator(String text) {
    return isArithmeticOperator(text)
        || isComparisonOperator(text)
        || isLogicalOperator(text);
}

private boolean isArithmeticOperator(String text) {
    return "+".equals(text) || "-".equals(text) || "*".equals(text)
        || "/".equals(text) || "%".equals(text);
}

private boolean isComparisonOperator(String text) {
    return isEqualityOperator(text) || isOrderingOperator(text);
}
```

`isEqualityOperator` 判断 `=`、`==`、`<>`、`!=`；`isOrderingOperator` 判断 `<`、`<=`、`>`、`>=`；
`isLogicalOperator` 保持 `and`、`or` 大小写不敏感。

- [x] **Step 3: 拆分字面量 token 谓词**

实现以下结构：

```java
private boolean isLiteralNode(ASTNode node) {
    int type = node.getType();
    return isTextOrNumericLiteral(type) || isBooleanLiteral(type) || type == HiveParser.KW_NULL;
}
```

`isTextOrNumericLiteral` 覆盖 StringLiteral、Number、NumberLiteral、IntegralLiteral；
`isBooleanLiteral` 覆盖 KW_TRUE、KW_FALSE。

- [x] **Step 4: 运行结构扫描确认转绿**

Run: Task 1 Step 1 的两个 QualityScan 命令。

Expected: 两个文件均无输出。

- [x] **Step 5: 运行定向行为测试**

Run: Task 2 Step 1 的 Maven 命令。

Expected: PASS。

### Task 3: 展平 JDBC 资源声明

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/metadata/JdbcMetadataColumnV2Provider.java:44-73`
- Test: `src/test/java/com/panfan/hivelineage/JdbcMetadataColumnV2ProviderIntegrationTest.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveMetadataLineageTest.java`

- [x] **Step 1: 提取列查询资源管理方法**

`getTableMetadata` 仅管理 `Connection`，列查询委托给私有方法；私有方法管理 `PreparedStatement`，
`ResultSet` 保持唯一一层嵌套：

```java
try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
    List<String> columns = queryColumns(connection, sql, tableIdentifier);
    // 原有 view 查询及返回逻辑保持原顺序。
}

private List<String> queryColumns(...) throws SQLException {
    List<String> columns = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
    statement.setString(1, tableIdentifier.getDatabaseName());
    statement.setString(2, tableIdentifier.getTableName());
    try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
            columns.add(resultSet.getString("column_name"));
        }
    }
    }
    return columns;
}
```

列查询方法返回前关闭 ResultSet 和 PreparedStatement，确保视图查询开始时列查询 Statement 已关闭。

- [x] **Step 2: 运行元数据测试**

Run:

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn \
  -Dtest=HiveMetadataLineageTest,JdbcMetadataColumnV2ProviderIntegrationTest test
```

Expected: 单元测试通过；无数据库环境时 5 个集成测试保持 skipped，不出现失败或错误。

- [x] **Step 3: 运行结构扫描确认转绿**

Run: Task 1 Step 2 的 QualityScan 命令。

Expected: 文件无输出。

### Task 4: 完整验证与合并准备

**Files:**
- Verify: all modified files

- [x] **Step 1: 运行完整测试**

Run:

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn test
```

Expected: 0 failures，0 errors。

- [x] **Step 2: 检查结构规则与变更边界**

Run:

```bash
git diff --check
git status --short
```

Expected: 无空白错误；变更仅包含设计/计划、规则文档和三个报告文件，不包含 `release/`。

- [x] **Step 3: 请求独立代码评审**

评审重点：操作符/token 覆盖集合是否完整、JDBC 关闭顺序和 SQLException 包装是否保持、是否仍存在对应静态报告。
