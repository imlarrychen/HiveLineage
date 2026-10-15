-- Simple CASE and searched CASE retain condition and value column dependencies.
INSERT INTO dwd.result(id, name, amount)
SELECT u.id,
       CASE WHEN u.id > 0 AND u.name IS NOT NULL THEN u.name ELSE 'anonymous' END,
       CASE o.status WHEN 1 THEN o.amount WHEN 2 THEN -o.amount ELSE 0 END
FROM ods.users u JOIN ods.orders o ON u.id = o.user_id;
