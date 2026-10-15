-- 场景：动态分区取嵌套查询最后一个输出，业务字段别名和分区字段分别回溯。
insert into dwd.result partition(dt) (id, name)
select q.user_key, q.display_name, q.business_day
from (select u.id as user_key, u.name as display_name, u.dt as business_day from ods.users u) q;
