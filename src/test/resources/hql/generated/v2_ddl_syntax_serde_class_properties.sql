-- TOK_TABLESERIALIZER 下 TOK_SERDENAME 同时包裹 class 与属性。
CREATE TABLE dwd.ddl_serde (
    id BIGINT,
    name STRING
)
ROW FORMAT SERDE 'example.JsonSerde'
WITH SERDEPROPERTIES ('separator'='|', 'null.token'='NA')
STORED AS ORC
TBLPROPERTIES ('owner'='integration');
