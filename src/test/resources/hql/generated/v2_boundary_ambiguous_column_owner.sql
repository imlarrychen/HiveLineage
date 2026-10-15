-- Unqualified amount belongs to both inputs; never invent a source owner.
INSERT INTO dwd.result(id, amount)
SELECT u.id, amount FROM ods.users u JOIN ods.orders o ON u.id = o.user_id;
