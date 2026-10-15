-- EXCEPT DISTINCT binds output positions across two derived-query branches.
INSERT INTO TABLE dwd.result (id, name)
SELECT s.id, s.name FROM (
    SELECT u.id, u.name FROM (SELECT id, name FROM ods.users WHERE amount > 0) u
    EXCEPT DISTINCT
    SELECT a.id, a.name FROM (SELECT id, name FROM ods.users_archive WHERE amount >= 0) a
) s;
