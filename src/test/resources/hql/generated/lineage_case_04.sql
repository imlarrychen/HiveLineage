-- 覆盖点：select * 元数据展开 + insert overwrite table 未显式目标字段
-- 预期：字段血缘必须依赖元数据把 ods.user_base 的 user_id,user_name,dept_id,tags,dt 展开到 dwd.user_base_snapshot。
insert overwrite table dwd.user_base_snapshot
select *
from ods.user_base
where dt = '${bizdate}';
