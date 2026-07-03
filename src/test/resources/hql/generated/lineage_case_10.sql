-- 覆盖点：默认忽略语句 + 普通建表 DDL + insert values + insert overwrite directory + 纯 select + 最终可解析 insert
-- 预期：纯 select、普通 DDL、insert values、insert overwrite directory 默认不返回正式血缘；最后一条 insert 正常产生血缘。
use dwd;

create table if not exists dwd.ignore_me
(
     id   string
    ,name string
)
stored as parquet;

select id, name
from ods.user_base
where dt = '${bizdate}';

insert overwrite table dwd.ignore_me values ('1', 'tom');

insert overwrite directory '/tmp/lineage_ignore'
select id, name
from ods.user_base;

insert overwrite table dwd.parse_error_target(user_id)
select user_id from ods.user_base;
