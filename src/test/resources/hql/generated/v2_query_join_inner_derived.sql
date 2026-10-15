-- INNER JOIN with an independently filtered derived relation.
INSERT OVERWRITE TABLE dwd.result (id, name, dept_name)
SELECT q.id, q.name, d.dept_name
FROM (SELECT id, name, dept_id FROM ods.users WHERE amount > 0) q
INNER JOIN ods.departments d ON q.dept_id = d.dept_id;
