-- Maintenance actions cannot silently leave catalog/data certainty unchanged.
create table dwd.ddl_maintenance (id bigint);
alter table dwd.ddl_maintenance touch;
alter table dwd.ddl_maintenance compact 'major';
alter table dwd.ddl_maintenance concatenate;
alter table dwd.ddl_maintenance update statistics set ('numRows'='42');
insert overwrite table dwd.result(id) select * from dwd.ddl_maintenance;
