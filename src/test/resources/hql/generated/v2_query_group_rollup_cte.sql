-- ROLLUP over a CTE; grouping keys and aggregate inputs map independently.
WITH daily AS (SELECT dept_id, dt, amount FROM ods.users)
INSERT INTO TABLE dwd.result (id, dt, amount)
SELECT dept_id, dt, SUM(amount) FROM daily
GROUP BY dept_id, dt WITH ROLLUP;
