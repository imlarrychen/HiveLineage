-- ARRAY/MAP/STRUCT/UNIONTYPE 递归声明；类型与字段名称不应丢失或变成输入表。
CREATE TABLE dwd.ddl_complex_types (
    id BIGINT,
    tags ARRAY<STRING>,
    attributes MAP<STRING,ARRAY<DECIMAL(12,2)>>,
    profile STRUCT<city:STRING,account:STRUCT<score:INT,labels:ARRAY<STRING>>>,
    choice UNIONTYPE<INT,STRING,ARRAY<STRUCT<key:STRING,value:DOUBLE>>>
);
