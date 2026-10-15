-- Materialized view 的 CREATE/REBUILD 涉及未支持的数据写入，明确失败；DROP 仅审计。
CREATE MATERIALIZED VIEW dim.ddl_materialized
AS SELECT q.id FROM (SELECT id FROM ods.users) q;
ALTER MATERIALIZED VIEW dim.ddl_materialized REBUILD;
DROP MATERIALIZED VIEW IF EXISTS dim.ddl_materialized;
