-- IF 标记与 LIKE 源名保存；LIKE 当前不复制来源 schema。
CREATE TABLE IF NOT EXISTS dwd.ddl_if_absent (id BIGINT);
CREATE EXTERNAL TABLE IF NOT EXISTS dwd.ddl_like_external LIKE ods.users
LOCATION 'hdfs:///warehouse/ddl_like_external';
DROP TABLE IF EXISTS dwd.ddl_missing PURGE;
DROP VIEW IF EXISTS dim.ddl_missing_view;
