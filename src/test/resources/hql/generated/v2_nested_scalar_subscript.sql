-- 场景：scalar 返回数组后下标取值，events.kind 来源不能丢失。
insert overwrite table dwd.result(id, tag)
select u.id, (select collect_list(e.kind) from ods.events e)[0] as first_event_kind
from ods.users u;
