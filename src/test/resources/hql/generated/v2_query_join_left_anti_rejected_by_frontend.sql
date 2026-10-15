-- Boundary: Hive 4.0.1 lexer rejects LEFT ANTI JOIN although native IR models it.
INSERT INTO TABLE dwd.result (id, name)
SELECT u.id, u.name
FROM ods.users u LEFT ANTI JOIN
    (SELECT user_id FROM ods.events WHERE kind = 'disabled') e ON u.id = e.user_id;
