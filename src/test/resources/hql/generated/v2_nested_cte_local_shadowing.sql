-- 场景：派生表内 WITH 与外部同名 CTE；局部 users_archive 不覆盖外部 orders。
with scoped_rows as (
    select o.user_id as id, o.amount from ods.orders o
)
insert overwrite table dwd.result(id, name, amount)
select local_rows.id, local_rows.name, outer_rows.amount
from (
    with scoped_rows as (
        select a.id, a.name from ods.users_archive a
    )
    select s.id, s.name from scoped_rows s
) local_rows
join scoped_rows outer_rows on local_rows.id = outer_rows.id;
