-- SKEWED 与约束目前仅 parser 可达；约束 AST 不得被误当成列 comment。
CREATE TABLE dwd.ddl_skewed (id BIGINT, name STRING)
SKEWED BY (id) ON (1,2) STORED AS DIRECTORIES;
CREATE TABLE dwd.ddl_constraints (
    id BIGINT PRIMARY KEY DISABLE NOVALIDATE,
    name STRING NOT NULL,
    amount DECIMAL(12,2) COMMENT 'actual amount comment'
);
