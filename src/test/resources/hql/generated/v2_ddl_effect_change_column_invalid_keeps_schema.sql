-- Rejected CHANGE proposals must not mutate the committed schema.
create table dwd.ddl_invalid_change (id bigint, name string) partitioned by (dt string);
alter table dwd.ddl_invalid_change change id name bigint;
alter table dwd.ddl_invalid_change change id new_id bigint after missing_column;
alter table dwd.ddl_invalid_change change dt new_dt string;
alter table dwd.ddl_invalid_change change id new_id bigint after dt;
insert overwrite table dwd.result(id,name,dt) select q.id,q.name,q.dt from (select * from dwd.ddl_invalid_change) q;
