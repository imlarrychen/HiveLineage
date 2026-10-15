-- Whole-table CHANGE preserves declared types, comments and positional order.
create table dwd.ddl_change (id bigint, name string, amount double);
alter table dwd.ddl_change change column amount total decimal(12,2) comment 'renamed total' first;
alter table dwd.ddl_change change id user_id bigint after name;
insert overwrite table dwd.result(amount,name,id) select q.total as amount,q.name,q.user_id as id from (select * from dwd.ddl_change) q;
-- An earlier column name is no longer valid for another CHANGE.
alter table dwd.ddl_change change id resurrected_id bigint;
insert overwrite table dwd.result(id) select user_id from dwd.ddl_change;
