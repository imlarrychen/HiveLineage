-- Query tails and sample forms preserve output values; these fixtures do not execute SQL.
INSERT INTO TABLE dwd.result (id, name)
SELECT q.id, q.name
FROM (SELECT id, name FROM ods.users TABLESAMPLE(BUCKET 1 OUT OF 4 ON id)) q
ORDER BY q.id DESC NULLS LAST LIMIT 2, 5;

INSERT INTO TABLE dwd.result (id, name)
SELECT id, name FROM ods.users
DISTRIBUTE BY dept_id SORT BY amount DESC NULLS FIRST LIMIT 10;

INSERT INTO TABLE dwd.result (id, name)
SELECT id, name FROM ods.users CLUSTER BY dept_id;

INSERT INTO TABLE dwd.result (id, name)
SELECT id, name FROM ods.users TABLESAMPLE(10 PERCENT);

INSERT INTO TABLE dwd.result (id, name)
SELECT id, name FROM ods.users TABLESAMPLE(4 ROWS);

INSERT INTO TABLE dwd.result (id, name)
SELECT id, name FROM ods.users TABLESAMPLE(100B);

-- A hidden sort expression must not become an extra result column or value dependency.
INSERT INTO TABLE dwd.result (id, name)
SELECT q.id, q.name FROM (SELECT id, name, amount FROM ods.users) q
ORDER BY q.amount DESC LIMIT 5;

-- Window computations introduced by a sort expression remain hidden and fully bound.
INSERT INTO TABLE dwd.result (id, name)
SELECT id, name FROM ods.users
ORDER BY ROW_NUMBER() OVER (PARTITION BY dept_id ORDER BY amount) LIMIT 5;
