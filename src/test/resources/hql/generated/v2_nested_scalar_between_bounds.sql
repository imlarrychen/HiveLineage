-- 场景：CASE 条件 BETWEEN 的上下界均是 scalar；TABLE_ONLY 也必须遍历这些子查询。
insert overwrite table dwd.result(id, amount)
select u.id,
       case when u.amount between (select min(o.amount) from ods.orders o)
                              and (select max(a.amount) from ods.users_archive a)
            then u.amount else 0 end as bounded_amount
from ods.users u;
