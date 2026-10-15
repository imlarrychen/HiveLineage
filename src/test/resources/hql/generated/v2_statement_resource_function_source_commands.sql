-- 场景：资源、SOURCE、函数命令保存审计类型但不加载/执行外部资源，末尾普通 INSERT 继续解析。
add jar hdfs:///lib/statement_udf.jar;
add file /tmp/statement_lookup.txt;
add archive hdfs:///lib/statement_lookup.zip;
list jar;
delete file /tmp/statement_lookup.txt;
source /tmp/statement_source.hql;
create temporary function statement_fn as 'example.StatementFunction' using jar 'hdfs:///lib/statement_udf.jar';
drop temporary function if exists statement_fn;

insert into dwd.result(id, name) select u.id, u.name from ods.users u;
