-- Hive CREATE AST 的 bucket token 与 SerDeProps wrapper；保存全部已建模选项。
CREATE EXTERNAL TABLE IF NOT EXISTS dwd.ddl_full_options (
    id BIGINT COMMENT 'user identifier',
    amount DECIMAL(12,2) COMMENT 'order amount'
)
COMMENT 'full DDL options'
PARTITIONED BY (dt STRING COMMENT 'business date', region STRING COMMENT 'region')
CLUSTERED BY (id) SORTED BY (amount DESC, id ASC) INTO 8 BUCKETS
ROW FORMAT DELIMITED
FIELDS TERMINATED BY ',' ESCAPED BY '\\'
COLLECTION ITEMS TERMINATED BY ':'
MAP KEYS TERMINATED BY '='
LINES TERMINATED BY '\n'
NULL DEFINED AS 'NULL'
STORED AS TEXTFILE
LOCATION 'hdfs:///warehouse/ddl_full_options'
TBLPROPERTIES ('owner'='integration', 'retention'='30');
