-- 覆盖点：动态分区 + 静态分区 + lateral view explode + 别名字段回溯到 explode 参数
-- 预期：tag 的来源应回溯到 ods.user_base.tags；静态分区 source_system 来源常量，动态分区 dt 来源 ods.user_base.dt。
set hive.exec.dynamic.partition = true;
set hive.exec.dynamic.partition.mode = nonstrict;

insert overwrite table dwd.user_tag_detail partition(source_system = 'profile', dt)
select
       user_id
      ,tag
      ,dt
from ods.user_base
lateral view explode(tags) exploded_tags as tag
where dt = '${bizdate}';
