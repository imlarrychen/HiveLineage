-- REPLACE retains partition keys, so data columns may not reuse their names.
create table dwd.ddl_partition_collision (id bigint, name string) partitioned by (dt string);
alter table dwd.ddl_partition_collision replace columns (id bigint, DT string);
insert overwrite table dwd.result(id,name,dt) select q.id,q.name,q.dt from (select * from dwd.ddl_partition_collision) q;
