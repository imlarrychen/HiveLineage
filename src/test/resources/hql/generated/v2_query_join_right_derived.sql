-- RIGHT OUTER JOIN and a value expression using both sides.
INSERT INTO TABLE dwd.result (id, dept_name)
SELECT COALESCE(q.id, d.dept_id), d.dept_name
FROM (SELECT id, dept_id FROM ods.users) q
RIGHT OUTER JOIN ods.departments d ON q.dept_id = d.dept_id;
