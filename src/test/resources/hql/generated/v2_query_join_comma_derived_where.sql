-- Comma join with a WHERE join predicate and independent derived-query scopes.
INSERT INTO TABLE dwd.result (id, rn)
SELECT u.id, o.order_id
FROM (SELECT id FROM ods.users) u, (SELECT order_id FROM ods.orders) o
WHERE u.id = o.order_id;
