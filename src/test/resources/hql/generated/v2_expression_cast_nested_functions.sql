-- CAST, nested builtin calls and null fallback keep known argument sources.
INSERT INTO dwd.result(id, name, amount)
SELECT CAST(u.id AS BIGINT), concat(upper(u.name), CAST(u.id AS STRING)), coalesce(u.amount, 0)
FROM (SELECT id, name, amount FROM ods.users) u;
