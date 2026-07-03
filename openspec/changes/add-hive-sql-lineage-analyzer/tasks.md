# Tasks: Add Hive SQL Lineage Analyzer

## 0. Project Setup

- [x] Convert the current Java sources into a Maven project.
- [x] Configure Maven compiler source/target as Java 8.
- [x] Add Hive parser dependency with version `4.0.1`.
- [x] Keep parser dependency isolated behind adapter interfaces.
- [x] Add JUnit dependency compatible with Java 8.

## 1. API Model

- [x] Define `LineageAnalyzeRequest` with `workflowId`, `taskId`, `taskVersion`, `defaultDatabase`, `rawScript`, `runtimeVariables`, and `options`.
- [x] Define `LineageAnalyzeResult`, `StatementLineageResult`, `TableLineage`, `ColumnLineage`, and `LineageWarning`.
- [x] Define `LineageAnalyzeOptions` and `LineageParseMode` with `TABLE_ONLY`, `COLUMN_ONLY`, and `TABLE_AND_COLUMN`.
- [x] Add view expansion options `expandViewLineage` and `maxViewExpandDepth`.
- [x] Define `StatementParseStatus` with numeric codes: `SUCCESS(0)`, `PARTIAL_SUCCESS(1)`, `FAILED(2)`, and `IGNORED(3)`.
- [x] Ensure each lineage-producing, failed, or diagnostic statement produces one `StatementLineageResult`.
- [x] Ensure successful non-lineage statements are ignored by default unless `includeIgnoredStatements` is enabled.
- [x] Ensure `StatementLineageResult` stores both `rawSql` and `normalizedSql`.
- [x] Ensure `StatementLineageResult` carries `workflowId`, `taskId`, `taskVersion`, `statementIndex`, and `statementId`.
- [x] Ensure `StatementLineageResult` records the parse mode used for the statement.
- [x] Ensure `StatementLineageResult` records `parseStatusCode`, skip reason, failure code, and failure message where applicable.
- [ ] Document that TDSQL persistence uses one row per `StatementLineageResult`.
- [ ] Define `TableKey`, `DataObjectRef`, `ColumnRef`, `TableMetadata`, and `ColumnMetadata`.
- [ ] Add `TableObjectType` to distinguish physical tables and views.
- [ ] Add view definition fields to `TableMetadata`, including original and expanded view SQL.
- [x] Define enums for `LineageType`, `StatementType`, `StatementParseStatus`, and `WarningCode`.

## 2. Script Processing

- [ ] Add `ScriptPreprocessor` interface.
- [ ] Add no-op `removeComments` implementation as a temporary extension point.
- [x] Add no-op or simple `splitStatements` implementation as a temporary extension point.
- [x] Ensure statement index is stable after splitting.

## 3. Session State

- [ ] Add `SessionContext`.
- [x] Parse `set key=value` and update `sessionVariables`.
- [ ] Parse `use database` and update `currentDatabase`.
- [x] Store and resolve temporary table lineage in `temporaryTables`.
- [x] Ensure successful `set` and `use` statements do not create persisted statement results by default.

## 4. Variable Resolution

- [ ] Add `VariableResolver`.
- [x] Resolve variables from session variables first.
- [x] Resolve variables from request runtime variables second.
- [ ] Resolve known built-in fallback variables third.
- [ ] Preserve unresolved variables and emit `UNRESOLVED_VARIABLE` warning.
- [x] Support `SET hivevar:VARI = 'xx'` and later `${hivevar:VARI}` references.
- [x] Support `${xxx}` runtime variables and fallback replacement.
- [x] Support `@{xxx}` environment variables with fallback replacement only.
- [x] Preserve original variable text in `rawSql` and replacement output in `normalizedSql`.

## 5. Metadata Cache

