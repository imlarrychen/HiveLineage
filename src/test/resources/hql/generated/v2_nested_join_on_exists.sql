-- 场景：JOIN ON 中 EXISTS 再包派生表，并关联外层 users；筛选来源进入表血缘。
insert overwrite table dwd.result(id, name, dept_name)
select u.id, u.name, d.dept_name
from ods.users u
join ods.departments d
  on u.dept_id = d.dept_id
 and exists (
     select 1
     from (
         select e.user_id, e.kind from ods.events e
     ) active_events
     where active_events.user_id = u.id and active_events.kind = 'active'
 );
