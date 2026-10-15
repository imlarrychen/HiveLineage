-- 场景：scalar 分别嵌入 CASE 条件、CAST、COALESCE，保留条件与值来源。
insert overwrite table dwd.result(id, amount)
select u.id,
       case when u.amount > (select avg(o.amount) from ods.orders o)
            then cast((select max(a.amount) from ods.users_archive a) as double)
            else coalesce(u.amount, 0)
       end as chosen_amount
from ods.users u;
