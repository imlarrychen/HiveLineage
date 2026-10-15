-- Projection predicates exercise BETWEEN, IN-list, null-safe comparison, LIKE and regexp.
INSERT INTO dwd.result(id, rn, tag, name)
SELECT u.id,
       CASE WHEN u.id NOT BETWEEN 1 AND 9 OR u.amount IN (10, 20) THEN 1 ELSE 0 END,
       CASE WHEN u.name LIKE 'A%' AND u.name RLIKE '^[A-Z]' THEN u.name ELSE '' END,
       CASE WHEN u.name <=> NULL OR u.name REGEXP 'x' THEN u.name ELSE 'present' END
FROM ods.users u;
