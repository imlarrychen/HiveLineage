-- USING is parsed but its native column-list condition has no declared binding rule.
INSERT INTO dwd.result(id)
SELECT u.id FROM ods.users u JOIN ods.users_archive a USING(id);
