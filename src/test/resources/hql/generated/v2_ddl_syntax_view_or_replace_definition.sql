-- OR REPLACE 保留语法选项；后续定义应切换到 archive 的来源。
CREATE VIEW dim.ddl_replace_view AS SELECT id, name FROM ods.users;
CREATE OR REPLACE VIEW dim.ddl_replace_view (user_key, display_name)
COMMENT 'replacement definition'
AS SELECT q.id, q.name FROM (SELECT id, name FROM ods.users_archive) q;
INSERT INTO dwd.result (id, name)
SELECT user_key, display_name FROM dim.ddl_replace_view;
