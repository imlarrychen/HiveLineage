-- 覆盖点：CTAS + CTE + 聚合函数 + group by + count(*)
-- 预期：create table as select 产生正式血缘；total_amount 来源 ods.order_detail.amount；order_cnt 是 count(*)，依赖输入对象行集。
create table if not exists dwd.user_order_summary
stored as parquet
as
with paid_orders as (
    select
           user_id
          ,order_id
          ,amount
          ,pay_time
    from ods.order_detail
    where pay_status = 'PAID'
)
select
       user_id
      ,sum(amount) as total_amount
      ,count(*) as order_cnt
      ,max(pay_time) as last_pay_time
from paid_orders
group by user_id;