- [x] Define `MetadataProvider`.
- [x] Add mock `MetadataProvider` implementation for tests and local development.
- [x] Keep production Metastore MySQL access behind `MetadataProvider` and do not couple parser logic to MySQL tables.
- [x] Add JDBC provider for `metadata_column_v2` field metadata lookup.
- [ ] Add object-level metadata API to return whether an object is a table or view without loading full field metadata.
- [ ] Ensure `MetadataProvider` can return whether an object is a table or view.
- [x] Ensure view metadata includes view definition SQL when available.
- [ ] Add cache for view expansion results keyed by view identity and view SQL digest.
- [x] Implement workflow-level cache.
- [x] Implement process-level TTL cache.
- [x] Add negative cache for missing tables.
- [ ] Add batch metadata loading.
- [x] Add single-flight protection for concurrent table metadata loads.
- [x] Ensure `TABLE_ONLY` mode does not load field metadata.
- [x] Allow `TABLE_ONLY` mode to load object-level metadata when view expansion is enabled.
- [x] Ensure `COLUMN_ONLY` and `TABLE_AND_COLUMN` modes load metadata only when field lineage requires it.

## 6. Statement Analysis

- [x] Add `HiveAstParserAdapter` based on Hive parser 4.0.1.
- [x] Convert Hive parser AST into an internal `ParsedHiveStatement` model.
- [x] Ensure lineage generation code depends on `ParsedHiveStatement`, not Hive parser AST classes.
- [x] Identify statements that can produce formal lineage.
- [x] Skip statements without both input data object and output data object.
- [x] Ignore successful non-lineage statements by default while preserving their Session side effects.
- [x] Persist failed statement results with `parseStatusCode`, `failureCode`, `failureMessage`, `rawSql`, and `statementId`.
- [x] Extract table-level lineage for insert, CTAS, temporary table CTAS, and create view when parse mode includes table lineage.
- [x] Extract table-level lineage for alter view when parse mode includes table lineage.
- [x] Extract field-level lineage for direct columns, expressions, aggregations, constants, and table-only dependencies when parse mode includes field lineage.
- [x] Traverse column expression ASTs to collect source column references.
- [x] Attempt to preserve normalized column expressions in `ColumnLineage.expression`.
- [x] Ensure missing expression text does not block source column extraction.
- [x] Record whether expression text was extracted in `ColumnLineage.expressionExtracted`.
- [x] Classify column lineage as `DIRECT` or `DERIVED`.
- [x] Extract function names into `ColumnLineage.functionNames` when expressions contain functions or UDFs.
- [x] Mark `complexExpression` for expressions that contain functions, arithmetic operations, case-when, aggregation, window functions, or multiple source columns.
- [x] Degrade unknown UDFs by extracting parameter columns when possible and preserving expression only when stable extraction is available.
- [x] Ensure `COLUMN_ONLY` mode can use internally extracted input and output objects without returning table lineage.
- [x] Emit warnings for unsupported or partially parsed statements.
- [x] Support `insert into table ... select ...`.
- [x] Support `insert overwrite table ... select ...`.
- [x] Support static and dynamic partition writes.
- [x] Support `create table ... as select ...`.
- [x] Support `create temporary table ... as select ...`.
- [x] Support `create view ... as select ...`.
- [x] Support CTE, subquery, join, union, group by, case when, function, UDF, and aggregate in lineage extraction.
- [x] Support lateral view explode in lineage extraction.
- [x] Continue ignoring pure select and insert values statements under formal lineage rules.

## 7. View Lineage Expansion

- [x] Detect source objects whose metadata type is `VIEW`.
- [ ] Ensure view detection is based on `MetadataProvider.getTableObjectMetadata`, not SQL text or naming rules.
- [x] Resolve Session temporary tables before querying Hive object metadata.
- [x] Parse view definition SQL and build view field-to-upstream-field mapping.
- [ ] Reuse cached `ViewLineageExpansion` when the same view SQL digest appears again.
- [x] Expand table lineage through views when `expandViewLineage = true`.
- [x] Expand column lineage through view field mappings in field lineage modes.
- [x] Support nested view expansion up to `maxViewExpandDepth`.
- [ ] Detect view expansion cycles and emit `VIEW_EXPANSION_CYCLE`.
- [x] Emit `VIEW_METADATA_NOT_FOUND` when view definition SQL is unavailable.
- [x] Emit `VIEW_EXPANSION_FAILED` when view definition SQL cannot be parsed.

