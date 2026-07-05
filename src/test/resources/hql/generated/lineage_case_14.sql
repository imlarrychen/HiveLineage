-- 覆盖点：inline FROM 子查询、聚合表达式和子查询别名字段回溯。
-- 预期：dwd.inline_subquery_target 的 user_id 来源 ods.order_detail.user_id；order_cnt 来源 count(*) 表达式。
insert overwrite table dwd.inline_subquery_target(user_id, order_cnt)
select
       x.user_id
      ,x.order_cnt
from (
    select
           user_id
          ,count(*) as order_cnt
    from ods.order_detail
    where pay_status = 'PAID'
    group by user_id
) x
where x.order_cnt > 0;
