# Non-Lineage Statement Classification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recognize Hive statements without formal table or column data lineage as ignored results, while preserving the existing failure path for data-moving or transforming statements.

**Architecture:** Keep classification in `HiveScriptLineageAnalyzer` before fallback lineage parsing. The classifier uses a small, explicit set of statement families and rejects `AS SELECT` forms, so query-bearing DDL cannot be swallowed. A Hive-parser validation attempt adds `INVALID_DDL` to ignored malformed DDL without increasing the failure count.

**Tech Stack:** Java 8, Apache Hive `ParseDriver`, JUnit 4, Maven.

---

## File structure

- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java` — classify non-lineage statements, validate classified DDL, and build ignored results.
- Modify: `src/main/java/com/panfan/hivelineage/result/WarningCode.java` — expose `INVALID_DDL` to callers.
- Modify: `src/test/java/com/panfan/hivelineage/HiveScriptProcessingTest.java` — regression tests for ignored categories, malformed DDL, and protected data-bearing statements.
- Modify: `README.md` — document the ignored categories and the explicit non-ignored boundary.

### Task 1: Define the regression contract

**Files:**

- Modify: `src/test/java/com/panfan/hivelineage/HiveScriptProcessingTest.java`

- [ ] **Step 1: Write the failing test for supplied non-lineage SQL**

Add a JUnit test analyzing this script with `includeIgnoredStatements(true)`:

```java
String script = "create temporary function f as 'pkg.F' using jar 'hdfs:///f.jar';"
    + "drop table tmp.old_t;"
    + "truncate table dwd.snapshot_t;"
    + "drop view dwd.old_v;"
    + "create temporary table tmp.staging(dt string);"
    + "create external table if not exists ods.empty_t(dt date);";
```

Assert `failedStatementCount == 0`, `ignoredStatementCount == 6`, every returned statement has status `3`, empty table/column lineage lists, and skip reasons in this order: `CREATE_TEMPORARY_FUNCTION`, `DROP_TABLE`, `TRUNCATE_TABLE`, `DROP_VIEW`, `CREATE_TEMPORARY_TABLE_DDL`, `CREATE_EXTERNAL_TABLE_DDL`.

- [ ] **Step 2: Run the test to verify RED**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn -q -Dtest=HiveScriptProcessingTest test
```

Expected: FAIL because these statements currently reach `UNSUPPORTED_STATEMENT`.

- [ ] **Step 3: Write the failing malformed-DDL test**

Use `"alter table dwd.t set tblproperties（'comment'='bad';"` with `includeIgnoredStatements(true)`. Assert zero failed statements, one result with status `3`, `skipReason == "ALTER_TABLE"`, and exactly one `WarningCode.INVALID_DDL` warning.

- [ ] **Step 4: Run the test to verify RED**

Run the command from Step 2. Expected: FAIL because malformed `ALTER TABLE` currently reports an unsupported parse failure.

- [ ] **Step 5: Write the failing data-bearing boundary test**

With `includeIgnoredStatements(true)`, analyze an `INSERT ... SELECT`, CTAS, and `MERGE`. Assert the first two succeed with formal lineage and assert `MERGE` is not returned as status `3` with a non-lineage skip reason.

- [ ] **Step 6: Run the test to verify RED**

Run the command from Step 2. Expected: new non-lineage expectations fail while data-bearing statements remain outside ignored classification.

### Task 2: Implement the explicit classifier

**Files:**

- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java:132-190,545-593`
- Modify: `src/main/java/com/panfan/hivelineage/result/WarningCode.java`

- [ ] **Step 1: Add the warning code**

```java
    UNSUPPORTED_STATEMENT,
    INVALID_DDL
```

- [ ] **Step 2: Add a private classification value and explicit matcher**

The value contains `statementType`, `skipReason`, and `validateWithHiveParser`. Return it only for function/resource registration, non-query create DDL, `DROP TABLE|VIEW|DATABASE|FUNCTION`, `TRUNCATE TABLE`, `ALTER TABLE`, `MSCK`, `ANALYZE TABLE`, `SHOW`, `DESCRIBE`, `GRANT`, and `REVOKE`.

Use `\\bas\\s+(?:with\\b.*)?select\\b` as the query-bearing-DDL guard and return no classification for CTAS or views with `AS SELECT`.

- [ ] **Step 3: Route classified statements through the ignored-result path**

Immediately after variable resolution and before `analyzeStatement`, obtain the classification. If non-null, validate only when its validation flag is true; increment `ignored`; and append `ignoredResult` only when `includeIgnoredStatements` is enabled. Preserve current `SET`, `USE`, pure query, `INSERT VALUES`, and directory-insert behavior.

- [ ] **Step 4: Add malformed-DDL diagnostics without changing status**

Validate with `new HiveParserAstAdapter().parse(normalized)`. On a parser runtime exception, append:

```java
new LineageWarning(WarningCode.INVALID_DDL, "Ignored DDL is syntactically invalid", context.getStatementIndex())
```

Do not validate session/resource commands, `SHOW`, `DESCRIBE`, `GRANT`, or `REVOKE`.

- [ ] **Step 5: Run the focused test to verify GREEN**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn -q -Dtest=HiveScriptProcessingTest test
```

Expected: all `HiveScriptProcessingTest` tests pass.

- [ ] **Step 6: Commit the implementation and regression tests**

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java src/main/java/com/panfan/hivelineage/result/WarningCode.java src/test/java/com/panfan/hivelineage/HiveScriptProcessingTest.java
git commit -m "feat: ignore non-lineage Hive statements"
```

### Task 3: Document and fully verify

**Files:**

- Modify: `README.md:92-98,132-139`

- [ ] **Step 1: Document categories and boundary**

Document ignored session/resource commands, schema/metadata DDL, metadata inspection, and privilege statements. Explicitly list `INSERT ... SELECT`, CTAS, view-as-query, `MERGE`, `UPDATE`, `DELETE`, `LOAD DATA`, `IMPORT`, and `EXPORT` as not ignored.

- [ ] **Step 2: Document malformed DDL diagnostics**

Document that malformed ignored-DDL families remain `IGNORED` and expose `INVALID_DDL` when ignored detail is requested.

- [ ] **Step 3: Run the full suite**

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn test -q
```

Expected: exit code `0`.

- [ ] **Step 4: Check the final diff**

```bash
git diff --check HEAD~1..HEAD && git status --short
```

Expected: no whitespace errors and only documented working-tree changes.

- [ ] **Step 5: Commit documentation**

```bash
git add README.md docs/superpowers/specs/2026-10-09-non-lineage-statement-classification-design.md docs/superpowers/plans/2026-10-09-non-lineage-statement-classification.md
git commit -m "docs: describe ignored Hive statement handling"
```
