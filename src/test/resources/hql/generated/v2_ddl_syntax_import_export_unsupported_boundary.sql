-- IMPORT/EXPORT 涉及未支持的数据搬运；语法可达但引擎明确失败，不影响后续普通 INSERT。
IMPORT TABLE dwd.ddl_imported FROM 'hdfs:///export/generated_users';
EXPORT TABLE ods.users TO 'hdfs:///export/generated_users';
INSERT INTO dwd.result (id, name) SELECT id, name FROM ods.users;
