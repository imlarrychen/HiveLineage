-- 场景：CTAS 从嵌套聚合建表，随后读取已注册定义；不依赖 metastore 预置新表。
create table dwd.statement_order_totals stored as parquet as
select totals.user_id as id, totals.total_amount as amount
from (
    select o.user_id, sum(o.amount) as total_amount
    from ods.orders o where o.status='PAID' group by o.user_id
) totals;

insert overwrite table dwd.result(id, amount)
select t.id, t.amount from dwd.statement_order_totals t;
