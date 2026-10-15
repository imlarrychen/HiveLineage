-- CROSS JOIN still keeps each projected value bound to its own source.
INSERT INTO TABLE dwd.result (id, dept_name)
SELECT q.id, d.dept_name
FROM (SELECT id FROM ods.users) q CROSS JOIN ods.departments d;
