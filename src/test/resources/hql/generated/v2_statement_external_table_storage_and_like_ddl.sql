-- 场景：外部表 DDL 保留复杂类型/分区/存储选项；CREATE LIKE 只保留语法，未推导来源 schema。
create external table dwd.statement_external (
    id bigint,
    name string,
    tags array<string>,
    payload struct<city:string,score:int>
) partitioned by (dt string)
row format delimited fields terminated by '\t'
stored as textfile
location 'hdfs:///warehouse/statement_external'
tblproperties('owner'='integration');

create table dwd.statement_like like ods.users;
