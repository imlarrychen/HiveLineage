-- 覆盖点：left semi join 和 full outer join。
-- 预期：left semi join 保留过滤表 ods.order_detail；full outer join 同时保留左右两侧来源表。
insert overwrite table dwd.semi_join_target(user_id, user_name)
select
       u.user_id
      ,u.user_name
from ods.user_base u
left semi join ods.order_detail o
on u.user_id = o.user_id
and o.pay_status = 'PAID';

insert overwrite table dwd.full_outer_join_target(user_id, profile_name, feature_status)
select
       coalesce(u.user_id, f.user_id) as user_id
      ,u.user_name as profile_name
      ,f.status as feature_status
from ods.user_base u
full outer join ods.user_feature f
on u.user_id = f.user_id
and f.dt = '${bizdate}';
