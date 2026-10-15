-- DATABASE/SCHEMA 同义语法及选项只审计，不模拟数据库目录和权限。
CREATE DATABASE IF NOT EXISTS ddl_database
COMMENT 'generated database' LOCATION 'hdfs:///warehouse/ddl_database'
WITH DBPROPERTIES ('owner'='integration');
CREATE SCHEMA IF NOT EXISTS ddl_schema;
ALTER DATABASE ddl_database SET DBPROPERTIES ('owner'='updated');
ALTER DATABASE ddl_database SET OWNER USER generated_owner;
ALTER DATABASE ddl_database SET LOCATION 'hdfs:///warehouse/ddl_database_new';
DROP DATABASE IF EXISTS ddl_database CASCADE;
DROP SCHEMA IF EXISTS ddl_schema RESTRICT;
