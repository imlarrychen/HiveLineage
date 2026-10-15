-- Typed literals and adjacent string tokens retain their kinds and constant assignments.
INSERT INTO dwd.result(id, name, amount, dept_name, tag, rn, dt)
SELECT TRUE, 'first' 'second', FALSE, DATE '2026-10-15', TIMESTAMP '2026-10-15 01:02:03', 42, NULL
FROM ods.users;
