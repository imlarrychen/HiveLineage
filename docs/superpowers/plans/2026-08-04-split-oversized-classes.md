# Oversized Java Class Responsibility Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor every Java source file below 1001 physical lines without changing the lineage analyzer's public API or observable behavior.

**Architecture:** Keep `HiveScriptLineageAnalyzer` and `HiveParserAstAdapter` as public facades and coordinators. Move script preprocessing, join analysis, projection resolution, view/temporary expansion, and AST extraction into package-private collaborators; split the monolithic test suite by behavior and enforce the line limit with a JUnit architecture test.

**Tech Stack:** Java 8, Maven, JUnit 4.13.2, Hive Parser 4.0.1

---

## File map

Production files to create:

- `src/main/java/com/panfan/hivelineage/core/HiveScriptPreprocessor.java` — statement splitting, comments, session variables, unresolved-variable fallback.
- `src/main/java/com/panfan/hivelineage/core/HiveJoinConditionAnalyzer.java` — SQL-text join discovery, query-block traversal, direct and resolved join fields.
- `src/main/java/com/panfan/hivelineage/core/HiveTargetColumnResolver.java` — target-column alignment, metadata-based star expansion, top-level aliases.
- `src/main/java/com/panfan/hivelineage/core/HiveProjectionLineageResolver.java` — inline/CTE/set-operation projection mapping and expression source columns.
- `src/main/java/com/panfan/hivelineage/core/HiveLineageExpansionService.java` — temporary-table and view table/column expansion and boundary warnings.
- `src/main/java/com/panfan/hivelineage/core/HiveSqlTextUtils.java` — shared package-private identifier and balanced SQL-text operations only.
- `src/main/java/com/panfan/hivelineage/core/HiveAstProjectionExtractor.java` — AST select, set-operation, subquery projection, and partition extraction.
- `src/main/java/com/panfan/hivelineage/core/HiveAstJoinExtractor.java` — AST explicit/implicit join extraction.

Production files to modify:

- `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java` — retain facade, request-local state, orchestration, result construction.
- `src/main/java/com/panfan/hivelineage/core/HiveParserAstAdapter.java` — retain ParseDriver invocation and `ParsedHiveStatement` assembly.

Test files to create:

- `src/test/java/com/panfan/hivelineage/JavaSourceSizeTest.java`
- `src/test/java/com/panfan/hivelineage/AnalyzerTestSupport.java`
- `src/test/java/com/panfan/hivelineage/HiveJoinLineageTest.java`
- `src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java`
- `src/test/java/com/panfan/hivelineage/HiveViewAndTemporaryLineageTest.java`
- `src/test/java/com/panfan/hivelineage/HiveScriptProcessingTest.java`
- `src/test/java/com/panfan/hivelineage/HiveParserAstAdapterTest.java`
- `src/test/java/com/panfan/hivelineage/HiveMetadataLineageTest.java`

Test file to remove after all methods are migrated:

- `src/test/java/com/panfan/hivelineage/HiveLineageAnalyzerTest.java`

## Task 1: Add the source-size architecture test

