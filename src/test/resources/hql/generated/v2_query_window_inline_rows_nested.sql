-- Inline windows retain partition and ordering dependencies across a derived query.
INSERT INTO TABLE dwd.result (id, amount, rn)
SELECT q.user_id,
       SUM(q.amount) OVER (PARTITION BY q.user_id ORDER BY q.dt ROWS BETWEEN 2 PRECEDING AND CURRENT ROW),
       ROW_NUMBER() OVER (PARTITION BY q.user_id ORDER BY q.amount DESC)
FROM (SELECT user_id, amount, dt FROM ods.orders WHERE status = 'paid') q;
