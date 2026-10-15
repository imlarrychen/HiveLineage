-- Scalar input in GROUP BY must remain an object dependency in every parse mode.
INSERT INTO dwd.result(id)
SELECT max(u.id) FROM ods.users u
GROUP BY (SELECT max(e.user_id) FROM ods.events e);
