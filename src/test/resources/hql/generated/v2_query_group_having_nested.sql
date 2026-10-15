-- Aggregation over a derived query, DISTINCT aggregate argument, and HAVING.
INSERT INTO TABLE dwd.result (id, amount, rn)
SELECT q.dept_id, SUM(q.amount), COUNT(DISTINCT q.id)
FROM (SELECT id, dept_id, amount FROM ods.users WHERE amount IS NOT NULL) q
GROUP BY q.dept_id HAVING SUM(q.amount) > 100;
