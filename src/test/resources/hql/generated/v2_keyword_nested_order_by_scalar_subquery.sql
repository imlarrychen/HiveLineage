-- Scalar input in ORDER BY must remain an object dependency in every parse mode.
INSERT INTO dwd.result(id)
SELECT u.id FROM ods.users u
ORDER BY (SELECT max(e.user_id) FROM ods.events e);
