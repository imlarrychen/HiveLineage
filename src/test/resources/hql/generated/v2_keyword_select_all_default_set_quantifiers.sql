-- Explicit SELECT ALL is semantically the default; omitted set quantifiers mean DISTINCT.
INSERT INTO dwd.result(id) SELECT ALL id FROM ods.users;
INSERT INTO dwd.result(id) SELECT id FROM ods.users INTERSECT SELECT id FROM ods.users_archive;
INSERT INTO dwd.result(id) SELECT id FROM ods.users EXCEPT SELECT id FROM ods.users_archive;
INSERT INTO dwd.result(id) SELECT id FROM ods.users UNION SELECT id FROM ods.users_archive;
