-- Named RANGE window reused by both aggregate and ranking outputs.
WITH candidates AS (SELECT id, dept_id, amount FROM ods.users)
INSERT INTO TABLE dwd.result (id, amount, rn)
SELECT id, SUM(amount) OVER w, ROW_NUMBER() OVER w
FROM candidates
WINDOW w AS (PARTITION BY dept_id ORDER BY amount RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW);
