# Fallback Variable Warning Semantics

## Goal

Make diagnostics reflect the final SQL analysis result. A variable that is
successfully replaced with the configured fallback value must not produce an
`UNRESOLVED_VARIABLE` warning.

## Scope

This applies equally to `${...}` Hive/runtime variables and `@{...}`
environment-style variables when no supplied or session value is available.

## Design

`HiveScriptPreprocessor` will continue to replace unresolved variable tokens
with `fallbackVariableValue`, but the fallback replacement path will not add a
warning. `rawSql` remains unchanged and `normalizedSql` remains the
fallback-replaced SQL.

Subsequent parser or lineage-analysis failures continue to be returned through
their existing failure codes and warnings. No new warning code is introduced.

## Verification

Regression tests will cover both `${...}` and `@{...}` fallback replacement:

- the normalized SQL contains the fallback value and no variable marker;
- analysis succeeds for otherwise valid SQL;
- the statement and aggregated result contain no `UNRESOLVED_VARIABLE` warning;
- an invalid normalized SQL still reports `SQL_PARSE_ERROR`.
