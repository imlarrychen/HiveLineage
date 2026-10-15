-- 场景：派生表内 INTERSECT，两分支同位置字段均保留血缘。
insert overwrite table dwd.result(id)
select shared_ids.id
from (
    select u.id from ods.users u
    intersect
    select a.id from ods.users_archive a
) shared_ids;
