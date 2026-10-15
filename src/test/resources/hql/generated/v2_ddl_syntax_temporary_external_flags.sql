-- 无查询声明式 TEMPORARY/EXTERNAL 选项与 location；两者不产生写入血缘。
CREATE TEMPORARY TABLE ddl_declared_temp (id BIGINT COMMENT 'temporary identifier');
CREATE EXTERNAL TABLE dwd.ddl_declared_external (id BIGINT)
LOCATION 'hdfs:///warehouse/ddl_declared_external';
