-- A nonauthoritative missing metastore result cannot prove object absence.
create table if not exists dwd.ddl_if_unknown (id bigint, name string);
insert overwrite table dwd.result(id,name) select * from dwd.ddl_if_unknown;
insert overwrite table dwd.result(id) select id from ods.users;
