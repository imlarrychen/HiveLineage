-- 场景：SELECT 非关联 scalar 聚合子查询；金额来源 orders，不串到外层 users。
insert overwrite table dwd.result(id, name, amount)
select u.id, u.name, (select max(o.amount) from ods.orders o) as maximum_order_amount
from ods.users u;
