-- A field accessor wrapping a scalar subquery must not hide its physical table.
INSERT INTO dwd.result(id, name)
SELECT u.id, (SELECT a.payload FROM ods.users_archive a).label
FROM ods.users u;
