INSERT INTO dwd.result (id, rn)
SELECT u.id,
       ROW_NUMBER() OVER (PARTITION BY LOWER(u.name) ORDER BY ABS(u.amount)) AS rn
FROM ods.users u;

INSERT INTO dwd.result (id, rn)
SELECT q.id, q.rn
FROM (
    SELECT u.id,
           ROW_NUMBER() OVER (PARTITION BY LOWER(u.name) ORDER BY ABS(u.amount)) AS rn
    FROM ods.users u
) q;

INSERT INTO dwd.result (id, rn)
SELECT u.id, ROW_NUMBER() OVER ranked AS rn
FROM ods.users u
WINDOW ranked AS (PARTITION BY LOWER(u.name) ORDER BY ABS(u.amount));

INSERT INTO dwd.result (amount)
SELECT SUM(u.amount) OVER (
    PARTITION BY LOWER(u.name)
    ORDER BY ABS(u.amount)
    ROWS BETWEEN 1 PRECEDING AND CURRENT ROW
)
FROM ods.users u;
