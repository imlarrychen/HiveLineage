-- Known existence suppresses CREATE; a prior DROP proves absence for recreation.
create table dwd.ddl_if_known (id bigint);
create table if not exists dwd.ddl_if_known (name string);
insert overwrite table dwd.result(id) select q.id from (select * from dwd.ddl_if_known) q;
drop table if exists dwd.ddl_if_known;
create table if not exists dwd.ddl_if_known (name string);
insert overwrite table dwd.result(name) select q.name from (select * from dwd.ddl_if_known) q;
