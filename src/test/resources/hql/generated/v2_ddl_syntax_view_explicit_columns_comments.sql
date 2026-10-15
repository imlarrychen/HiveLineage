-- 显式 view 列名/COMMENT 覆盖内部别名；括号查询中含嵌套派生表。
-- 先 DROP 建立已知不存在的前提，随后 IF NOT EXISTS 的定义可确定提交。
DROP VIEW IF EXISTS dim.ddl_alias_view;
CREATE VIEW IF NOT EXISTS dim.ddl_alias_view (
    user_key COMMENT 'view user key',
    display_name COMMENT 'view display name'
)
COMMENT 'aliased nested view'
TBLPROPERTIES ('owner'='integration')
AS (
    SELECT q.id, q.name
    FROM (SELECT id, name FROM ods.users) q
);
INSERT INTO dwd.result (id, name)
SELECT v.user_key, v.display_name
FROM (SELECT user_key, display_name FROM dim.ddl_alias_view) v;
