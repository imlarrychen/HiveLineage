-- 场景：SELECT scalar 内再嵌两层派生表与聚合，只有 orders.amount 作为金额值源。
insert overwrite table dwd.result(id, amount)
select u.id,
       (select max(second_level.amount)
        from (
            select first_level.amount
            from (
                select o.amount from ods.orders o where o.status = 'PAID'
            ) first_level
        ) second_level) as maximum_paid_amount
from ods.users u;
