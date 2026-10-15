-- 场景：直接 INSERT OVERWRITE 接 UNION ALL，首分支 FROM 两层派生表，末分支直接查表。
insert overwrite table dwd.result(id, name)
select first_branch.user_key, first_branch.display_name
from (
    select inner_branch.renamed_id as user_key, inner_branch.renamed_name as display_name
    from (
        select u.id as renamed_id, u.name as renamed_name
        from ods.users u
    ) inner_branch
) first_branch
union all
select a.id, a.name from ods.users_archive a;
