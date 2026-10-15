-- LEFT OUTER JOIN against a grouped CTE: unrelated join keys must not enter output values.
WITH order_totals AS (
    SELECT user_id, SUM(amount) AS total_amount
    FROM ods.orders WHERE status = 'paid' GROUP BY user_id
)
INSERT INTO TABLE dwd.result (id, amount)
SELECT u.id, o.total_amount
FROM ods.users u LEFT OUTER JOIN order_totals o ON u.id = o.user_id;
