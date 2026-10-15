-- 永久函数名及 JAR/FILE/ARCHIVE 资源审计；不会加载 class 或资源。
CREATE FUNCTION default.ddl_function AS 'example.GeneratedFunction'
USING JAR 'hdfs:///lib/generated.jar', FILE 'hdfs:///lib/generated.conf', ARCHIVE 'hdfs:///lib/generated.zip';
DROP FUNCTION IF EXISTS default.ddl_function;
