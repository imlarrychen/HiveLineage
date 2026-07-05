-- 覆盖点：目标元数据缺失降级。
-- 预期：目标表 dwd.metadata_missing_target 不加入 mock metastore；表血缘保留，字段血缘用 select item 降级推断，并输出 METADATA_NOT_FOUND warning。
insert overwrite table dwd.metadata_missing_target
select user_id, user_name
from ods.user_base
where dt = '${bizdate}';
