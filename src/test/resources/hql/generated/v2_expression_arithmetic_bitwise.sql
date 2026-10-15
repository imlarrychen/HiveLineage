-- Arithmetic, unary operators and bitwise operators through a derived query.
INSERT INTO dwd.result(id, amount, rn)
SELECT x.id + 1, -(x.amount * 2 + x.id / 10) % 7, (x.id & 3) | (x.id ^ 1)
FROM (SELECT id, amount FROM ods.users) x;
