-- 场景：JOIN 两侧都是派生表，右侧聚合，外层过滤聚合别名。
insert overwrite table dwd.result(id, name, amount)
select user_rows.id, user_rows.name, paid_orders.total_amount
from (
    select u.id, u.name
    from ods.users u
    where u.dt >= '2026-10-01'
) user_rows
left join (
    select o.user_id, sum(o.amount) as total_amount
    from ods.orders o
    where o.status = 'PAID'
    group by o.user_id
) paid_orders on user_rows.id = paid_orders.user_id
where paid_orders.total_amount > 0;
