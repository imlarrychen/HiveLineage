-- Prove no permanent fallback exists, then exercise the renamed temporary aliases.
drop table if exists default.ddl_temp_new;
create temporary table ddl_temp_old (id bigint);
alter table ddl_temp_old rename to ddl_temp_new;
alter table ddl_temp_new add columns (name string);
insert overwrite table dwd.result(id,name) select q.id,q.name from (select * from ddl_temp_new) q;
drop table ddl_temp_new;
insert overwrite table dwd.result(id) select id from ddl_temp_new;
