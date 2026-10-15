-- GROUPING SETS includes multiple key combinations and the empty grouping set.
WITH daily AS (SELECT dept_id, dt, amount FROM ods.users)
INSERT INTO TABLE dwd.result (id, dt, amount)
SELECT dept_id, dt, SUM(amount) FROM daily
GROUP BY dept_id, dt GROUPING SETS ((dept_id, dt), (dept_id), (dt), ());
