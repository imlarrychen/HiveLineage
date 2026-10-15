-- A scalar subquery in window ordering must remain an input even in TABLE_ONLY.
INSERT INTO dwd.result(id, amount)
SELECT u.id, sum(u.amount) OVER (ORDER BY (SELECT max(o.amount) FROM ods.orders o))
FROM ods.users u;
