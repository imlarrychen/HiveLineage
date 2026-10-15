-- Sampling unit markers are structural metadata, not numeric sample arguments.
INSERT INTO dwd.result(id) SELECT id FROM ods.users TABLESAMPLE(BUCKET 1 OUT OF 4 ON id);
INSERT INTO dwd.result(id) SELECT id FROM ods.users TABLESAMPLE(10 PERCENT);
INSERT INTO dwd.result(id) SELECT id FROM ods.users TABLESAMPLE(4 ROWS);
INSERT INTO dwd.result(id) SELECT id FROM ods.users TABLESAMPLE(100B);
