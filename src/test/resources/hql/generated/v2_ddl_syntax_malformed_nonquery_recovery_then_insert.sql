-- 非查询 DDL 缺失字段类型，仅保留 INVALID_DDL 审计，前端 PARTIAL；后续 INSERT 继续。
CREATE TABLE dwd.ddl_invalid (id);
INSERT INTO dwd.result (id, name) SELECT id, name FROM ods.users;
