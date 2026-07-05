-- 覆盖点：where exists 子查询输入表。
-- 预期：主查询来源 ods.user_base；exists 子查询中的 ods.order_detail 也进入 inputObjects/table lineage source。
insert overwrite table dwd.exists_filter_target(user_id, user_name)
select
       u.user_id
      ,u.user_name
from ods.user_base u
where exists (
    select 1
    from ods.order_detail o
    where o.user_id = u.user_id
      and o.pay_status = 'PAID'
);
