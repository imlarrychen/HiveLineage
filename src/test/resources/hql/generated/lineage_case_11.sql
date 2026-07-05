-- 覆盖点：单条 SQL 解析失败仍返回失败结果，后续语句继续解析。
-- 预期：第一条 insert 进入 failed statement；第二条 insert 仍产生 dwd.parse_recovery_target 的正式血缘。
insert into dwd.parse_error_target select from;

insert overwrite table dwd.parse_recovery_target(user_id, user_name)
select user_id, user_name
from ods.user_base
where dt = '${bizdate}';
