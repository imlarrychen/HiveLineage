-- 存储格式保留为名称；不检查存储引擎执行能力，也不产生数据来源。
CREATE TABLE dwd.ddl_textfile (id BIGINT) STORED AS TEXTFILE;
CREATE TABLE dwd.ddl_sequencefile (id BIGINT) STORED AS SEQUENCEFILE;
CREATE TABLE dwd.ddl_rcfile (id BIGINT) STORED AS RCFILE;
CREATE TABLE dwd.ddl_orc (id BIGINT) STORED AS ORC;
CREATE TABLE dwd.ddl_parquet (id BIGINT) STORED AS PARQUET;
CREATE TABLE dwd.ddl_avro (id BIGINT) STORED AS AVRO;
CREATE TABLE dwd.ddl_jsonfile (id BIGINT) STORED AS JSONFILE;
