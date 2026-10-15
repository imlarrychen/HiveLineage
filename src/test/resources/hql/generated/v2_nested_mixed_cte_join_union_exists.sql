-- 场景：CTE 集合查询、聚合 CTE、两个 JOIN、EXISTS 混合；物理来源五张表。
with combined_users as (
    select u.id, u.name, u.dept_id from ods.users u
    union all
    select a.id, a.name, a.dept_id from ods.users_archive a
), paid_totals as (
    select o.user_id, sum(o.amount) as total_amount
    from ods.orders o where o.status = 'PAID' group by o.user_id
)
insert overwrite table dwd.result(id, name, amount, dept_name)
select selected.id, selected.name, p.total_amount, d.dept_name
from (
    select c.id, c.name, c.dept_id from combined_users c
) selected
join paid_totals p on selected.id = p.user_id
left join ods.departments d on selected.dept_id = d.dept_id
where exists (
    select 1 from ods.events e where e.user_id = selected.id and e.kind = 'active'
);
