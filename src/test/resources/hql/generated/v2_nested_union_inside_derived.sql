-- 场景：派生表内 UNION ALL，外层按位置接收两分支字段后过滤。
insert overwrite table dwd.result(id, name, amount)
select combined.user_key, combined.display_name, combined.total_amount
from (
    select u.id as user_key, u.name as display_name, u.amount as total_amount
    from ods.users u
    union all
    select a.id, a.name, a.amount from ods.users_archive a
) combined
where combined.total_amount > 0;
