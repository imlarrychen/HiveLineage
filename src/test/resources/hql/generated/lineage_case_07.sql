-- 覆盖点：create view as select + alter view as select + 视图作为输入表
-- 预期：create/alter view 本身产生正式血缘；后续读取 dim.v_user_profile 时，expandViewLineage=true 应保留视图输入，并补充 ods.user_base / ods.user_dept 到视图的定义血缘。
create view if not exists dim.v_user_profile as
select
       u.user_id
      ,u.user_name
      ,d.dept_name
from ods.user_base u
left join ods.user_dept d
on u.dept_id = d.dept_id;

alter view dim.v_user_profile as
select
       u.user_id
      ,upper(u.user_name) as user_name
      ,d.dept_name
from ods.user_base u
left join ods.user_dept d
on u.dept_id = d.dept_id
where u.status = 1;

insert overwrite table dwd.active_user_profile(user_id, user_name, dept_name)
select
       user_id
      ,user_name
      ,dept_name
from dim.v_user_profile;
