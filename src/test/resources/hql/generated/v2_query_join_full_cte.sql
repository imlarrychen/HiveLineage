-- FULL OUTER JOIN with two coalesced output columns across a CTE boundary.
WITH current_users AS (SELECT id, name FROM ods.users)
INSERT INTO TABLE dwd.result (id, name)
SELECT COALESCE(u.id, a.id), COALESCE(u.name, a.name)
FROM current_users u FULL OUTER JOIN ods.users_archive a ON u.id = a.id;
