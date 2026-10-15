-- 自定义 input/output format 与 storage handler 仅 parser 可达，目前没有结构化存储模型。
CREATE TABLE dwd.ddl_custom_format (id BIGINT)
STORED AS INPUTFORMAT 'example.InputFormat' OUTPUTFORMAT 'example.OutputFormat';
CREATE TABLE dwd.ddl_storage_handler (id BIGINT)
STORED BY 'example.StorageHandler' WITH SERDEPROPERTIES ('handler.option'='enabled');
