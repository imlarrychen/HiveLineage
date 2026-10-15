-- These catalog options are parsed but their schema/data effects are not simulated.
create table dwd.ddl_storage (id bigint);
alter table dwd.ddl_storage set serde 'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe';
alter table dwd.ddl_storage set serdeproperties ('field.delim'=',');
alter table dwd.ddl_storage set fileformat orc;
alter table dwd.ddl_storage clustered by (id) sorted by (id) into 4 buckets;
insert overwrite table dwd.result(id) select * from dwd.ddl_storage;
-- A name change cannot resolve an earlier unmodeled schema or data change.
alter table dwd.ddl_storage rename to dwd.ddl_storage_renamed;
alter table dwd.ddl_storage_renamed add columns (name string);
insert overwrite table dwd.result(id) select q.id from (select * from dwd.ddl_storage_renamed) q;
insert overwrite table dwd.result(id) select id from ods.users;
