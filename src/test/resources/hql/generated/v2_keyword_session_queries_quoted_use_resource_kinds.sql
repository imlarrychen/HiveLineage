-- SET query forms, quoted USE and every resource command kind are audited without execution.
SET;
SET hiveconf:keyword_mode;
SET hiveconf:keyword_mode=active;
USE `ods`;
ADD JAR 'hdfs:///lib/keyword.jar';
ADD FILE '/tmp/keyword.txt';
ADD ARCHIVE 'hdfs:///lib/keyword.zip';
LIST JAR;
LIST FILE;
LIST ARCHIVE;
DELETE JAR 'hdfs:///lib/keyword.jar';
DELETE FILE '/tmp/keyword.txt';
DELETE ARCHIVE 'hdfs:///lib/keyword.zip';
USE 'invalid_database';
INSERT INTO dwd.result(id) SELECT u.id FROM users u;
