-- Parsed partition operations retain an explicit unknown object effect.
create table dwd.ddl_partition_actions (id bigint) partitioned by (dt string);
alter table dwd.ddl_partition_actions add if not exists partition (dt='2026-10-15') location '/warehouse/ddl/20261015' partition (dt='2026-10-16');
alter table dwd.ddl_partition_actions drop if exists partition (dt='2026-10-15') purge;
alter table dwd.ddl_partition_actions partition (dt='2026-10-16') rename to partition (dt='2026-10-17');
insert overwrite table dwd.result(id,dt) select * from dwd.ddl_partition_actions;
