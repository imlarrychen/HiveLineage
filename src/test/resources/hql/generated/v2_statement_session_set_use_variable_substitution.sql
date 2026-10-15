-- 场景：SET 的 hivevar/hiveconf 会话变量、USE 默认库以及变量值在后续 SELECT 中生效。
set hivevar:source_table=users;
set bizdate=20261014;
use ods;

insert overwrite table dwd.result(id, name, dt)
select u.id, u.name, '${hiveconf:bizdate}'
from ${hivevar:source_table} u where u.dt='${bizdate}';
