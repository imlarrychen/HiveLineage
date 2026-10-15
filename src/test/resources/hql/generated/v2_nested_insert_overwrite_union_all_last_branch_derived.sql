-- 场景：直接 INSERT OVERWRITE 接 UNION ALL，末分支 FROM 派生表，别名按位置对齐。
insert overwrite table dwd.result(id, name)
select u.id, u.name
from ods.users u
union all
select last_branch.user_key, last_branch.display_name
from (
    select a.id as user_key, a.name as display_name
    from ods.users_archive a
    where a.amount > 0
) last_branch;
