-- 场景：开启 CATALOG_SIMULATION 后，建表/ADD/REPLACE/RENAME 的 schema 和名称依次生效，DROP 产生删除效果。
create table dwd.statement_catalog (id bigint, name string);
alter table dwd.statement_catalog add columns (amount decimal(12,2));
alter table dwd.statement_catalog replace columns (id bigint, name string, amount decimal(12,2));
alter table dwd.statement_catalog rename to dwd.statement_catalog_renamed;

insert into dwd.result(id, name, amount)
select c.id, c.name, c.amount from dwd.statement_catalog_renamed c;

drop table dwd.statement_catalog_renamed;
