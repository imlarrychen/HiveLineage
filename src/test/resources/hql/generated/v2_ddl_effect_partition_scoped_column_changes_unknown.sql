-- A partition-specific schema is not substituted for the table schema.
create table dwd.ddl_partition_columns (id bigint) partitioned by (dt string);
alter table dwd.ddl_partition_columns partition (dt='2026-10-15') add columns (name string);
alter table dwd.ddl_partition_columns partition (dt='2026-10-15') replace columns (new_id bigint);
alter table dwd.ddl_partition_columns partition (dt='2026-10-15') change id partition_id bigint first;
insert overwrite table dwd.result(id,dt) select * from dwd.ddl_partition_columns;
insert overwrite table dwd.result(id) select id from ods.users;
