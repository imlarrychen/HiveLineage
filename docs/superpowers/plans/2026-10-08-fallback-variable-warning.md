# Fallback Variable Warning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Suppress variable warnings when `${...}` or `@{...}` is successfully replaced with the configured fallback value.

**Architecture:** Keep raw-SQL preservation and fallback substitution in `HiveScriptPreprocessor`. Remove warning creation from fallback substitution so diagnostics only report analysis of final normalized SQL. Add regression tests at the analyzer boundary.

**Tech Stack:** Java 8, JUnit 4, Apache Hive parser, Maven.

---

### Task 1: Specify fallback behavior with tests

**Files:**
- Modify: `src/test/java/com/panfan/hivelineage/HiveScriptProcessingTest.java:159-204`

- [ ] **Step 1: Write a failing test for both fallback syntaxes**

Add a test named `fallbackVariableReplacementDoesNotProduceWarnings` after `unresolvedVariablesAreReplacedByFallbackConstant`. Build a request containing valid SQL with both `dt = '${missing_bizdate}'` and `env = '@{missing_env}'`, use `.fallbackVariableValue("fallback_value")`, then assert all of the following:

```java
TestSupport.assertEquals(0, result.getFailedStatementCount(), "valid fallback statement succeeds");
TestSupport.assertTrue(statement.getNormalizedSql().contains("fallback_value"), "fallback is normalized");
TestSupport.assertTrue(!statement.getNormalizedSql().contains("${"), "hive variable is removed");
TestSupport.assertTrue(!statement.getNormalizedSql().contains("@{"), "environment variable is removed");
TestSupport.assertTrue(
    statement.getWarnings().stream().noneMatch(warning -> warning.getWarningCode() == WarningCode.UNRESOLVED_VARIABLE),
    "statement has no fallback warning"
);
TestSupport.assertTrue(
    result.getWarnings().stream().noneMatch(warning -> warning.getWarningCode() == WarningCode.UNRESOLVED_VARIABLE),
    "aggregate has no fallback warning"
);
```

- [ ] **Step 2: Verify the test fails for the expected reason**

Run:

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn -Dtest=HiveScriptProcessingTest#fallbackVariableReplacementDoesNotProduceWarnings test
```

Expected: FAIL because fallback replacement currently adds `UNRESOLVED_VARIABLE` warnings.

### Task 2: Remove fallback warning creation

**Files:**
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptPreprocessor.java:95-128`
- Test: `src/test/java/com/panfan/hivelineage/HiveScriptProcessingTest.java:159-204`

- [ ] **Step 1: Implement the minimal replacement-only helper**

Replace the warning-producing helper with:

```java
private String replaceUnresolvedVariables(
    String statement,
    Pattern variablePattern,
    String fallbackVariableValue
) {
    Matcher matcher = variablePattern.matcher(statement);
    StringBuffer buffer = new StringBuffer();
    while (matcher.find()) {
        matcher.appendReplacement(buffer, Matcher.quoteReplacement(fallbackVariableValue));
    }
    matcher.appendTail(buffer);
    return buffer.toString();
}
```

Update both calls in `resolveVariables` to pass only `resolved`, the pattern, and `fallbackVariableValue`. Do not change raw/normalized SQL behavior.

- [ ] **Step 2: Run the focused test after implementation**

Run:

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn -Dtest=HiveScriptProcessingTest#fallbackVariableReplacementDoesNotProduceWarnings test
```

Expected: PASS.

- [ ] **Step 3: Align the existing fallback test**

Rename `unresolvedVariablesAreReplacedByFallbackConstant` to `unresolvedVariablesAreReplacedByFallbackConstantWithoutWarnings`. Replace its warning assertion with:

```java
TestSupport.assertTrue(
    statement.getWarnings().stream().noneMatch(warning -> warning.getWarningCode() == WarningCode.UNRESOLVED_VARIABLE),
    "fallback replacement does not warn"
);
```

- [ ] **Step 4: Run the complete processing test class**

Run:

```bash
/Users/panfan/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn -Dtest=HiveScriptProcessingTest test
```

Expected: PASS.

- [ ] **Step 5: Commit the implementation**

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveScriptPreprocessor.java src/test/java/com/panfan/hivelineage/HiveScriptProcessingTest.java
git commit -m "fix: suppress warnings for fallback variables"
```

### Task 3: Verify project-wide compatibility

**Files:**
- Verify only: repository test suite

- [ ] **Step 1: Run the full test suite**

Run:

```bash
bash scripts/run-tests.sh
```

Expected: PASS with no test failures.

- [ ] **Step 2: Review the final implementation diff**

Run:

```bash
git diff HEAD~1 --check
git show --stat --oneline HEAD
```

Expected: no whitespace errors; only preprocessor and regression-test changes in the implementation commit.
