-- 场景：两层 EXISTS，最内层同时关联直接父层 orders 和祖先 users。
insert overwrite table dwd.result(id, name)
select u.id, u.name
from ods.users u
where exists (
    select 1 from ods.orders o
    where o.user_id = u.id
      and exists (
          select 1 from ods.events e
          where e.user_id = o.user_id and e.user_id = u.id and e.kind = 'purchase'
      )
);
