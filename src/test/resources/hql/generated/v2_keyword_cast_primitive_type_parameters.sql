-- Primitive CAST grammar preserves declared type names, precision/scale and string lengths.
INSERT INTO dwd.result(id, name, amount, dept_name, tag, rn, dt)
SELECT CAST(id AS TINYINT), CAST(id AS SMALLINT), CAST(id AS INT), CAST(id AS BIGINT),
       CAST(amount AS FLOAT), CAST(amount AS DOUBLE), CAST(amount AS DECIMAL(12,2))
FROM ods.users;
INSERT INTO dwd.result(id, name, amount, dept_name, tag, rn, dt)
SELECT CAST(id AS BOOLEAN), CAST(name AS STRING), CAST(name AS CHAR(8)), CAST(name AS VARCHAR(32)),
       CAST(name AS BINARY), CAST(dt AS DATE), CAST(dt AS TIMESTAMP)
FROM ods.users;
