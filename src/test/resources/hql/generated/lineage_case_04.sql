-- 覆盖点：select * 元数据展开 + insert overwrite table 未显式目标字段
-- 预期：字段血缘必须依赖元数据，按字段位置把 ods.user_base 的完整字段列表展开到 dwd.user_base_snapshot。
insert overwrite table dwd.user_base_snapshot
select *
from ods.user_base
where dt = '${bizdate}';
