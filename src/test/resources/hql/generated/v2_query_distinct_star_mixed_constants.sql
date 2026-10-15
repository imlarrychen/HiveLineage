-- Qualified star expands a derived output schema between DISTINCT and constant outputs.
INSERT INTO TABLE dwd.result (id, name, amount, dept_name, tag, rn, dt)
SELECT DISTINCT q.*, 'fixed-tag', 7, '2026-10-14'
FROM (SELECT id, name, amount, CAST(dept_id AS STRING) AS dept_name FROM ods.users) q;
