-- 覆盖点：create temporary table as select + 临时表作为最终目标上游 + 临时字段加工串联
-- 预期：默认可不返回临时表语句结果，但后续 dwd.user_profile_enriched 的血缘应展开到 ods.user_base 和 ods.user_dept。
create temporary table tmp_user_profile as
select
       u.user_id
      ,u.user_name
      ,u.dept_id
      ,d.dept_name
      ,case when u.status = 1 then 'ACTIVE' else 'INACTIVE' end as user_status
from ods.user_base u
left join ods.user_dept d
on u.dept_id = d.dept_id
where u.dt = '${bizdate}';

insert overwrite table dwd.user_profile_enriched(user_id, user_name, dept_name, user_status, process_date)
select
       user_id
      ,user_name
      ,dept_name
      ,user_status
      ,'${bizdate}' as process_date
from tmp_user_profile;
