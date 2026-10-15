-- Recursive window inheritance must merge partitions, ordering and complete frame bounds.
INSERT INTO dwd.result(id, amount)
SELECT id, SUM(amount) OVER w3 FROM ods.users
WINDOW w1 AS (PARTITION BY dept_id), w2 AS (w1 ORDER BY amount),
       w3 AS (w2 ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING);

-- Both frame edges are bounded FOLLOWING offsets.
INSERT INTO dwd.result(id, amount)
SELECT id, SUM(amount) OVER (PARTITION BY dept_id ORDER BY amount ROWS BETWEEN 1 FOLLOWING AND 3 FOLLOWING)
FROM ods.users;

-- A single ROWS boundary has a default CURRENT ROW end in bound IR.
INSERT INTO dwd.result(id, amount)
SELECT id, SUM(amount) OVER (PARTITION BY dept_id ORDER BY amount ROWS 2 PRECEDING)
FROM ods.users;

-- RANGE bounds retain a distinct unit and an unbounded following endpoint.
INSERT INTO dwd.result(id, amount)
SELECT id, SUM(amount) OVER (PARTITION BY dept_id ORDER BY amount RANGE BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING)
FROM ods.users;

-- A scalar in an inherited partition must survive both object and field binding.
INSERT INTO dwd.result(id, amount)
SELECT u.id, SUM(u.amount) OVER w2 FROM ods.users u
WINDOW w1 AS (PARTITION BY (SELECT max(e.user_id) FROM ods.events e)),
       w2 AS (w1 ORDER BY u.amount ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW);
