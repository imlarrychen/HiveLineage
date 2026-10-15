-- 场景：SELECT scalar 子查询按外层用户关联；内层筛选影响行集，值来自 orders.amount。
insert overwrite table dwd.result(id, name, amount)
select u.id, u.name,
       (select sum(o.amount) from ods.orders o where o.user_id = u.id and o.status = 'PAID') as paid_amount
from ods.users u;