**Files:**
- Create: `src/test/java/com/panfan/hivelineage/JavaSourceSizeTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.panfan.hivelineage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

public class JavaSourceSizeTest {
    private static final long MAX_LINES = 1000L;

    @Test
    public void everyJavaSourceFileStaysWithinOneThousandLines() throws IOException {
        List<String> oversized = new ArrayList<>();
        inspect(Paths.get("src/main/java"), oversized);
        inspect(Paths.get("src/test/java"), oversized);
        TestSupport.assertTrue(oversized.isEmpty(), "oversized Java sources: " + oversized);
    }

    private static void inspect(Path root, List<String> oversized) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try (Stream<String> lines = Files.lines(path)) {
                    long count = lines.count();
                    if (count > MAX_LINES) {
                        oversized.add(path + " (" + count + ")");
                    }
                } catch (IOException ex) {
                    throw new IllegalStateException("Cannot inspect " + path, ex);
                }
            });
        }
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `mvn -q -Dtest=JavaSourceSizeTest test`

Expected: FAIL listing exactly `HiveScriptLineageAnalyzer.java`, `HiveParserAstAdapter.java`, and `HiveLineageAnalyzerTest.java` with counts above 1000.

- [ ] **Step 3: Commit the red architecture test**

```bash
git add src/test/java/com/panfan/hivelineage/JavaSourceSizeTest.java
git commit -m "test: enforce Java source size limit"
```

## Task 2: Split the monolithic behavior test suite

**Files:**
- Create: `src/test/java/com/panfan/hivelineage/AnalyzerTestSupport.java`
- Create: `src/test/java/com/panfan/hivelineage/HiveJoinLineageTest.java`
- Create: `src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java`
- Create: `src/test/java/com/panfan/hivelineage/HiveViewAndTemporaryLineageTest.java`
- Create: `src/test/java/com/panfan/hivelineage/HiveScriptProcessingTest.java`
- Create: `src/test/java/com/panfan/hivelineage/HiveParserAstAdapterTest.java`
- Create: `src/test/java/com/panfan/hivelineage/HiveMetadataLineageTest.java`
- Delete: `src/test/java/com/panfan/hivelineage/HiveLineageAnalyzerTest.java`

- [ ] **Step 1: Record the existing test inventory**

Run:

```bash
rg '^    public void [A-Za-z0-9_]+\(' src/test/java/com/panfan/hivelineage/HiveLineageAnalyzerTest.java
mvn -q -DskipTests test-compile
```

Expected: 73 JUnit behavior methods plus the manual `main`; test compilation succeeds. Save the method-name output for comparison. The manual `main` is obsolete under Maven/JUnit and is not migrated.

- [ ] **Step 2: Create shared test support**

Move the exact bodies of `readWorkspaceFile`, both `assertContainsSource` overloads, `assertNotContainsSource`, `assertContainsColumn`, `containsColumn`, `containsSource`, `findJoinCondition`, `assertContainsWarning`, `CountingMetadataProvider`, `ExpiringMetadataProvider`, and `MapMetadataProvider` from lines 2397-2550 of the current test into package-private `AnalyzerTestSupport`. Remove only the `private` modifier needed for themed tests to call the methods or instantiate the nested providers; do not change their algorithms.

- [ ] **Step 3: Move Join behavior tests**

Create `HiveJoinLineageTest` and move every method whose name contains `Join`, `join`, `CteJoin`, or `temporaryTableJoinCondition`. Preserve each `@Test`, body, input SQL, and assertion. Replace calls to moved private helpers with `AnalyzerTestSupport` calls.

- [ ] **Step 4: Move column/projection behavior tests**

Create `HiveColumnLineageTest` and move expression, union, constant, partition, star, subquery projection, window function, lateral view, CTAS column, and column-alias tests without changing assertions.

- [ ] **Step 5: Move view/temporary, script, adapter, and metadata tests**

Move the remaining methods by the file-map responsibilities. Put general table-lineage smoke cases and production samples in `HiveScriptProcessingTest`. Ensure no new test class exceeds 1000 lines.

- [ ] **Step 6: Delete the old test class and compare inventory**

Run:

```bash
rg '^    public void [A-Za-z0-9_]+\(' src/test/java/com/panfan/hivelineage/*Test.java
mvn -q -DskipTests test-compile
```

Expected: the same 73 JUnit method names appear exactly once; compilation succeeds.

- [ ] **Step 7: Run all behavior tests except the known-red size gate**

Run: `mvn -q -Dtest=HiveJoinLineageTest,HiveColumnLineageTest,HiveViewAndTemporaryLineageTest,HiveScriptProcessingTest,HiveParserAstAdapterTest,HiveMetadataLineageTest,LineageMarkdownRendererTest,HiveAstWalkerTest,GeneratedSqlTestSetTest,JdbcMetadataColumnV2ProviderIntegrationTest test`

Expected: PASS with no failures or errors.

- [ ] **Step 8: Re-run the size test**

Run: `mvn -q -Dtest=JavaSourceSizeTest test`

Expected: FAIL only for the two production files.

- [ ] **Step 9: Commit the test responsibility split**

```bash
git add src/test/java/com/panfan/hivelineage
git commit -m "test: split lineage analyzer tests by behavior"
```

## Task 3: Extract AST join analysis

**Files:**
- Create: `src/main/java/com/panfan/hivelineage/core/HiveAstJoinExtractor.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveParserAstAdapter.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveParserAstAdapterTest.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveJoinLineageTest.java`

- [ ] **Step 1: Run the focused baseline**

Run: `mvn -q -Dtest=HiveParserAstAdapterTest,HiveJoinLineageTest test`

Expected: PASS.

- [ ] **Step 2: Create the AST Join collaborator**

Move `resolveJoinConditions` and every helper used exclusively by it, from nested-subquery collection through direct-object resolution, into this package-private class. Move bodies unchanged and inject the existing walker/expression extractor.

```java
final class HiveAstJoinExtractor {
    private final HiveAstWalker astWalker;
    private final HiveAstExpressionExtractor expressionExtractor;

    HiveAstJoinExtractor(HiveAstWalker astWalker, HiveAstExpressionExtractor expressionExtractor) {
        this.astWalker = astWalker;
        this.expressionExtractor = expressionExtractor;
    }

    List<ParsedJoinCondition> extract(ASTNode root, Set<String> cteAliases) {
        return resolveJoinConditions(root, cteAliases);
    }
}
```

- [ ] **Step 3: Delegate from `HiveParserAstAdapter`**

Add `HiveAstJoinExtractor joinExtractor = new HiveAstJoinExtractor(astWalker, expressionExtractor);` and replace `resolveJoinConditions(root, cteAliases)` with `joinExtractor.extract(root, cteAliases)` in `parse`.

- [ ] **Step 4: Run focused tests**

Run: `mvn -q -Dtest=HiveParserAstAdapterTest,HiveJoinLineageTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveAstJoinExtractor.java src/main/java/com/panfan/hivelineage/core/HiveParserAstAdapter.java
git commit -m "refactor: extract AST join analysis"
```

## Task 4: Extract AST projection analysis

**Files:**
- Create: `src/main/java/com/panfan/hivelineage/core/HiveAstProjectionExtractor.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveParserAstAdapter.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveParserAstAdapterTest.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java`

- [ ] **Step 1: Create the projection value returned to the adapter**

```java
final class HiveAstProjectionExtractor {
    static final class Projection {
        final List<String> selectExpressions;
        final Map<String, String> columnAliasSources;
        final Map<String, String> columnAliasExpressions;
        final Map<Integer, List<String>> setOperationSourceColumns;
        final Map<Integer, List<String>> setOperationExpressions;
        final Map<String, String> staticPartitionValues;

        Projection(List<String> selectExpressions,
                   Map<String, String> columnAliasSources,
                   Map<String, String> columnAliasExpressions,
                   Map<Integer, List<String>> setOperationSourceColumns,
                   Map<Integer, List<String>> setOperationExpressions,
                   Map<String, String> staticPartitionValues) {
            this.selectExpressions = selectExpressions;
            this.columnAliasSources = columnAliasSources;
            this.columnAliasExpressions = columnAliasExpressions;
            this.setOperationSourceColumns = setOperationSourceColumns;
            this.setOperationExpressions = setOperationExpressions;
            this.staticPartitionValues = staticPartitionValues;
        }
    }

    Projection extract(ASTNode root, ASTNode mainInsert, ASTNode mainSetOperation, Set<String> cteAliases) {
        return new Projection(
            resolveSelectExpressions(root, mainInsert),
            resolveColumnAliasSources(root, cteAliases),
            resolveColumnAliasExpressions(root, cteAliases),
            resolveSetOperationSourceColumns(mainSetOperation, cteAliases),
            resolveSetOperationExpressions(mainSetOperation, cteAliases),
            resolveStaticPartitionValues(mainInsert)
        );
    }
}
```

Move the private helpers for select expressions, union branches, subquery column projections, alias sources/expressions, and static partitions into this class. Keep table/source alias discovery in the adapter unless the projection extractor is its only consumer.

- [ ] **Step 2: Delegate `ParsedHiveStatement` assembly**

Create one `Projection projection = projectionExtractor.extract(...)` in `parse` and pass its six fields to the existing constructor in the same order. Keep target-column fallback behavior in the adapter.

- [ ] **Step 3: Run adapter and column tests**

Run: `mvn -q -Dtest=HiveParserAstAdapterTest,HiveColumnLineageTest test`

Expected: PASS.

- [ ] **Step 4: Verify adapter file size**

Run: `wc -l src/main/java/com/panfan/hivelineage/core/HiveParserAstAdapter.java src/main/java/com/panfan/hivelineage/core/HiveAstProjectionExtractor.java src/main/java/com/panfan/hivelineage/core/HiveAstJoinExtractor.java`

Expected: each file is at most 1000 lines.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveAstProjectionExtractor.java src/main/java/com/panfan/hivelineage/core/HiveParserAstAdapter.java
git commit -m "refactor: extract AST projection analysis"
```

## Task 5: Extract script preprocessing

**Files:**
- Create: `src/main/java/com/panfan/hivelineage/core/HiveScriptPreprocessor.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveScriptProcessingTest.java`

- [ ] **Step 1: Run script-processing baseline**

Run: `mvn -q -Dtest=HiveScriptProcessingTest test`

Expected: PASS.

- [ ] **Step 2: Extract the preprocessor**

Move the exact implementations of `isSetStatement`, `applySetStatement`, `stripQuotedValue`, both `resolveVariables` overloads, `replaceUnresolvedVariables`, `splitStatements`, `stripLeadingComments`, `indexOfLineEnd`, and `addStatement` into `HiveScriptPreprocessor`. Keep the current package imports for collections, regex, and warnings; change only receiver calls needed after the move.

- [ ] **Step 3: Delegate from the analyzer**

Add one final preprocessor field initialized in the constructor path and prefix the existing orchestration calls with `scriptPreprocessor.`. Do not move request-local maps into fields.

- [ ] **Step 4: Run script tests and commit**

Run: `mvn -q -Dtest=HiveScriptProcessingTest test`

Expected: PASS.

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveScriptPreprocessor.java src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java
git commit -m "refactor: extract Hive script preprocessing"
```

## Task 6: Extract SQL-text Join analysis

**Files:**
- Create: `src/main/java/com/panfan/hivelineage/core/HiveJoinConditionAnalyzer.java`
- Create: `src/main/java/com/panfan/hivelineage/core/HiveSqlTextUtils.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveJoinLineageTest.java`

- [ ] **Step 1: Run Join baseline**

Run: `mvn -q -Dtest=HiveJoinLineageTest test`

Expected: PASS.

- [ ] **Step 2: Move shared balanced-text primitives**

Create static package-private helpers by moving the exact implementations of `findMatchingParen`, `topLevelKeywordIndex`, `lastTopLevelKeywordIndex`, `matchesKeywordAt`, `skipWhitespace`, `readIdentifier`, `isIdentifierCharacter`, `normalizeIdentifier`, `tableNameOfColumn`, and `columnNameOf`. Do not move lineage decisions into the utility. Update consumers with static imports or `HiveSqlTextUtils.` qualification consistently.

- [ ] **Step 3: Extract Join behavior and nested value objects**

Move methods from `buildJoinConditions` through `resolveJoinType`, plus Join-only CTE projection expansion, into `HiveJoinConditionAnalyzer`. Move `JoinIndexCounter`, `JoinClause`, `JoinTypeInfo`, `CteBlock`, and `CteExtraction` with them.

Expose one entry point whose parameter list mirrors existing request-local inputs:

```java
List<JoinCondition> buildJoinConditions(
    LineageAnalyzeRequest request,
    ParsedHiveStatement parsedStatement,
    String rawStatement,
    String normalizedStatement,
    int statementIndex,
    Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns,
    List<LineageWarning> warnings)
```

If projection types are needed across components, promote `ColumnLineageProjection` to its own package-private top-level file or replace the dependency with `List<ColumnRef>`/expression values; do not make it public.

- [ ] **Step 4: Delegate and verify**

Replace the analyzer's internal Join calls with `joinConditionAnalyzer` calls. Run: `mvn -q -Dtest=HiveJoinLineageTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveJoinConditionAnalyzer.java src/main/java/com/panfan/hivelineage/core/HiveSqlTextUtils.java src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java
git commit -m "refactor: extract join condition analysis"
```

## Task 7: Extract target-column and projection lineage resolution

**Files:**
- Create: `src/main/java/com/panfan/hivelineage/core/HiveTargetColumnResolver.java`
- Create: `src/main/java/com/panfan/hivelineage/core/HiveProjectionLineageResolver.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveColumnLineageTest.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveMetadataLineageTest.java`

- [ ] **Step 1: Run projection baseline**

Run: `mvn -q -Dtest=HiveColumnLineageTest,HiveMetadataLineageTest test`

Expected: PASS.

- [ ] **Step 2: Extract target-column resolution**

Move `parseTargetColumns`, `inferTargetColumns`, both `resolveTargetColumns` overloads, both `expandStarSelectItems` overloads, `isSingleStarSelect`, `intersectionInTargetOrder`, all three metadata-loading helpers, source-alias extraction/merging/filtering, top-level alias capture, and both `buildColumnOwners` overloads into `HiveTargetColumnResolver`. Inject no mutable request state; pass `LineageAnalyzeRequest` and warning lists to metadata-loading operations. Preserve existing overload signatures until delegation compiles, then expose the smallest package-private entry points actually called by the facade.

- [ ] **Step 3: Extract projection lineage resolution**

Move `setOperationSourceColumns`, `setOperationExpression`, `setOperationFunctionName`, `joinWithComma`, `hasCompleteSetOperationProjection`, every method from `extractStatementProjectionTables` through `removeStringLiterals`, and the nested values `ColumnLineageProjection`, `SetOperationParts`, and `DirectColumn` into `HiveProjectionLineageResolver`. If Join or expansion code still requires `ColumnLineageProjection`, place that value unchanged in `ColumnLineageProjection.java` with package visibility instead of making it public.

- [ ] **Step 4: Delegate without changing ordering**

Update `analyzeSelectItemsToTarget` to ask the target resolver for aligned targets and the projection resolver for expression/source mapping. Preserve current loop order and construction of `ColumnLineage`.

- [ ] **Step 5: Run projection and metadata tests**

Run: `mvn -q -Dtest=HiveColumnLineageTest,HiveMetadataLineageTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveTargetColumnResolver.java src/main/java/com/panfan/hivelineage/core/HiveProjectionLineageResolver.java src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java
git commit -m "refactor: extract column lineage resolution"
```

## Task 8: Extract view and temporary-table expansion

**Files:**
- Create: `src/main/java/com/panfan/hivelineage/core/HiveLineageExpansionService.java`
- Modify: `src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java`
- Test: `src/test/java/com/panfan/hivelineage/HiveViewAndTemporaryLineageTest.java`

- [ ] **Step 1: Run expansion baseline**

Run: `mvn -q -Dtest=HiveViewAndTemporaryLineageTest test`

Expected: PASS.

- [ ] **Step 2: Extract expansion behavior**

Move the exact implementations of `registerTemporaryTable`, `columnProjectionMap`, `containsTemporaryTable`, `expandTemporaryInputObjects`, `filterProjectionInputObjects`, `isStatementProjectionObject`, `expandViewInputObjects`, `expandViewSourceColumns`, `viewColumnMapping`, both boundary-warning helpers, `isKnownView`, `addInputObject`, and `addColumn` into `HiveLineageExpansionService`. Keep temporary-table lookup ahead of metastore view lookup and pass every cache/map explicitly from `analyzeScript`.

- [ ] **Step 3: Delegate and verify**

Keep all temporary/view caches local to `analyzeScript` and pass them into the service. Run: `mvn -q -Dtest=HiveViewAndTemporaryLineageTest test`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/panfan/hivelineage/core/HiveLineageExpansionService.java src/main/java/com/panfan/hivelineage/core/HiveScriptLineageAnalyzer.java
git commit -m "refactor: extract lineage expansion service"
```

## Task 9: Make the size gate green and run full verification

**Files:**
- Modify only if needed: all files created or modified in Tasks 1-8
- Test: `src/test/java/com/panfan/hivelineage/JavaSourceSizeTest.java`

- [ ] **Step 1: Compile and list all file sizes**

Run:

```bash
mvn -q -DskipTests test-compile
find src -type f -name '*.java' -print0 | xargs -0 wc -l | sort -nr
```

Expected: compilation succeeds and every individual Java file is at most 1000 lines.

- [ ] **Step 2: Run the formerly-red size gate**

Run: `mvn -q -Dtest=JavaSourceSizeTest test`

Expected: PASS.

- [ ] **Step 3: Run the full clean suite**

Run: `mvn -q clean test`

Expected: exit code 0, zero failures, zero errors.

- [ ] **Step 4: Check scope and formatting**

Run:

```bash
git diff --check
git status --short
git diff --stat HEAD~8
```

Expected: no whitespace errors; only design/plan, relevant Java source, and relevant tests are changed. `release/` remains untracked and untouched.

- [ ] **Step 5: Commit any final mechanical cleanup**

Only if Step 1 required import/format cleanup:

```bash
git add src/main/java src/test/java
git commit -m "refactor: complete oversized class split"
```

- [ ] **Step 6: Record final evidence**

Capture the full test count from Surefire reports and the maximum Java file/line count for the handoff. Do not claim completion without the fresh outputs from Steps 1-4.
