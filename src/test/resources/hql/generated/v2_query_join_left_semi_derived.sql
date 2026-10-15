-- LEFT SEMI JOIN: the right relation affects membership, while output values stay on the left.
INSERT INTO TABLE dwd.result (id, name)
SELECT u.id, u.name
FROM ods.users u LEFT SEMI JOIN
    (SELECT user_id FROM ods.orders WHERE status = 'paid') o ON u.id = o.user_id;
