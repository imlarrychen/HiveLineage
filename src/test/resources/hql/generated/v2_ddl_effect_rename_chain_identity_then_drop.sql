-- Two renames, an ALTER, and DROP preserve one object's identity.
create table dwd.ddl_rename_a (id bigint, name string);
alter table dwd.ddl_rename_a rename to dwd.ddl_rename_b;
alter table dwd.ddl_rename_b rename to dwd.ddl_rename_c;
alter table dwd.ddl_rename_c add columns (amount double);
insert overwrite table dwd.result(id,name,amount) select q.id,q.name,q.amount from (select * from dwd.ddl_rename_c) q;
drop table dwd.ddl_rename_c purge;
insert overwrite table dwd.result(id) select id from dwd.ddl_rename_c;
