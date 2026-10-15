-- ALTER/DROP choose the temporary lookup before a permanent object with the same name.
create table default.ddl_shadow (id bigint, name string);
create temporary table ddl_shadow (id bigint);
alter table ddl_shadow add columns (name string);
insert overwrite table dwd.result(id,name) select q.id,q.name from (select * from ddl_shadow) q;
drop table ddl_shadow;
insert overwrite table dwd.result(id,name) select q.id,q.name from (select * from default.ddl_shadow) q;
drop table default.ddl_shadow purge;
insert overwrite table dwd.result(id) select id from default.ddl_shadow;
