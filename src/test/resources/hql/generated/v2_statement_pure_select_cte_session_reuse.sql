-- 场景：兼容口径下前序纯 SELECT 忽略正式输出，但 CTE 定义可供后续语句复用。
with statement_saved_users as (
    select u.id as user_key, upper(u.name) as display_name from ods.users u
)
select s.user_key, s.display_name from statement_saved_users s;

insert overwrite table dwd.result(id, name)
select s.user_key, s.display_name from statement_saved_users s;
