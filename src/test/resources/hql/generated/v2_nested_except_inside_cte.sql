-- 场景：CTE 内 EXCEPT，再由外层派生表投影。
with remaining_users as (
    select u.id from ods.users u
    except
    select a.id from ods.users_archive a
)
insert overwrite table dwd.result(id)
select remaining_ids.id
from (
    select r.id from remaining_users r
) remaining_ids;
