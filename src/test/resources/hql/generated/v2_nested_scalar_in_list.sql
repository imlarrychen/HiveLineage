-- 场景：IN 常量列表含 scalar 子查询，外层 CASE 的条件来源也属于字段血缘。
insert overwrite table dwd.result(id, name)
select u.id,
       case when u.id in (0, (select max(o.user_id) from ods.orders o))
            then u.name else 'other' end as selected_name
from ods.users u;
