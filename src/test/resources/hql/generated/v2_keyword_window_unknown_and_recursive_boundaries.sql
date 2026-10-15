-- Unknown base windows are accepted syntactically and explicitly partial during binding.
INSERT INTO dwd.result(id, amount)
SELECT id, SUM(amount) OVER missing FROM ods.users;

-- Window inheritance cycles must be diagnosed without recursion overflow.
INSERT INTO dwd.result(id, amount)
SELECT id, SUM(amount) OVER w1 FROM ods.users
WINDOW w1 AS (w2 PARTITION BY dept_id), w2 AS (w1 ORDER BY amount);
