-- 覆盖点：临时表优先于同名 metastore 表/视图。
-- 预期：tmp_shadow_user 在 metastore 中也存在为视图；后续 insert 应使用本脚本 create temporary table 的 ods.user_base 血缘，而不是 metastore 视图定义。
create temporary table tmp_shadow_user as
select
       user_id
      ,user_name
from ods.user_base
where dt = '${bizdate}';

insert overwrite table dwd.temp_shadow_target(user_id, user_name)
select
       user_id
      ,user_name
from tmp_shadow_user;
