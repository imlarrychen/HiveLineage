-- 不带 SORTED BY 的 bucket AST 只有列名与桶数，不能误读成排序列表。
CREATE TABLE dwd.ddl_bucket_only (id BIGINT, name STRING)
CLUSTERED BY (id, name) INTO 4 BUCKETS STORED AS PARQUET;