## 8. Temporary Table Composition

- [x] Register temporary table lineage on creation.
- [x] Use temporary table lineage when a later statement reads from the temporary table.
- [x] Expand temporary table lineage to real upstream objects in final result.
- [x] Support `includeTemporaryLineage` option for debugging output.

## 9. Testing

- [ ] Test Maven build on Java 8.
- [x] Test Hive parser 4.0.1 adapter parses supported Hive SQL types.
- [x] Test single insert table lineage.
- [x] Test field lineage with direct columns and expressions.
- [x] Test `set` variable propagation.
- [x] Test successful `set` statement updates Session but is not returned by default.
- [x] Test successful pure query statement is ignored and not returned by default.
- [x] Test parse failure returns a `StatementLineageResult` with `parseStatusCode = 2` and failure details.
- [x] Test fallback variable replacement.
- [x] Test unresolved variable warning.
- [x] Test temporary table lineage expansion.
- [x] Test task version isolation in result keys.
- [x] Test standalone `select` does not produce formal lineage.
- [x] Test metadata cache avoids repeated provider calls.
- [x] Test JDBC provider queries `metadata_column_v2`.
- [x] Test analyzer uses JDBC provider to expand `select *` field lineage.
- [ ] Test `TABLE_ONLY` returns table lineage and does not call metadata provider for field metadata.
- [ ] Test `COLUMN_ONLY` returns column lineage without returning table lineage.
- [ ] Test `TABLE_AND_COLUMN` returns both table lineage and column lineage.
- [ ] Test missing field metadata does not block `TABLE_ONLY` table lineage.
- [ ] Test SQL `insert into test_a(a1, a2) select b1 + b2, b3 from test_b` records `test_b.b1/test_b.b2 -> test_a.a1`, `test_b.b3 -> test_a.a2`, `DERIVED` for `a1`, `DIRECT` for `a2`, and attempts expression extraction for `b1 + b2`.
- [ ] Test SQL `insert overwrite table test_a select nvl(b1, 0) as a1 from test_b` records `test_b.b1 -> test_a.a1`, `DERIVED`, and attempts expression/function extraction for `nvl`.
- [ ] Test SQL `insert overwrite table test_a select user_id, sum(amount) as total_amount from test_b group by user_id` records `test_b.amount -> test_a.total_amount`, `DERIVED`, and attempts expression/function extraction for `sum`.
- [ ] Test SQL `insert overwrite table test_a select complex_udf(named_struct('k', b1), b2) as a1 from test_b` still extracts source columns when expression text cannot be stably extracted and sets `expressionExtracted = false`.
- [ ] Test unknown UDF lineage preserves source columns when possible and degrades with warning only when parameter columns cannot be reliably extracted.
- [ ] Test SQL `SET hivevar:VARI = 'xx'; insert into test_a select name from stu where age = ${hivevar:VARI}` replaces hivevar namespace variables.
- [ ] Test SQL containing `@{env_name}` replaces the environment variable with fallback value.
- [ ] Test CTAS table lineage and column lineage.
- [ ] Test create temporary table CTAS registers temporary lineage.
- [ ] Test create view AS SELECT produces lineage for the view target.
- [x] Test join, union, CTE, case when, group by, lateral view explode, and partition write examples.
- [ ] Test view table lineage expands from view to physical upstream table.
- [ ] Test view column lineage expands from view field to physical upstream column.
- [x] Test missing view definition emits `VIEW_METADATA_NOT_FOUND`.
- [ ] Test nested view expansion respects `maxViewExpandDepth`.
- [ ] Test view cycle emits `VIEW_EXPANSION_CYCLE`.

## 10. Documentation

- [ ] Document formal lineage generation rules.
- [ ] Document output schema with JSON example.
- [ ] Document cache key and task version relationship.
- [ ] Document extension point for comment removal and statement splitting.
- [ ] Document view expansion behavior and warning codes.
