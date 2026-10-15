-- Opaque UDF retains proven arguments and explicitly reports unknown behavior.
INSERT INTO dwd.result(id, amount)
SELECT u.id, business_score(u.amount, u.id) FROM ods.users u;
