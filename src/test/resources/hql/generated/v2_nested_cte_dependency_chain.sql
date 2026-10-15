-- 场景：三层 CTE 依赖链与派生表交错；加工字段不丢失物理来源。
with raw_users as (
    select u.id, u.name, u.amount from ods.users u
), adjusted_users as (
    select r.id, upper(r.name) as name, r.amount * 2 as amount from raw_users r
), active_users as (
    select a.id, a.name, a.amount from adjusted_users a where a.amount > 10
)
insert overwrite table dwd.result(id, name, amount)
select final_users.id, final_users.name, final_users.amount
from (
    select a.id, a.name, a.amount from active_users a
) final_users;
