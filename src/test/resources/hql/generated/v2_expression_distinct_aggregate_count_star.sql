-- DISTINCT aggregate arguments and COUNT(*) have different field provenance.
INSERT INTO dwd.result(id, amount, rn)
SELECT o.user_id, sum(DISTINCT o.amount), count(*)
FROM ods.orders o GROUP BY o.user_id;
