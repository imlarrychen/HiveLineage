-- 场景：LOAD DATA 需显式 EXTERNAL_DATA_LINEAGE；本地/分布式路径与 overwrite/append、静态分区分别保存。
load data local inpath '/tmp/v2_statement_users.tsv' overwrite into table dwd.result partition(dt='20261014');
load data inpath 'hdfs:///warehouse/v2_statement_users.tsv' into table dwd.result partition(dt='20261015');
