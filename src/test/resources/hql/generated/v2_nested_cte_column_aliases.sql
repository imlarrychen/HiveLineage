-- 场景：CTE 显式列别名覆盖 SELECT 别名，依赖链再次更名。
with named_users(user_key, display_name, gross_amount) as (
    select u.id, u.name, u.amount * 2 from ods.users u
), renamed_users(final_key, final_name, final_amount) as (
    select n.user_key, n.display_name, n.gross_amount + 1 from named_users n
)
insert overwrite table dwd.result(id, name, amount)
select r.final_key, r.final_name, r.final_amount from renamed_users r;
