-- The scalar query is syntactically valid but its width is invalid; retain diagnostic.
INSERT INTO dwd.result(id, amount)
SELECT u.id, (SELECT o.amount, o.order_id FROM ods.orders o)
FROM ods.users u;
