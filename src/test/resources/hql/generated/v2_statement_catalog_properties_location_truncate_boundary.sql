-- 场景：ALTER properties/location 和 TRUNCATE 只保存不确定 catalog 效果，不宣称有数据字段血缘。
create table dwd.statement_catalog_boundary (id bigint, name string);
alter table dwd.statement_catalog_boundary set tblproperties('owner'='integration');
alter table dwd.statement_catalog_boundary set location 'hdfs:///warehouse/statement_catalog_boundary';
truncate table dwd.statement_catalog_boundary;
