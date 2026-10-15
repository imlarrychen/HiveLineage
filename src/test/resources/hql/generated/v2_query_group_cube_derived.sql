-- CUBE over a derived query.
INSERT INTO TABLE dwd.result (id, dt, amount)
SELECT q.dept_id, q.dt, SUM(q.amount)
FROM (SELECT dept_id, dt, amount FROM ods.users) q
GROUP BY q.dept_id, q.dt WITH CUBE;
