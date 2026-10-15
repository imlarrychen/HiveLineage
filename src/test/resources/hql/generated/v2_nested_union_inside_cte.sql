-- 场景：CTE 内 UNION DISTINCT，派生表再连接部门表。
with combined_users as (
    select u.id, u.name, u.dept_id from ods.users u
    union
    select a.id, a.name, a.dept_id from ods.users_archive a
)
insert overwrite table dwd.result(id, name, dept_name)
select selected_users.id, selected_users.name, d.dept_name
from (
    select c.id, c.name, c.dept_id from combined_users c
) selected_users
join ods.departments d on selected_users.dept_id = d.dept_id;
