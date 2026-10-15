-- 场景：直接 INSERT OVERWRITE 的 UNION ALL 两分支均嵌套，分别包含函数和聚合。
insert overwrite table dwd.result(id, name)
select first_branch.user_key, upper(first_branch.display_name)
from (
    select u.id as user_key, u.name as display_name from ods.users u
) first_branch
union all
select last_branch.archive_key, last_branch.latest_name
from (
    select inner_branch.id as archive_key, max(inner_branch.name) as latest_name
    from (
        select a.id, a.name from ods.users_archive a where a.amount >= 0
    ) inner_branch
    group by inner_branch.id
) last_branch;
