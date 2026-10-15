-- Distinct native operators cannot hide behind the same id source column; CASE omits ELSE.
INSERT INTO dwd.result(id, name, amount, dept_name, tag, rn, dt)
SELECT +u.id, ~u.id, u.id DIV 2,
       u.id <> 3 OR u.id != 5,
       u.id < 2 AND u.id <= 3,
       CASE WHEN u.id IS NULL OR u.id NOT IN (1, 2) THEN +u.id END,
       u.id BETWEEN 0 AND 1
FROM ods.users u;
