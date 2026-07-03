set hivevar:VARI = '20';
set bizdate = 20260701;

insert into test_a(a1, a2)
select b1 + b2, nvl(b3, 0)
from test_b
where dt = '${hiveconf:bizdate}'
  and env = '@{run_env}';

insert overwrite table dwd.user_profile
select a.user_id, b.dept_name
from ods.user_base a
left join ods.user_dept b
on a.dept_id = b.dept_id;

insert overwrite table dwd.union_target
select id, name from ods.union_source_a
union all
select id, name from ods.union_source_b;
