-- 场景：三层 FROM 派生表连续重命名与算术加工；来源始终回溯 ods.users。
insert overwrite table dwd.result(id, name, amount)
select level_three.user_key, level_three.display_name, level_three.final_amount
from (
    select level_two.user_key, level_two.display_name, level_two.adjusted_amount + 5 as final_amount
    from (
        select level_one.user_key, upper(level_one.display_name) as display_name,
               level_one.base_amount * 2 as adjusted_amount
        from (
            select u.id as user_key, u.name as display_name, u.amount as base_amount
            from ods.users u
            where u.amount > 0
        ) level_one
    ) level_two
) level_three;
