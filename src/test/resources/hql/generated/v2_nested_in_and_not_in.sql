-- 场景：WHERE IN 子查询中再嵌 NOT IN 子查询；筛选来源不冒充投影字段来源。
insert overwrite table dwd.result(id, name)
select u.id, u.name
from ods.users u
where u.id in (
    select o.user_id from ods.orders o
    where o.user_id not in (
        select e.user_id from ods.events e where e.kind = 'blocked'
    )
);
