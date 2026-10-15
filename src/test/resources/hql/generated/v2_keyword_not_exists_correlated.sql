-- NOT EXISTS preserves filtering input while the inserted value comes only from users.
INSERT OVERWRITE TABLE dwd.result(id)
SELECT u.id FROM ods.users u
WHERE NOT EXISTS (SELECT 1 FROM ods.events e WHERE e.user_id = u.id AND e.kind = 'blocked');
