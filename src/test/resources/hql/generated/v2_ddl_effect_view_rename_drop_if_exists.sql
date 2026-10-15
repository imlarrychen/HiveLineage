-- Renaming a view retains its definition, then DROP removes it from lookup.
create view dwd.ddl_view_a (id,name) as select id,name from ods.users;
alter view dwd.ddl_view_a rename to dwd.ddl_view_b;
insert overwrite table dwd.result(id,name) select id,name from dwd.ddl_view_b;
drop view if exists dwd.ddl_view_b;
insert overwrite table dwd.result(id) select id from dwd.ddl_view_b;
drop view if exists dwd.ddl_absent_view;
