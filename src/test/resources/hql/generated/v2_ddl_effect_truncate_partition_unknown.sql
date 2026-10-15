-- Partition truncation invalidates data certainty of this object, without claiming a data write.
create table dwd.ddl_truncate (id bigint) partitioned by (dt string);
truncate table dwd.ddl_truncate partition (dt='2026-10-15');
insert overwrite table dwd.result(id,dt) select * from dwd.ddl_truncate;
insert overwrite table dwd.result(id) select id from ods.users;
