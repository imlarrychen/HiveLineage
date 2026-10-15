-- Partition keys remain last and keep their role across successive table schema changes.
create table dwd.ddl_partition_roles (id bigint, name string) partitioned by (dt string, region string);
alter table dwd.ddl_partition_roles add columns (amount decimal(12,2));
alter table dwd.ddl_partition_roles replace columns (id bigint, amount decimal(12,2));
alter table dwd.ddl_partition_roles add columns (name string);
insert overwrite table dwd.result(id,amount,name,dt,tag) select q.id,q.amount,q.name,q.dt,q.region as tag from (select * from dwd.ddl_partition_roles) q;
