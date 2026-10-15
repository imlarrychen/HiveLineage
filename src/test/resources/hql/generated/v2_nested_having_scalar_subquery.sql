-- 场景：GROUP BY/HAVING 中嵌非关联 scalar，目标聚合值来自外层 users.amount。
insert overwrite table dwd.result(id, amount)
select u.id, sum(u.amount) as total_amount
from ods.users u
where u.amount > 0
group by u.id
having sum(u.amount) > (select avg(o.amount) from ods.orders o where o.status = 'PAID');
