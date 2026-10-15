-- 场景：一个 CTE 两次引用的自连接，关系实例与输出槽必须独立。
with user_rows as (
    select u.id, u.name, u.amount from ods.users u
)
insert overwrite table dwd.result(id, name, amount)
select first_row.id, second_row.name, first_row.amount + second_row.amount
from user_rows first_row
join user_rows second_row on first_row.id = second_row.id;
