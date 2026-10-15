-- Named window specifications can also contain scalar subquery dependencies.
INSERT INTO dwd.result(id, amount)
SELECT u.id, sum(u.amount) OVER w
FROM ods.users u
WINDOW w AS (ORDER BY (SELECT max(o.amount) FROM ods.orders o));
